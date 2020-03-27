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
import java.io.IOException


class DeviceFragment : Fragment() {
    private val args: DeviceFragmentArgs by navArgs()
    private val viewModel: DeviceViewModel by viewModels()

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

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            val key = ByteArray(args.key.length / 2) { i ->
                args.key.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val band = MiBand(requireContext(), args.device, key, viewLifecycleOwner)
            band.connect()
            viewModel.setConnected(band)
        }

        fetch_button.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                try {
                    viewModel.fetchData()
                } catch (err: IOException) {
                    showSnack(err.localizedMessage ?: "I/O error")
                }
            }
        }
    }
}
