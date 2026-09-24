package com.mdevz.sp.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.mdevz.sp.R
import com.mdevz.sp.core.config.ProfileValidation
import com.mdevz.sp.core.model.PayloadMode
import com.mdevz.sp.core.model.SshProfile
import com.mdevz.sp.databinding.DialogProfileConfigurationBinding

class ProfileConfigurationController(
    private val context: Context,
    private val profiles: com.mdevz.sp.core.config.ProfileRepository,
    private val onProfileSaved: (String) -> Unit
) {
    fun show(
        profileId: String,
        profile: SshProfile
    ) {
        val dialogBinding =
            DialogProfileConfigurationBinding.inflate(
                LayoutInflater.from(context)
            )

        fun updatePayloadVisibility() {
            val enabled =
                dialogBinding.switchPayload
                    .isChecked

            dialogBinding.groupPayloadMode
                .isVisible =
                enabled

            dialogBinding.layoutPayload
                .isVisible =
                enabled

            dialogBinding.layoutProxyHost
                .isVisible =
                enabled

            dialogBinding.layoutProxyPort
                .isVisible =
                enabled
        }

        fun updateTlsVisibility() {
            val enabled =
                dialogBinding.switchTls
                    .isChecked

            dialogBinding.layoutSni.isVisible =
                enabled

            dialogBinding.layoutTlsVersion.isVisible =
                enabled
        }

        dialogBinding.inputName.setText(
            profile.name
        )

        dialogBinding.inputSshHost.setText(
            profile.sshHost
        )

        dialogBinding.inputSshPort.setText(
            profile.sshPort.toString()
        )

        dialogBinding.inputUsername.setText(
            profile.username
        )

        dialogBinding.inputPassword.setText(
            profile.password
        )

        dialogBinding.switchPayload.isChecked =
            profile.payloadEnabled

        dialogBinding.radioNormal.isChecked =
            profile.payloadMode ==
            PayloadMode.NORMAL

        dialogBinding.radioEnhanced.isChecked =
            profile.payloadMode ==
            PayloadMode.ENHANCED

        dialogBinding.inputPayload.setText(
            profile.payload
        )

        dialogBinding.inputProxyHost.setText(
            profile.proxyHost
        )

        dialogBinding.inputProxyPort.setText(
            if (profile.proxyPort > 0) {
                profile.proxyPort.toString()
            } else {
                ""
            }
        )

        dialogBinding.switchTls.isChecked =
            profile.tlsEnabled

        dialogBinding.inputSni.setText(
            profile.sni
        )

        val tlsVersions =
            context.resources.getStringArray(
                R.array.tls_version_values
            )

        val tlsVersionLabels =
            context.resources.getStringArray(
                R.array.tls_version_labels
            )

        val tlsVersionIndex =
            tlsVersions.indexOf(
                profile.tlsVersion
            ).takeIf {
                it >= 0
            } ?: 0

        dialogBinding.inputTlsVersion.setText(
            tlsVersionLabels[
                tlsVersionIndex
            ],
            false
        )

        dialogBinding.inputTlsVersion.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                tlsVersionLabels
            )
        )

        dialogBinding.inputSocksPort.setText(
            profile.localSocksPort.toString()
        )

        dialogBinding.inputKeepAlive.setText(
            profile.keepAliveSeconds.toString()
        )

        dialogBinding.inputSendBufferSize.setText(
            profile.socketSendBufferSize.toString()
        )

        dialogBinding.inputReceiveBufferSize.setText(
            profile.socketReceiveBufferSize.toString()
        )

        dialogBinding.switchTcpNoDelay.isChecked =
            profile.tcpNoDelay

        dialogBinding.switchCpuWakeLock.isChecked =
            profile.cpuWakeLockEnabled

        dialogBinding.switchAutoReconnect
            .isChecked =
            profile.autoReconnect

        dialogBinding.inputMaxReconnect.setText(
            profile.maxReconnect.toString()
        )

        val updateMaxReconnectState = {
            dialogBinding.inputMaxReconnect.isEnabled =
                dialogBinding.switchAutoReconnect.isChecked
        }

        dialogBinding.switchAutoReconnect
            .setOnCheckedChangeListener {
                _, _ ->
                updateMaxReconnectState()
            }

        updateMaxReconnectState()

        dialogBinding.switchPayload
            .setOnCheckedChangeListener {
                _, _ ->
                updatePayloadVisibility()
            }

        dialogBinding.switchTls
            .setOnCheckedChangeListener {
                _, _ ->
                updateTlsVisibility()
            }

        updatePayloadVisibility()
        updateTlsVisibility()

        dialogBinding.textDialogProfileName.text =
            profile.name

        val dialog =
            AlertDialog.Builder(context)
                .setView(
                    dialogBinding.root
                )
                .create()

        dialog.setOnShowListener {
            dialogBinding.buttonCancel
                .setOnClickListener {
                    dialog.dismiss()
                }

            dialogBinding.buttonSave
                .setOnClickListener {
                    val updated =
                        try {
                            profileFromDialog(
                                    existingProfile =
                                        profile,
                                dialogBinding =
                                    dialogBinding
                            )
                        } catch (
                            failure:
                                IllegalArgumentException
                        ) {
                            showValidationErrors(
                                failure.message
                                    ?: context.getString(
                                        R.string.invalid_profile
                                    )
                            )
                            return@setOnClickListener
                        }

                    val errors =
                        ProfileValidation.validate(
                            updated
                        )

                    if (errors.isNotEmpty()) {
                        showValidationErrors(
                            errors.joinToString(
                                "\n"
                            ) {
                                "${it.field}: ${it.message}"
                            }
                        )
                        return@setOnClickListener
                    }

                    try {
                        profiles.save(
                            profileId,
                            updated
                        )

                        onProfileSaved(
                            profileId
                        )

                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.profile_saved
                            ),
                            Toast.LENGTH_SHORT
                        ).show()

                        dialog.dismiss()
                    } catch (
                        failure: Throwable
                    ) {
                        AlertDialog.Builder(context)
                            .setTitle(
                                context.getString(
                                    R.string.unable_save_profile
                                )
                            )
                            .setMessage(
                                failure.message
                                    ?: failure::class.java
                                        .simpleName
                            )
                            .setPositiveButton(
                                android.R.string.ok,
                                null
                            )
                            .show()
                    }
                }
        }

        dialog.show()
    }

    private fun profileFromDialog(
        existingProfile: SshProfile,
        dialogBinding:
            DialogProfileConfigurationBinding
    ): SshProfile {
        fun text(
            value: CharSequence?
        ): String =
            value?.toString()
                ?.trim()
                .orEmpty()

        fun requiredInt(
            value: CharSequence?,
            field: String
        ): Int {
            val raw =
                text(value)

            return raw.toIntOrNull()
                ?: throw IllegalArgumentException(
                    context.getString(
                        R.string.field_must_be_number,
                        field
                    )
                )
        }

        val payloadEnabled =
            dialogBinding.switchPayload
                .isChecked

        val tlsEnabled =
            dialogBinding.switchTls
                .isChecked

        val selectedTlsValues =
            context.resources.getStringArray(
                R.array.tls_version_values
            )

        val selectedTlsLabels =
            context.resources.getStringArray(
                R.array.tls_version_labels
            )

        val selectedTlsText =
            dialogBinding.inputTlsVersion.text
                ?.toString()
                .orEmpty()

        val selectedTlsIndex =
            selectedTlsLabels.indexOf(
                selectedTlsText
            )

        val tlsVersion =
            selectedTlsValues.getOrNull(
                selectedTlsIndex
            ) ?: "default"

        return SshProfile(
            name =
                text(
                    dialogBinding.inputName.text
                ),
            sshHost =
                text(
                    dialogBinding.inputSshHost.text
                ),
            sshPort =
                requiredInt(
                    dialogBinding.inputSshPort.text,
                    context.getString(
                        R.string.ssh_port
                    )
                ),
            username =
                text(
                    dialogBinding.inputUsername.text
                ),
            password =
                dialogBinding.inputPassword.text
                    ?.toString()
                    .orEmpty(),
            proxyEnabled =
                existingProfile.proxyEnabled,
            payloadEnabled =
                payloadEnabled,
            payloadMode =
                if (
                    dialogBinding.radioEnhanced
                        .isChecked
                ) {
                    PayloadMode.ENHANCED
                } else {
                    PayloadMode.NORMAL
                },
            payload =
                dialogBinding.inputPayload.text
                    ?.toString()
                    .orEmpty(),
            proxyHost =
                if (payloadEnabled) {
                    text(
                        dialogBinding.inputProxyHost.text
                    )
                } else {
                    existingProfile.proxyHost
                },
            proxyPort =
                if (payloadEnabled) {
                    requiredInt(
                        dialogBinding.inputProxyPort.text,
                        context.getString(
                            R.string.field_proxy_port
                        )
                    )
                } else {
                    existingProfile.proxyPort
                },
            tlsEnabled =
                tlsEnabled,
            sni =
                text(
                    dialogBinding.inputSni.text
                ),
            tlsVersion =
                tlsVersion,
            localSocksPort =
                requiredInt(
                    dialogBinding.inputSocksPort.text,
                    context.getString(
                        R.string.field_socks_port
                    )
                ),
            keepAliveSeconds =
                requiredInt(
                    dialogBinding.inputKeepAlive.text,
                    context.getString(
                        R.string.field_keepalive
                    )
                ),
            socketSendBufferSize =
                requiredInt(
                    dialogBinding.inputSendBufferSize.text,
                    context.getString(
                        R.string.socket_send_buffer_size
                    )
                ),
            socketReceiveBufferSize =
                requiredInt(
                    dialogBinding.inputReceiveBufferSize.text,
                    context.getString(
                        R.string.socket_receive_buffer_size
                    )
                ),
            tcpNoDelay =
                dialogBinding.switchTcpNoDelay
                    .isChecked,
            cpuWakeLockEnabled =
                dialogBinding.switchCpuWakeLock
                    .isChecked,
            connectTimeoutMillis =
                15_000,
            readTimeoutMillis =
                0,
            autoReconnect =
                dialogBinding.switchAutoReconnect
                    .isChecked,
            maxReconnect =
                requiredInt(
                    dialogBinding.inputMaxReconnect.text,
                    context.getString(
                        R.string.max_reconnect
                    )
                )
        )
    }

    private fun showValidationErrors(
        message: String
    ) {
        AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    R.string.invalid_profile
                )
            )
            .setMessage(message)
            .setPositiveButton(
                android.R.string.ok,
                null
            )
            .show()
    }
}
