package com.mdevz.sp.ui.editor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.mdevz.sp.R
import com.mdevz.sp.core.model.PayloadMode
import com.mdevz.sp.core.model.SshProfile
import com.mdevz.sp.databinding.FragmentProfileEditorBinding
import com.mdevz.sp.service.TunnelRuntime
import java.util.UUID

class ProfileEditorFragment : Fragment() {

    private val autosaveHandler =
        Handler(Looper.getMainLooper())

    private var autosaveRunnable: Runnable? = null

    private fun scheduleAutosave() {
        autosaveRunnable?.let { pending ->
            autosaveHandler.removeCallbacks(pending)
        }

        val pending =
            Runnable {
                autosaveRunnable = null

                if (_binding != null) {
                    saveIfReady()
                }
            }

        autosaveRunnable = pending

        autosaveHandler.postDelayed(
            pending,
            TEXT_AUTOSAVE_DEBOUNCE_MILLIS
        )
    }

    private var _binding: FragmentProfileEditorBinding? = null

    private val binding: FragmentProfileEditorBinding
        get() = requireNotNull(_binding)

    private lateinit var runtime: TunnelRuntime
    private lateinit var profileId: String

    private var loading = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentProfileEditorBinding.inflate(
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

        runtime =
            TunnelRuntime.get(
                requireContext()
            )

        profileId =
            arguments
                ?.getString(ARG_PROFILE_ID)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: UUID.randomUUID()
                    .toString()

        val profile =
            runtime.profiles
                .load(profileId)
                ?: emptyProfile()

        bind(profile)
        installAutoSave()

        loading = false
    }

    private fun emptyProfile(): SshProfile =
        SshProfile(
            name = "",
            sshHost = "",
            username = "",
            password = ""
        )

    private fun bind(
        profile: SshProfile
    ) {
        binding.textDialogProfileName.text =
            profile.name

        binding.inputName.setText(
            profile.name
        )

        binding.inputSshHost.setText(
            profile.sshHost
        )

        binding.inputSshPort.setText(
            profile.sshPort.toString()
        )

        binding.inputUsername.setText(
            profile.username
        )

        binding.inputPassword.setText(
            profile.password
        )

        binding.switchProxy.isChecked =
            profile.proxyEnabled

        binding.switchPayload.isChecked =
            profile.payloadEnabled

        when (profile.payloadMode) {
            PayloadMode.NORMAL ->
                binding.radioNormal.isChecked = true

            PayloadMode.ENHANCED ->
                binding.radioEnhanced.isChecked = true
        }

        binding.inputPayload.setText(
            profile.payload
        )

        binding.inputProxyHost.setText(
            profile.proxyHost
        )

        binding.inputProxyPort.setText(
            profile.proxyPort.toString()
        )

        binding.switchTls.isChecked =
            profile.tlsEnabled

        binding.inputSni.setText(
            profile.sni
        )

        binding.inputTlsVersion.setText(
            profile.tlsVersion,
            false
        )

        binding.inputSocksPort.setText(
            profile.localSocksPort.toString()
        )

        binding.inputKeepAlive.setText(
            profile.keepAliveSeconds.toString()
        )

        binding.inputSendBufferSize.setText(
            profile.socketSendBufferSize.toString()
        )

        binding.inputReceiveBufferSize.setText(
            profile.socketReceiveBufferSize.toString()
        )

        binding.switchTcpNoDelay.isChecked =
            profile.tcpNoDelay

        binding.switchCpuWakeLock.isChecked =
            profile.cpuWakeLockEnabled

        binding.switchAutoReconnect.isChecked =
            profile.autoReconnect

        binding.inputMaxReconnect.setText(
            profile.maxReconnect.toString()
        )

        installTlsVersionAdapter()
        updateConditionalVisibility()
    }

