package com.mdevz.sp.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.mdevz.sp.R
import com.mdevz.sp.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding:
        FragmentSettingsBinding? = null

    private val binding:
        FragmentSettingsBinding
        get() = requireNotNull(_binding)

    private var controller:
        SettingsController? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentSettingsBinding.inflate(
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

        val settingsController =
            SettingsController(
                context = requireContext(),
                binding = binding
            )

        controller = settingsController
        settingsController.setup()

        binding.buttonAbout.setOnClickListener {
            findNavController().navigate(
                R.id.action_settings_to_about
            )
        }
    }

    override fun onDestroyView() {
        controller = null
        _binding = null
        super.onDestroyView()
    }
}
