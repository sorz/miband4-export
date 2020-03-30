package cn.edu.sustech.cse.miband

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class DeviceViewModel : ViewModel() {
    val ready = MutableLiveData(false)

}