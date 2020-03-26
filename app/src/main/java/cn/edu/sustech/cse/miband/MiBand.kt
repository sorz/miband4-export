package cn.edu.sustech.cse.miband

import android.bluetooth.*
import android.bluetooth.BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
import android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
import android.content.Context
import androidx.collection.CircularArray
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.warn
import java.io.IOException
import java.lang.IllegalStateException
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val AUTH_CHAR_CMD_REQUEST_CHALLENGE = byteArrayOf(0x02, 0x00)

class MiBand (
    context: Context,
    private val device: BluetoothDevice,
    private val key: String,
    lifecycleOwner: LifecycleOwner
) : LifecycleObserver, AnkoLogger {
    private lateinit var bleGatt: BluetoothGatt
    private lateinit var charAuth: BluetoothGattCharacteristic
    private val context = context.applicationContext

    private var connectContinuation: Continuation<Unit>? = null
    private var charWriteCont: MutableMap<UUID, Continuation<Unit>> = mutableMapOf()
    private var descWriteCont: MutableMap<Pair<UUID, UUID>, Continuation<Unit>> = mutableMapOf()
    private var charChangeCont: MutableMap<UUID, Continuation<ByteArray>> = mutableMapOf()
    private var charChangeQueue: MutableMap<UUID, CircularArray<ByteArray>> = mutableMapOf()


    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            bleGatt = gatt
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    debug { "GATT connected, discover services" }
                    if (!gatt.discoverServices())
                        throwException(IOException("fail to discover services"))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    throwException(IOException("GATT disconnected"))
                }
                else -> error("Unknown GATT state: $newState")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            debug("service discovered ${gatt.services}")
            val band1 = gatt.getService(UUID_SERVICE_MIBAND1)
                ?: return throwException(IOException("no MiBand1 service found"))
            val band2 = gatt.getService(UUID_SERVICE_MIBAND2)
                ?: return throwException(IOException("no miBand2 service found"))

            charAuth = band2.getCharacteristic(UUID_CHARACTERISTIC_AUTH)
                ?: return throwException(IOException("no auth characteristic found"))
            connectContinuation?.resume(Unit)
            connectContinuation = null
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            debug("descriptor WRITTEN $descriptor $status")
            val key = Pair(descriptor.characteristic.uuid, descriptor.uuid)
            val cont = descWriteCont.remove(key)
            if (cont == null) {
                warn("descriptor $descriptor not found")
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS)
                cont.resumeWithException(IOException("Fail to write descriptor: $status"))
            else
                cont.resume(Unit)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            debug("characteristic WRITTEN ${char.uuid} $status")
            val cont = charWriteCont.remove(char.uuid)
            if (cont == null) {
                warn("characteristic $char not found")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS)
                cont.resumeWithException(IOException("Fail to write characteristic: $status"))
            else
                cont.resume(Unit)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            debug("characteristic CHANGED ${char.uuid} ${char.value}")
            charChangeCont.remove(char.uuid)?.resume(char.value)
                ?: charChangeQueue.getOrPut(char.uuid) { CircularArray() }.addLast(char.value)
        }
    }

    private suspend fun enableNotification(char: BluetoothGattCharacteristic, enable: Boolean) {
        val desc = char.getDescriptor(UUID_CLIENT_CHAR_CONFIG)
            ?: throw IOException("missing config descriptor on $char")
        val key = Pair(char.uuid, desc.uuid)
        if (descWriteCont.containsKey(key))
            throw IllegalStateException("last enableNotification() not finish")

        if (!bleGatt.setCharacteristicNotification(charAuth, enable))
            throw IOException("fail to set notification on $char")

        return suspendCoroutine { cont ->
            descWriteCont[key] = cont
            desc.value = if (enable)  ENABLE_NOTIFICATION_VALUE else DISABLE_NOTIFICATION_VALUE
            if (!bleGatt.writeDescriptor(desc))
                cont.resumeWithException(IOException("fail to config descriptor $this"))
        }
    }

    private suspend fun writeCharacteristic(char: BluetoothGattCharacteristic, value: ByteArray) {
        if (charWriteCont.containsKey(char.uuid))
            throw IllegalStateException("last writeCharacteristic() not finish")
        return suspendCoroutine { cont ->
            charWriteCont[char.uuid] = cont
            char.value = value
            bleGatt.writeCharacteristic(char)
        }
    }

    private suspend fun readCharChange(char: BluetoothGattCharacteristic): ByteArray {
        val queuedData = charChangeQueue[char.uuid]?.takeIf { !it.isEmpty }?.popFirst()
        if (queuedData != null) return queuedData

        if (charChangeCont.containsKey(char.uuid))
            throw IllegalStateException("last enableNotification() not finish")
        return suspendCoroutine { cont ->
            charChangeCont[char.uuid] = cont
        }
    }

    private fun throwException(throwable: Throwable) {
        warn { "throw exception ${throwable.message}" }
        connectContinuation?.apply {
            resumeWithException(throwable)
            connectContinuation = null
        }
        charWriteCont.values.forEach { it.resumeWithException(throwable) }
        descWriteCont.values.forEach { it.resumeWithException(throwable) }
    }

    suspend fun connect() {
        if (connectContinuation != null) throw IllegalStateException("repeated invoking connect()")
        suspendCoroutine { cont: Continuation<Unit> ->
            connectContinuation = cont
            debug { "connecting to $device" }
            device.connectGatt(context, false, gattCallback)
        }
        debug { "$device connected, auth self" }
        enableNotification(charAuth, true)
        writeCharacteristic(charAuth, AUTH_CHAR_CMD_REQUEST_CHALLENGE)
        val resp = readCharChange(charAuth)
        debug { "charAuth.value = $resp" }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun disconnect() {
        if (::bleGatt.isInitialized)
            bleGatt.disconnect()
    }

}