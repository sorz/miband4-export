package cn.edu.sustech.cse.miband

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.Intent.ACTION_OPEN_DOCUMENT_TREE
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract.EXTRA_INITIAL_URI
import android.text.Html
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_select.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import orz.sorz.lab.blescanner.BLEScanner
import java.util.*


private const val REQUEST_SELECT_FILE = 1
private const val PREF_FREE_MY_BAND_URI = "free-my-band-uri"

class SelectFragment : Fragment(), AnkoLogger {
    private val bleScanner by lazy { BLEScanner(requireContext(), this) }
    private val bluetoothManager by lazy {
        requireContext().getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val preferences by lazy { requireActivity().getPreferences(MODE_PRIVATE) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_select, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prerequisites_text.text = Html.fromHtml(
            "<h2>Prerequisites</h2><br>" +
                    "1) Un-pair your band and uninstall existing official Mi Fit app " +
                    "if you have it installed.<br><br>" +
                    "2) Download and install modified Mi Fit app from " +
                    "https://www.freemyband.com<br><br>" +
                    "3) Pair the band with the modified Mi Fit. Keys will write out to " +
                    "<i>/sdcard/freemyband</i> folder once paired.<br><br>" +
                    "4) Select the folder <i>/sdcard/freemyband</i> below:",
            Html.FROM_HTML_MODE_COMPACT
        )
        select_button.setOnClickListener {
            Intent(ACTION_OPEN_DOCUMENT_TREE).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    putExtra(EXTRA_INITIAL_URI, "/sdcard/freemyband/")
                startActivityForResult(this, REQUEST_SELECT_FILE)
            }
        }
        connect_button.setOnClickListener { button ->
            val uri = preferences.getString(PREF_FREE_MY_BAND_URI, null)
            if (uri == null) {
                showSnack("Please select FreeMyBand folder first")
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launchWhenResumed {
                button.isEnabled = false
                val deviceKeys = readDeviceKeys(Uri.parse(uri))
                if (deviceKeys.isEmpty()) {
                    showSnack("No key found in FreeMyBand folder")
                    return@launchWhenResumed
                }
                scan(deviceKeys)
            }.invokeOnCompletion {
                button.isEnabled = true
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        bleScanner.onActivityResult(requestCode, resultCode)
        when (requestCode) {
            REQUEST_SELECT_FILE -> {
                if (resultCode != Activity.RESULT_OK || data == null) return
                data.data?.let { uri ->
                    preferences.edit().putString(PREF_FREE_MY_BAND_URI, uri.toString()).apply()
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bleScanner.onRequestPermissionsResult(requestCode, grantResults)
    }

    private suspend fun readDeviceKeys(uri: Uri): Map<String, String> {
        val folder = DocumentFile.fromTreeUri(requireContext(), uri) ?: return mapOf()
        val files = folder.listFiles().filter {
            it.isFile && it.canRead() &&
                    it.name.orEmpty().matches(Regex("miband[0-9A-F]{12}\\.txt"))
        }
        val resolver = requireContext().contentResolver

        val regex = Regex("([0-9A-F:]{17});([0-9a-f]{32})")
        val macKey = mutableMapOf<String, String>()
        withContext(Dispatchers.IO) {
            for (file in files) {
                val content = resolver.openInputStream(file.uri)
                    ?.reader()
                    ?.readText()
                    ?.trim() ?: continue
                val groups = regex.find(content)?.groups ?: continue
                val mac = groups[1]?.value?.toUpperCase(Locale.ENGLISH) ?: continue
                val key = groups[2]?.value ?: continue
                macKey[mac] = key
            }
        }
        return macKey
    }

    private suspend fun scan(macKey: Map<String, String>) {
        if (!bleScanner.initialize(requireActivity(), this)) {
            showSnack("permission denied")
            return
        }

        var device = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).find {
            macKey.containsKey(it.address.toUpperCase(Locale.ENGLISH))
        }
        if (device == null) {
            debug { "no connected device, search for new one" }
            val filters = macKey.keys.map {
                debug { "filter $it added" }
                ScanFilter.Builder().setDeviceAddress(it).build()
            }
            val settings = ScanSettings.Builder().build()
            device = bleScanner.startScan(filters, settings).receive()
        }
        debug { "${device.name} (${device.address}) found" }

        val key = macKey[device.address.toUpperCase(Locale.ENGLISH)] ?: error("missing key")

        SelectFragmentDirections.actionConnect(device, key).apply {
            findNavController().navigate(this)
        }

    }
}