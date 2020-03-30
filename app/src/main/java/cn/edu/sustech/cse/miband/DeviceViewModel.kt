package cn.edu.sustech.cse.miband

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.threeten.bp.LocalDateTime

class DeviceViewModel : ViewModel() {
    private var miBand: MiBand? = null
    val connected = MutableLiveData(false)

    fun setConnected(band: MiBand) {
        miBand = band
        connected.value = true
    }

    suspend fun fetchData() {
        val since = LocalDateTime.of(2020, 3, 30, 0, 0, 0)
        miBand?.fetchData(since)
    }

    suspend fun monitorHeartRate() {
        miBand?.startRealtimeHeartRate()
    }

}