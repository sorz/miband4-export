package cn.edu.sustech.cse.miband

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import cn.edu.sustech.cse.miband.db.Record
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.info
import org.jetbrains.anko.warn
import org.sorz.lab.gattkt.GattIo
import org.sorz.lab.gattkt.connectGattIo
import org.threeten.bp.LocalDateTime
import java.io.IOException
import java.lang.IllegalArgumentException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Cipher.ENCRYPT_MODE
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.*

private val AUTH_CHAR_CMD_REQUEST_CHALLENGE = byteArrayOf(0x02, 0x00)
private val AUTH_CHAR_CMD_CHALLENGE_RESPONSE = byteArrayOf(0x03, 0x00)
private val AUTH_CHAR_RESP_CHALLENGE = byteArrayOf(0x10, 0x02, 0x01)
private val AUTH_CHAR_RESP_AUTH_OK = byteArrayOf(0x10, 0x03, 0x01)
private val FETCH_CHAR_CMD_CONFIRM = byteArrayOf(0x02)
private val FETCH_CHAR_RESP_START_TIME = byteArrayOf(0x10, 0x01, 0x01)
private val FETCH_CHAR_RESP_FINISHED = byteArrayOf(0x10, 0x02, 0x01)
private val FETCH_CHAR_RESP_NO_DATA = byteArrayOf(0x10, 0x02, 0x04)
private val HEART_CHAR_CMD_STOP_CONTINUES = byteArrayOf(0x15, 0x01, 0x00)
private val HEART_CHAR_CMD_STOP_MANUAL = byteArrayOf(0x15, 0x02, 0x00)
private val HEART_CHAR_CMD_START_CONTINUES = byteArrayOf(0x15, 0x01, 0x01)

