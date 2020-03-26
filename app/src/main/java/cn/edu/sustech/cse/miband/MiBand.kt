package cn.edu.sustech.cse.miband

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.warn
import java.io.IOException
import java.lang.IllegalStateException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MiBand (
    context: Context,
    private val device: BluetoothDevice,
    private val key: String,
    lifecycleOwner: LifecycleOwner
) : LifecycleObserver, AnkoLogger {
    private lateinit var bleGatt: BluetoothGatt
    private val context = context.applicationContext

    private var connectContinuation: Continuation<Unit>? = null

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
    }

    private fun throwException(throwable: Throwable) {
        warn { "throw exception ${throwable.message}" }
        connectContinuation?.apply {
            resumeWithException(throwable)
            connectContinuation = null
        }
    }

    suspend fun connect(): Unit {
        if (connectContinuation != null) throw IllegalStateException("repeated invoking connect()")
        return suspendCoroutine { cont ->
            connectContinuation = cont
            debug { "connecting to $device" }
            device.connectGatt(context, false, gattCallback)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun disconnect() {
        if (::bleGatt.isInitialized)
            bleGatt.disconnect()
    }

}