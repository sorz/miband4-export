package cn.edu.sustech.cse.miband

import android.annotation.SuppressLint
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
import org.threeten.bp.LocalDateTime
import java.io.IOException
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import javax.crypto.Cipher
import javax.crypto.Cipher.ENCRYPT_MODE
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val AUTH_CHAR_CMD_REQUEST_CHALLENGE = byteArrayOf(0x02, 0x00)
private val AUTH_CHAR_CMD_CHALLENGE_RESPONSE = byteArrayOf(0x03, 0x00)
private val AUTH_CHAR_RESP_CHALLENGE = byteArrayOf(0x10, 0x02, 0x01)
private val AUTH_CHAR_RESP_AUTH_OK = byteArrayOf(0x10, 0x03, 0x01)


class MiBand (
    context: Context,
    private val device: BluetoothDevice,
    private val key: ByteArray,
    lifecycleOwner: LifecycleOwner
) : LifecycleObserver, AnkoLogger {
    private lateinit var bleGatt: BluetoothGatt
    private lateinit var charAuth: BluetoothGattCharacteristic
    private lateinit var charSteps: BluetoothGattCharacteristic
    private lateinit var charFetch: BluetoothGattCharacteristic
    private lateinit var charActivity: BluetoothGattCharacteristic

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

            charAuth = band2.getCharacteristic(UUID_CHAR_AUTH)
                ?: return throwException(IOException("no auth characteristic found"))
            charSteps = band1.getCharacteristic(UUID_CHAR_STEPS)
                ?: return throwException(IOException("no steps characteristic found"))
            charFetch = band1.getCharacteristic(UUID_CHAR_FETCH)
                ?: return throwException(IOException("no fetch characteristic found"))
            charActivity = band1.getCharacteristic(UUID_CHAR_ACTIVITY_DATA)
                ?: return throwException(IOException("no activity characteristic found"))
            connectContinuation?.resume(Unit)
            connectContinuation = null
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            debug("descriptor WRITTEN ${descriptor.characteristic.uuid} $status")
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
            debug("characteristic WRITTEN ${char.uuid} $status ${char.value?.contentToString()}")
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
            debug("characteristic CHANGED ${char.uuid} ${char.value?.contentToString()}")
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

    private fun clearCharChangeQueue(char: BluetoothGattCharacteristic) {
        charChangeQueue.remove(char.uuid)
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
            device.connectGatt(context, true, gattCallback)
        }
        debug { "$device connected, auth self" }
        authSelf()
    }

    suspend fun fetchData(since: LocalDateTime) {
        enableNotification(charFetch, true)
        enableNotification(charActivity, true)
        clearCharChangeQueue(charFetch)
        clearCharChangeQueue(charActivity)

        // Send trigger to charFetch
        val trigger = byteArrayOf(0x01, 0x01) + since.toByteArray() + byteArrayOf(0x00, 0x17)
        writeCharacteristic(charFetch, trigger)


        val resp = readCharChange(charFetch)
        debug { "resp = ${resp.contentToString()}" }

    }

    private suspend fun authSelf() {
        enableNotification(charAuth, true)
        clearCharChangeQueue(charAuth)

        // Request challenge
        writeCharacteristic(charAuth, AUTH_CHAR_CMD_REQUEST_CHALLENGE)
        val resp = readCharChange(charAuth)
        if (!resp.startsWith(AUTH_CHAR_RESP_CHALLENGE))
            throw IOException("expect AUTH_CHAR_RESP_CHALLENGE, got ${resp.contentToString()}")
        if (resp.size != AUTH_CHAR_RESP_CHALLENGE.size + 16)
            throw IOException("wrong size of challenge: ${resp.size}")
        val challenge = resp.sliceArray(AUTH_CHAR_RESP_CHALLENGE.size until resp.size)

        // Send back challenge
        val response = encryptChallenge(challenge)
        writeCharacteristic(charAuth, AUTH_CHAR_CMD_CHALLENGE_RESPONSE + response)
        val result = readCharChange(charAuth)
        if (!result.contentEquals(AUTH_CHAR_RESP_AUTH_OK)) {
            warn { "self auth failed, response: ${result.contentToString()}" }
            throw IOException("Fail to authenticate self (wrong key?)")
        }
        debug { "self authenticated" }
        //enableNotification(charAuth, false)
    }

    @SuppressLint("GetInstance")
    private fun encryptChallenge(challenge: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(ENCRYPT_MODE, keySpec)
        return cipher.doFinal(challenge)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun disconnect() {
        if (::bleGatt.isInitialized)
            bleGatt.disconnect()
    }
}

fun ByteArray.startsWith(head: ByteArray): Boolean {
    if (size < head.size) return false
    for (i in head.indices)
        if (this[i] != head[i]) return false
    return true
}

private fun LocalDateTime.toByteArray() = ByteBuffer.allocate(6).apply {
    order(ByteOrder.LITTLE_ENDIAN)
    putShort(year.toShort())
    put(monthValue.toByte())
    put(dayOfMonth.toByte())
    put(hour.toByte())
    put(minute.toByte())
}.array()

private fun ByteArray.toDateTime(): LocalDateTime {
    if (size != 6) throw IllegalArgumentException("Wrong size of datetime $size")
    ByteBuffer.wrap(this).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        return LocalDateTime.of(short.toInt(), get().toInt(),
            get().toInt(), get().toInt(), get().toInt())
    }
}