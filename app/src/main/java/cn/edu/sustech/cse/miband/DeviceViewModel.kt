package cn.edu.sustech.cse.miband

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class DeviceViewModel : ViewModel() {
    val bandReady = MutableLiveData(false)
    val databaseReady = MutableLiveData(true)
    val heartBeatWorkerStarted = MutableLiveData(false)

}