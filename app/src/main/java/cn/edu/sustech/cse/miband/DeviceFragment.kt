package cn.edu.sustech.cse.miband

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import cn.edu.sustech.cse.miband.databinding.FragmentDeviceBinding
import kotlinx.android.synthetic.main.fragment_device.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.threeten.bp.LocalDateTime
import java.io.IOException


class DeviceFragment : Fragment(), AnkoLogger {
    private val args: DeviceFragmentArgs by navArgs()
    private val viewModel: DeviceViewModel by viewModels()
    private lateinit var miBand: MiBand

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = FragmentDeviceBinding.inflate(inflater, container, false).apply {
        lifecycleOwner = this@DeviceFragment
        model = viewModel
        device = args.device
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val key = ByteArray(args.key.length / 2) { i ->
            args.key.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        miBand = MiBand(requireContext(), args.device, key, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            miBand.connect()
            viewModel.ready.value = true
        }

        fetch_button.setOnClickListener { fetchData() }
        heart_rate_button.setOnClickListener { realtimeHeartRate() }
        monitor_enable_button.setOnClickListener { enableBackgroundHeartRate() }
        monitor_disable_button.setOnClickListener { disableBackgroundHeartRate() }
    }

    private fun operateBand(block: suspend CoroutineScope.() -> Unit) {
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            viewModel.ready.value = false
            try {
                block.invoke(this)
            } catch (err: IOException) {
                showSnack(err.localizedMessage ?: "I/O error")
            }
        }.invokeOnCompletion { viewModel.ready.value = true }
    }

    private fun fetchData() = operateBand {
        val dao = requireContext().database.recordDao()
        val since = withContext(Dispatchers.IO) {
            dao.loadLastTime()?.minusHours(3) ?: LocalDateTime.now().minusDays(1)
        }
        debug { "fetch data since $since" }
        val records = miBand.fetchData(since)
        debug { "${records.size} records fetched" }
        if (records.isEmpty()) {
            showSnack("No new record")
        } else {
            withContext(Dispatchers.IO) {
                dao.insertAll(records)
            }
            showSnack("Records fetched: ${records.size}" )
        }
    }

    private fun realtimeHeartRate() = operateBand {
        viewModel.ready.value = false
        miBand.startRealtimeHeartRate()
    }

    private fun enableBackgroundHeartRate() = operateBand {
        miBand.setHeartMonitorConfig(true, 1)
        showSnack("Background heart rate monitor enabled" )
    }

    private fun disableBackgroundHeartRate() = operateBand {
        miBand.setHeartMonitorConfig(false, 0)
        showSnack("Background heart rate monitor disabled" )
    }


}
