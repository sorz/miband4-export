package cn.edu.sustech.cse.miband

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SelectViewModel : ViewModel() {
    val folderSelected = MutableLiveData(false)

}