    private fun installTlsVersionAdapter() {
        val values =
            listOf(
                "default",
                "TLSv1.2",
                "TLSv1.3"
            )

        binding.inputTlsVersion.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                values
            )
        )
    }

    private fun installAutoSave() {
        val textFields =
            listOf(
                binding.inputName,
                binding.inputSshHost,
                binding.inputSshPort,
                binding.inputUsername,
                binding.inputPassword,
                binding.inputPayload,
                binding.inputProxyHost,
                binding.inputProxyPort,
                binding.inputSni,
                binding.inputSocksPort,
                binding.inputKeepAlive,
                binding.inputSendBufferSize,
                binding.inputReceiveBufferSize,
                binding.inputMaxReconnect
            )

        textFields.forEach { field ->
            field.doAfterTextChanged {
                if (field === binding.inputName) {
                    binding.textDialogProfileName.text =
                        it?.toString().orEmpty()
                }

                scheduleAutosave()
            }
        }

        binding.inputTlsVersion
            .setOnItemClickListener { _, _, _, _ ->
                saveIfReady()
            }

        binding.switchProxy
            .setOnCheckedChangeListener { _, _ ->
                updateConditionalVisibility()
                saveIfReady()
            }

        binding.switchPayload
            .setOnCheckedChangeListener { _, _ ->
                updateConditionalVisibility()
                saveIfReady()
            }

        binding.groupPayloadMode
            .setOnCheckedChangeListener { _, _ ->
                saveIfReady()
            }

        binding.switchTls
            .setOnCheckedChangeListener { _, _ ->
                updateConditionalVisibility()
                saveIfReady()
            }

        binding.switchTcpNoDelay
            .setOnCheckedChangeListener { _, _ ->
                saveIfReady()
            }

        binding.switchCpuWakeLock
            .setOnCheckedChangeListener { _, _ ->
                saveIfReady()
            }

        binding.switchAutoReconnect
            .setOnCheckedChangeListener { _, _ ->
                saveIfReady()
            }
    }

    private fun updateConditionalVisibility() {
        val payloadEnabled =
            binding.switchPayload.isChecked

        binding.groupPayloadMode.isVisible =
            payloadEnabled

        binding.layoutPayload.isVisible =
            payloadEnabled


        val proxyEnabled =
            binding.switchProxy.isChecked

        binding.layoutProxyHost.isVisible =
            proxyEnabled

        binding.layoutProxyPort.isVisible =
            proxyEnabled

        val tlsEnabled =
            binding.switchTls.isChecked

        binding.layoutSni.isVisible =
            tlsEnabled

        binding.layoutTlsVersion.isVisible =
            tlsEnabled
    }

    private fun saveIfReady() {
        if (loading) {
            return
        }

        saveProfile()
    }

    private fun saveProfile() {
        val previous =
            runtime.profiles
                .load(profileId)

        val defaults =
            emptyProfile()

        val proxyHost =
            binding.inputProxyHost
                .text
                ?.toString()
                .orEmpty()

        val proxyPort =
            binding.inputProxyPort
                .text
                ?.toString()
                ?.toIntOrNull()
                ?: 0

        val proxyEnabled =
            binding.switchProxy.isChecked

        val payloadMode =
            if (binding.radioEnhanced.isChecked) {
                PayloadMode.ENHANCED
            } else {
                PayloadMode.NORMAL
            }

        val profile =
            SshProfile(
                name =
                    binding.inputName
                        .text
                        ?.toString()
                        .orEmpty(),

                sshHost =
                    binding.inputSshHost
                        .text
                        ?.toString()
                        .orEmpty(),

                sshPort =
                    binding.inputSshPort
                        .text
                        ?.toString()
                        ?.toIntOrNull()
                        ?: 22,

                username =
                    binding.inputUsername
                        .text
                        ?.toString()
                        .orEmpty(),

                password =
                    binding.inputPassword
                        .text
                        ?.toString()
                        .orEmpty(),

                payloadEnabled =
                    binding.switchPayload.isChecked,

                payloadMode =
                    payloadMode,

                payload =
                    binding.inputPayload
                        .text
                        ?.toString()
                        .orEmpty(),

                proxyEnabled =
                    proxyEnabled,

                proxyHost =
                    proxyHost,

                proxyPort =
                    proxyPort,

                tlsEnabled =
                    binding.switchTls.isChecked,

                sni =
                    binding.inputSni
                        .text
                        ?.toString()
                        .orEmpty(),

                tlsVersion =
                    binding.inputTlsVersion
                        .text
                        ?.toString()
                        .orEmpty()
                        .ifBlank {
                            defaults.tlsVersion
                        },

                localSocksPort =
                    binding.inputSocksPort
                        .text
                        ?.toString()
                        ?.toIntOrNull()
                        ?: defaults.localSocksPort,

                keepAliveSeconds =
                    binding.inputKeepAlive
                        .text
                        ?.toString()
                        ?.toIntOrNull()
                        ?: defaults.keepAliveSeconds,

                socketSendBufferSize =
                    binding.inputSendBufferSize
                        .text
                        ?.toString()
                        ?.toIntOrNull()
                        ?: defaults.socketSendBufferSize,

                socketReceiveBufferSize =
                    binding.inputReceiveBufferSize
                        .text
                        ?.toString()
                        ?.toIntOrNull()
                        ?: defaults.socketReceiveBufferSize,

                tcpNoDelay =
                    binding.switchTcpNoDelay.isChecked,

                cpuWakeLockEnabled =
                    binding.switchCpuWakeLock.isChecked,

                connectTimeoutMillis =
                    previous?.connectTimeoutMillis
                        ?: defaults.connectTimeoutMillis,

                readTimeoutMillis =
                    previous?.readTimeoutMillis
                        ?: defaults.readTimeoutMillis,

                autoReconnect =
                    binding.switchAutoReconnect.isChecked,

                maxReconnect =
                    binding.inputMaxReconnect
                        .text
                        ?.toString()
                        ?.toIntOrNull()
                        ?: defaults.maxReconnect
            )

        runtime.profiles.save(
            profileId,
            profile
        )
    }

    private fun deleteEmptyDraftIfNeeded() {
        if (loading) {
            return
        }

        val profile =
            runtime.profiles.load(
                profileId
            ) ?: return

        val isEmpty =
            profile.name.isBlank() &&
                profile.sshHost.isBlank() &&
                profile.username.isBlank() &&
                profile.password.isBlank() &&
                !profile.payloadEnabled &&
                profile.payload.isBlank() &&
                !profile.proxyEnabled &&
                profile.proxyHost.isBlank() &&
                profile.proxyPort == 0 &&
                !profile.tlsEnabled &&
                profile.sni.isBlank()

        if (isEmpty) {
            runtime.profiles.delete(
                profileId
            )
        }
    }

    private fun flushPendingAutosave() {
        val pending = autosaveRunnable

        if (pending != null) {
            autosaveHandler.removeCallbacks(pending)
            autosaveRunnable = null

            if (!loading && _binding != null) {
                saveProfile()
            }
        }
    }

    override fun onDestroyView() {
        flushPendingAutosave()
        deleteEmptyDraftIfNeeded()
        autosaveHandler.removeCallbacksAndMessages(null)
        autosaveRunnable = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TEXT_AUTOSAVE_DEBOUNCE_MILLIS = 350L
        const val ARG_PROFILE_ID =
            "profile_id"
    }
}