class MiBand (
    context: Context,
    private val device: BluetoothDevice,
    key: String,
    lifecycleOwner: LifecycleOwner?
) : LifecycleObserver, AnkoLogger {
    private lateinit var gattIo: GattIo
    private lateinit var serviceBand1: BluetoothGattService
    private lateinit var serviceBand2: BluetoothGattService
    private lateinit var serviceHeart: BluetoothGattService

    private val context = context.applicationContext
    private val key = ByteArray(key.length / 2) { i ->
        key.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    init {
        lifecycleOwner?.lifecycle?.addObserver(this)
    }

    suspend fun connect() {
        debug { "connecting to $device" }
        gattIo = device.connectGattIo(context)
        serviceBand1 = gattIo.requireService(UUID_SERVICE_MIBAND1)
        serviceBand2 = gattIo.requireService(UUID_SERVICE_MIBAND2)
        serviceHeart = gattIo.requireService(UUID_SERVICE_HEART_RATE)
        debug { "$device connected, auth self" }
        authSelf()
    }

    suspend fun setHeartMonitorConfig(enable: Boolean, intervalMinute: Byte) {
        val charCtrl = serviceHeart.getCharacteristic(UUID_CHAR_HEART_RATE_CTRL)
            ?: throw IOException("char heart rate control not found")
        gattIo.writeCharacteristic(charCtrl, byteArrayOf(0x15, 0x00, if (enable) 0x01 else 0x00))
        gattIo.writeCharacteristic(charCtrl, byteArrayOf(0x14, intervalMinute))
    }

    suspend fun fetchData(since: LocalDateTime) = withContext(coroutineContext) {
        val charFetch = serviceBand1.getCharacteristic(UUID_CHAR_FETCH)
            ?: throw IOException("char fetch not found")
        val charActivity = serviceBand1.getCharacteristic(UUID_CHAR_ACTIVITY_DATA)
            ?: throw IOException("char activity data not found")

        gattIo.enableNotification(charFetch)
        gattIo.enableNotification(charActivity)
        gattIo.clearGattCharacteristicChangeQueue(charFetch)
        gattIo.clearGattCharacteristicChangeQueue(charActivity)

        // Send trigger to charFetch
        val trigger = byteArrayOf(0x01, 0x01) + since.toByteArray() + byteArrayOf(0x00, 0x17)
        gattIo.writeCharacteristic(charFetch, trigger)

        val resp = gattIo.readCharacteristicChange(charFetch)
        debug { "resp = ${resp.contentToString()}" }
        if (!resp.startsWith(FETCH_CHAR_RESP_START_TIME))
            throw IOException("unexpected response: ${resp.contentToString()}")
        val timeStart = resp.sliceArray(7..12).toDateTime()
        debug { "data since $timeStart" }
        gattIo.writeCharacteristic(charFetch, FETCH_CHAR_CMD_CONFIRM)

        // Receive data
        val records: MutableList<Record> = mutableListOf()
        val receiving = launch {
            var time = LocalDateTime.from(timeStart)
            while (true) {
                gattIo.readCharacteristicChange(charActivity)
                    .asSequence().drop(1).chunked(4).forEach { pkg ->
                        val step = pkg[2].toInt() and 0xff
                        val heartRate = pkg[3].toInt() and 0xff
                        if (step != 0 || heartRate != 0xff) {
                            debug { "$time step $step heart $heartRate bpm" }
                            records.add(Record(time, step, heartRate))
                        } else {
                            debug { "$time no data"}
                        }
                        time = time.plusMinutes(1)
                    }
            }
        }
        val end = gattIo.readCharacteristicChange(charFetch)
        receiving.cancelAndJoin()
        debug { "end reading" }
        gattIo.disableNotificationOrIndication(charFetch)
        gattIo.disableNotificationOrIndication(charActivity)
        records
    }

    suspend fun startRealtimeHeartRate(channel: Channel<Int>) = withContext(coroutineContext) {
        val charHeartRateCtrl = serviceHeart.getCharacteristic(UUID_CHAR_HEART_RATE_CTRL)
            ?: throw IOException("char heart rate control not found")
        val charHeartRateData = serviceHeart.getCharacteristic(UUID_CHAR_HEART_RATE_MEASURE)
            ?: throw IOException("char heart rate measure not found")

        // Stop monitor continues & manual
        gattIo.writeCharacteristic(charHeartRateCtrl, HEART_CHAR_CMD_STOP_CONTINUES)
        gattIo.writeCharacteristic(charHeartRateCtrl, HEART_CHAR_CMD_STOP_MANUAL)

        // Start monitor continues
        gattIo.enableNotification(charHeartRateData)
        gattIo.writeCharacteristic(charHeartRateCtrl, HEART_CHAR_CMD_START_CONTINUES)

        // Send ping every 12 seconds
        val keepAlive = launch {
            while (true) {
                delay(12_000)
                gattIo.writeCharacteristic(charHeartRateCtrl, byteArrayOf(0x16))
            }
        }
        // Receive data
        try {
            while (true) {
                val resp = gattIo.readCharacteristicChange(charHeartRateData)
                if (resp[0] != 0.toByte()) {
                    channel.close(IOException("Unexpected data: ${resp.contentToString()}"))
                    break
                }
                val bpm = resp[1].toInt() and 0xff
                debug { "$bpm bpm" }
                if (!channel.offer(bpm))
                    info("fail to offer bpm data")
            }
        } catch (err: CancellationException) {
            // Gracefully shutdown
            debug("realtimeHeartRateJob cancelled")
            keepAlive.cancel()
            channel.cancel(err)
            gattIo.writeCharacteristic(charHeartRateCtrl, HEART_CHAR_CMD_STOP_CONTINUES)
            gattIo.disableNotificationOrIndication(charHeartRateData)
        } catch (err: IOException) {
            warn { "error on read heat beat: $err" }
            channel.close(err)
        } finally {
            debug("realtimeHeartRateJob exited")
            keepAlive.cancel()
            channel.close()
        }
    }

    private suspend fun authSelf() {
        val charAuth = serviceBand2.getCharacteristic(UUID_CHAR_AUTH)
            ?: throw IOException("char auth not found")

        gattIo.enableNotification(charAuth)
        gattIo.clearGattCharacteristicChangeQueue(charAuth)

        // Request challenge
        gattIo.writeCharacteristic(charAuth, AUTH_CHAR_CMD_REQUEST_CHALLENGE)
        val resp = gattIo.readCharacteristicChange(charAuth)
        if (!resp.startsWith(AUTH_CHAR_RESP_CHALLENGE))
            throw IOException("expect AUTH_CHAR_RESP_CHALLENGE, got ${resp.contentToString()}")
        if (resp.size != AUTH_CHAR_RESP_CHALLENGE.size + 16)
            throw IOException("wrong size of challenge: ${resp.size}")
        val challenge = resp.sliceArray(AUTH_CHAR_RESP_CHALLENGE.size until resp.size)

        // Send back challenge
        val response = encryptChallenge(challenge)
        gattIo.writeCharacteristic(charAuth, AUTH_CHAR_CMD_CHALLENGE_RESPONSE + response)
        val result = gattIo.readCharacteristicChange(charAuth)
        if (!result.contentEquals(AUTH_CHAR_RESP_AUTH_OK)) {
            warn { "self auth failed, response: ${result.contentToString()}" }
            throw IOException("Fail to authenticate self (wrong key?)")
        }
        debug { "self authenticated" }

        gattIo.disableNotificationOrIndication(charAuth)
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
        debug("disconnecting")
        if (::gattIo.isInitialized)
            gattIo.gatt.close()
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