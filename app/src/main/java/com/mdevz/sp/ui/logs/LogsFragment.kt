package com.mdevz.sp.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdevz.sp.databinding.FragmentLogsBinding
import com.mdevz.sp.service.TunnelRuntime
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {

    private var _binding:
        FragmentLogsBinding? = null

    private val binding:
        FragmentLogsBinding
        get() = requireNotNull(_binding)

    private var controller:
        LogsController? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentLogsBinding.inflate(
                inflater,
                container,
                false
            )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        val runtime =
            TunnelRuntime.get(
                requireContext()
            )

        val logsController =
            LogsController(
                context =
                    requireContext(),
                binding = binding,
                logger = runtime.logger
            )

        controller =
            logsController

        logsController.setup()

        viewLifecycleOwner
            .lifecycleScope
            .launch {
                viewLifecycleOwner
                    .repeatOnLifecycle(
                        Lifecycle.State.STARTED
                    ) {
                        runtime.logger.entries
                            .collect {
                                logsController
                                    .render(it)
                            }
                    }
            }
    }

    override fun onDestroyView() {
        controller?.release()
        controller = null
        _binding = null
        super.onDestroyView()
    }
}
