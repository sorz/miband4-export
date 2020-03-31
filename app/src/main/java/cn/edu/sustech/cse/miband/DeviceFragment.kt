package cn.edu.sustech.cse.miband

import android.content.Intent.*
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.work.*
import cn.edu.sustech.cse.miband.databinding.FragmentDeviceBinding
import cn.edu.sustech.cse.miband.db.HeartBeatDao
import cn.edu.sustech.cse.miband.db.RecordDao
import kotlinx.android.synthetic.main.fragment_device.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.threeten.bp.LocalDateTime
import java.io.File
import java.io.IOException


private const val HEART_BEAT_WORK_NAME = "work-heat-beat"

class DeviceFragment : Fragment(), AnkoLogger {
    private val args: DeviceFragmentArgs by navArgs()
    private val viewModel: DeviceViewModel by viewModels()
    private val recordDao by lazy { requireContext().database.recordDao() }
    private val heartBeatDao by lazy { requireContext().database.heartBeatDao() }
    private lateinit var miBand: MiBand
    private lateinit var workManager: WorkManager

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
        miBand = MiBand(requireContext(), args.device, args.key, viewLifecycleOwner)
        workManager = WorkManager.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            miBand.connect()
            viewModel.ready.value = true
        }

        fetch_button.setOnClickListener { fetchData() }
        monitor_enable_button.setOnClickListener { enableBackgroundHeartRate() }
        monitor_disable_button.setOnClickListener { disableBackgroundHeartRate() }
        delete_activities_button.setOnClickListener { deleteAllRecords() }
        export_activities_button.setOnClickListener { exportActivitiesAsCsv() }
        heart_rate_button.setOnClickListener { realtimeHeartRate() }
        heart_rate_stop_button.setOnClickListener { stopRealtimeHeartRate() }
        export_heart_button.setOnClickListener { exportHeartAsCsv() }
        delete_heart_button.setOnClickListener { deleteAllHeartRate() }

        workManager.getWorkInfosForUniqueWorkLiveData(HEART_BEAT_WORK_NAME)
            .observe(viewLifecycleOwner, Observer { works ->
                viewModel.heartBeatWorkerStarted.value =
                    !(works.isEmpty() || works.first().state.isFinished)
            })
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
        val since = recordDao.loadLastTime()?.minusHours(3)
            ?: LocalDateTime.now().minusDays(1)
        debug { "fetch data since $since" }
        val records = miBand.fetchData(since)
        debug { "${records.size} records fetched" }
        if (records.isEmpty()) {
            showSnack("No new record")
        } else {
            recordDao.insertAll(records)
            showSnack("Records fetched: ${records.size}" )
        }
    }

    private fun realtimeHeartRate() {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .setRequiresBatteryNotLow(true)
            .build()
        val input = workDataOf(
            "device-address" to args.device.address,
            "device-key" to args.key
        )
        val work = OneTimeWorkRequestBuilder<HeartBeatWorker>()
            .setConstraints(constraints)
            .setInputData(input)
            .build()
        workManager.enqueueUniqueWork(HEART_BEAT_WORK_NAME, ExistingWorkPolicy.KEEP, listOf(work))
    }

    private fun stopRealtimeHeartRate() {
        workManager.cancelUniqueWork(HEART_BEAT_WORK_NAME)
    }

    private fun enableBackgroundHeartRate() = operateBand {
        miBand.setHeartMonitorConfig(true, 1)
        showSnack("Background heart rate monitor enabled" )
    }

    private fun disableBackgroundHeartRate() = operateBand {
        miBand.setHeartMonitorConfig(false, 0)
        showSnack("Background heart rate monitor disabled" )
    }

    private fun deleteAllRecords() = operateBand {
        recordDao.deleteAll()
        showSnack("All records removed")
    }

    private fun deleteAllHeartRate() = operateBand {
        heartBeatDao.deleteAll()
        showSnack("All records removed")
    }

    private fun exportActivitiesAsCsv() = operateBand {
        val records = recordDao.selectAll()
        if (records.isEmpty()) {
            showSnack("No records found")
            return@operateBand
        }

        val file = createExportCsvFile("activities-${records.last().time}")
        withContext(Dispatchers.IO) {
            val writer = file.outputStream().bufferedWriter()
            writer.write("time,step,heart_beat\r\n")
            for (record in records)
                record.apply {
                    writer.write("$time,$step,$heartRate\r\n")
                }
            writer.flush()
            writer.close()
        }
        exportCsvFile(file)
    }

    private fun exportHeartAsCsv() = operateBand {
        val records = heartBeatDao.selectAll()
        if (records.isEmpty()) {
            showSnack("no records found")
            return@operateBand
        }

        val file = createExportCsvFile("heartbeat-${records.last().time}")
        withContext(Dispatchers.IO) {
            val writer = file.outputStream().bufferedWriter()
            writer.write("time,bpm\r\n")
            for (record in records)
                writer.write("${record.time},${record.bpm}\r\n")
            writer.flush()
            writer.close()
        }
        exportCsvFile(file)
    }

    private fun createExportCsvFile(basename: String): File {
        val dir = File(requireContext().cacheDir, "exported")
        dir.mkdirs()
        val file = File(dir, "$basename.csv")
        file.deleteOnExit()
        return file
    }

    private fun exportCsvFile(file: File) {
        val uri = FileProvider.getUriForFile(requireContext(), requireContext().packageName, file)
        val intent = ShareCompat.IntentBuilder.from(requireActivity())
            .setStream(uri)
            .setType("text/csv")
            .intent
            .setAction(ACTION_SEND)
            .setDataAndType(uri, "text/csv")
            .addFlags(FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

}
