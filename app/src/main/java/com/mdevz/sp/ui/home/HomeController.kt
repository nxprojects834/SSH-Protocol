package com.mdevz.sp.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdevz.sp.R
import com.mdevz.sp.core.config.ProfileValidation
import com.mdevz.sp.core.config.ProfileUriCodec
import com.mdevz.sp.core.model.PayloadMode
import com.mdevz.sp.core.model.SshProfile
import com.mdevz.sp.core.model.TrafficStats
import com.mdevz.sp.core.model.TunnelState
import com.mdevz.sp.databinding.FragmentHomeBinding
import com.mdevz.sp.service.TunnelRuntime
import java.util.Locale
import java.util.UUID

class HomeController(
    private val context: Context,
    private val binding: FragmentHomeBinding,
    private val runtime: TunnelRuntime,
    private val onConnectRequested: (String) -> Unit,
    private val onStopRequested: () -> Unit
) {    private val profileConfigurationController =
        ProfileConfigurationController(
            context = context,
            profiles = runtime.profiles,
            onProfileSaved = {
                profileId ->
                currentProfileId =
                    profileId
                refreshProfiles(
                    preferredId =
                        profileId
                )
            }
        )

    var selectedProfileId:
        String? = null
        private set

    private var currentProfileId:
        String?
        get() =
            selectedProfileId
        set(value) {
            selectedProfileId =
                value
        }

    private var profileItems:
        List<ProfileItem> = emptyList()

    private var currentTunnelState =
        TunnelState.DISCONNECTED

    private var trafficSessionStartedAt =
        0L

    private var previousRxBytes =
        0L

    private var previousTxBytes =
        0L

    private var latestTraffic =
        TrafficStats()

    private var previousSampleAt =
        0L

    fun setup(
        preferredId: String
    ) {
        setupProfileSelector()
        setupActions()
        refreshProfiles(
            preferredId = preferredId
        )
    }

    private fun setupProfileSelector() {
        binding.profileCards.removeAllViews()
    }

    private fun setupActions() {
        binding.buttonAddProfile
            .setOnClickListener {
                if (isTunnelBusy()) {
                    return@setOnClickListener
                }

                showCreateProfileDialog()
            }

        binding.buttonConnect
            .setOnClickListener {
                if (isTunnelBusy()) {
                    onStopRequested()
                    return@setOnClickListener
                }

                val profileId =
                    currentProfileId
                        ?: run {
                            showValidationErrors(
                                context.getString(
                                    R.string.no_profile_selected
                                )
                            )
                            return@setOnClickListener
                        }

                val profile =
                    try {
                        runtime.profiles.load(
                            profileId
                        )
                    } catch (
                        failure: Throwable
                    ) {
                        AlertDialog.Builder(context)
                            .setTitle(
                                context.getString(
                                    R.string.unable_load_profile
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

                        return@setOnClickListener
                    }
                        ?: run {
                            showValidationErrors(
                                context.getString(
                                    R.string.no_profile_selected
                                )
                            )
                            return@setOnClickListener
                        }

                val errors =
                    ProfileValidation.validate(
                        profile
                    )

                if (errors.isNotEmpty()) {
                    showValidationErrors(
                        errors.joinToString("\n") {
                            "${it.field}: ${it.message}"
                        }
                    )
                    return@setOnClickListener
                }

                onConnectRequested(profileId)
            }

    }

    fun renderState(
        state: TunnelState
    ) {
        binding.textState.text =
            when (state) {
                TunnelState.CONNECTED ->
                    context.getString(R.string.state_connected)

                else ->
                    state.name
            }

        binding.textConnectionBoundary.text =
            when (state) {
                TunnelState.CONNECTED ->
                    context.getString(R.string.vpn_active_detail)

                TunnelState.STARTING_VPN ->
                    context.getString(R.string.vpn_starting)

                else ->
                    context.getString(R.string.vpn_not_active)
            }

        binding.statusIcon.setImageResource(
            if (
                state ==
                    TunnelState.CONNECTED
            ) {
                R.drawable.ic_lock_closed_24
            } else {
                R.drawable.ic_lock_open_24
            }
        )

        currentTunnelState =
            state

        val busy =
            isTunnelBusy()

        binding.buttonConnect.isEnabled =
            busy || currentProfileId != null

        binding.buttonConnect.text =
            context.getString(
                if (busy) {
                    R.string.action_stop
                } else {
                    R.string.action_connect
                }
            )

        binding.buttonAddProfile.isEnabled =
            !busy

        renderProfileCards()
    }

    private fun isTunnelBusy(): Boolean =
        currentTunnelState != TunnelState.DISCONNECTED &&
            currentTunnelState != TunnelState.ERROR

    fun renderTraffic(
        stats: TrafficStats
    ) {
        latestTraffic =
            stats

        val download =
            formatBytes(
                stats.rxBytes
            )

        val upload =
            formatBytes(
                stats.txBytes
            )

        binding.textTraffic.text =
            context.getString(
                R.string.traffic_format,
                download,
                upload
            )

        binding.textDownload.text =
            download

        binding.textUpload.text =
            upload

        if (stats.connectedAt <= 0L) {
            resetTrafficSession()
            return
        }

        if (
            trafficSessionStartedAt !=
                stats.connectedAt
        ) {
            trafficSessionStartedAt =
                stats.connectedAt

            previousRxBytes =
                stats.rxBytes

            previousTxBytes =
                stats.txBytes

            previousSampleAt =
                System.currentTimeMillis()

            binding.downloadChart.clearSamples()
            binding.uploadChart.clearSamples()
        }
    }

    fun renderRuntimeTick(
        nowMillis: Long
    ) {
        val stats =
            latestTraffic

        if (
            stats.connectedAt <= 0L
            || currentTunnelState !=
                TunnelState.CONNECTED
        ) {
            return
        }

        if (
            trafficSessionStartedAt !=
                stats.connectedAt
        ) {
            trafficSessionStartedAt =
                stats.connectedAt

            previousRxBytes =
                stats.rxBytes

            previousTxBytes =
                stats.txBytes

            previousSampleAt =
                nowMillis

            binding.downloadChart.clearSamples()
            binding.uploadChart.clearSamples()
        }

        val elapsedMillis =
            (
                nowMillis -
                    stats.connectedAt
            ).coerceAtLeast(0L)

        binding.textDuration.text =
            formatDuration(
                elapsedMillis
            )

        val sampleElapsed =
            (
                nowMillis -
                    previousSampleAt
            ).coerceAtLeast(1L)

        val rxDelta =
            (
                stats.rxBytes -
                    previousRxBytes
            ).coerceAtLeast(0L)

        val txDelta =
            (
                stats.txBytes -
                    previousTxBytes
            ).coerceAtLeast(0L)

        val rxPerSecond =
            (
                rxDelta.toDouble() *
                    1_000.0 /
                    sampleElapsed.toDouble()
            ).toLong()
                .coerceAtLeast(0L)

        val txPerSecond =
            (
                txDelta.toDouble() *
                    1_000.0 /
                    sampleElapsed.toDouble()
            ).toLong()
                .coerceAtLeast(0L)

        previousRxBytes =
            stats.rxBytes

        previousTxBytes =
            stats.txBytes

        previousSampleAt =
            nowMillis

        binding.downloadChart.addSample(
            rxPerSecond
        )

        binding.uploadChart.addSample(
            txPerSecond
        )
    }

    fun renderDeviceNetwork(
        address: String?,
        networkType: String?
    ) {
        binding.textVpnIp.text =
            address
                ?: context.getString(
                    R.string.home_ip_unavailable
                )

        binding.textNetworkType.text =
            networkType
                ?: context.getString(
                    R.string.home_ip_unavailable
                )
    }

    fun networkTypeWifi(): String =
        context.getString(
            R.string.home_network_wifi
        )

    fun networkTypeCellular(): String =
        context.getString(
            R.string.home_network_cellular
        )

    fun networkTypeEthernet(): String =
        context.getString(
            R.string.home_network_ethernet
        )

    fun networkTypeOther(): String =
        context.getString(
            R.string.home_network_other
        )

    private fun resetTrafficSession() {
        trafficSessionStartedAt =
            0L

        previousRxBytes =
            0L

        previousTxBytes =
            0L

        previousSampleAt =
            0L

        binding.textDuration.text =
            context.getString(
                R.string.home_duration_zero
            )

        binding.downloadChart.clearSamples()
        binding.uploadChart.clearSamples()
    }

    private fun refreshProfiles(
        preferredId: String? =
            currentProfileId
    ) {
        val ids =
            try {
                runtime.profiles.listIds()
            } catch (failure: Throwable) {
                AlertDialog.Builder(context)
                    .setTitle(
                        context.getString(R.string.unable_load_profiles)
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
                return
            }

        val items =
            ids.mapNotNull { id ->
                try {
                    runtime.profiles.load(id)
                        ?.let { profile ->
                            ProfileItem(
                                id = id,
                                name =
                                    profile.name.ifBlank {
                                        id
                                    },
                                sshHost =
                                    profile.sshHost
                            )
                        }
                } catch (failure: Throwable) {
                    null
                }
            }

        profileItems = items

        if (items.isEmpty()) {
            currentProfileId = null
            binding.textProtocol.text =
                context.getString(
                    R.string.home_ip_unavailable
                )
            renderProfileCards()
            return
        }

        val activeProfileId =
            context
                .getSharedPreferences(
                    ACTIVE_PROFILE_PREFERENCES,
                    Context.MODE_PRIVATE
                )
                .getString(
                    ACTIVE_PROFILE_KEY,
                    null
                )

        val selected =
            items.firstOrNull {
                it.id == activeProfileId
            } ?: items.firstOrNull {
                it.id == preferredId
            } ?: items.first()

        if (
            currentProfileId != selected.id
        ) {
            loadProfile(selected.id)
        } else {
            renderProfileCards()
        }
    }

    private fun renderProfileCards() {
        binding.profileCards.removeAllViews()
        binding.profileCards.isVisible = false
        binding.profileEmptyState.isVisible = false
        binding.buttonAddProfile.isVisible = false

        val busy = isTunnelBusy()

        binding.buttonConnect.isEnabled =
            busy || currentProfileId != null
    }

    private fun selectProfile(
        profileId: String
    ) {
        if (isTunnelBusy()) {
            return
        }

        if (
            profileId ==
            currentProfileId
        ) {
            return
        }

        loadProfile(
            profileId
        )
    }

    private fun resolveThemeColor(
        attribute: Int
    ): Int {
        val value =
            android.util.TypedValue()

        val resolved =
            context.theme.resolveAttribute(
                attribute,
                value,
                true
            )

        return if (resolved) {
            if (
                value.resourceId != 0
            ) {
                ContextCompat.getColor(
                    context,
                    value.resourceId
                )
            } else {
                value.data
            }
        } else {
            Color.TRANSPARENT
        }
    }

    private fun showCreateProfileDialog() {
        if (isTunnelBusy()) {
            return
        }

        val dialogBinding =
            com.mdevz.sp.databinding.DialogCreateProfileBinding.inflate(
                LayoutInflater.from(context)
            )

        val input =
            dialogBinding.inputProfileName

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setView(dialogBinding.root)
                .setNegativeButton(
                    context.getString(
                        R.string.action_cancel
                    ),
                    null
                )
                .setPositiveButton(
                    context.getString(
                        R.string.action_create
                    ),
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                val name =
                    input.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()

                if (name.isBlank()) {
                    dialogBinding.layoutProfileName.error =
                        context.getString(
                            R.string.profile_name_required
                        )
                    input.requestFocus()
                    return@setOnClickListener
                }

                dialogBinding.layoutProfileName.error =
                    null

                createDraftProfile(
                    name
                )

                dialog.dismiss()
            }

            input.requestFocus()
        }

        dialog.show()
    }

    private fun createDraftProfile(
        name: String
    ) {
        val id =
            UUID.randomUUID()
                .toString()

        val profile =
            SshProfile(
                name = name,
                sshHost = "",
                sshPort = 22,
                username = "",
                password = "",
                payloadEnabled = false,
                proxyEnabled = false,
                payloadMode = PayloadMode.NORMAL,
                payload = "",
                proxyHost = "",
                proxyPort = 0,
                tlsEnabled = false,
                sni = "",
                tlsVersion = "default",
                localSocksPort = 1080,
                keepAliveSeconds = 30,
                socketSendBufferSize = 16_384,
                socketReceiveBufferSize = 32_768,
                tcpNoDelay = true,
                cpuWakeLockEnabled = false,
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 0,
                autoReconnect = true,
                maxReconnect = 0
            )

        try {
            runtime.profiles.save(
                id,
                profile
            )

            refreshProfiles(
                preferredId = id
            )

            Toast.makeText(
                context,
                context.getString(
                    R.string.profile_created
                ),
                Toast.LENGTH_SHORT
            ).show()
        } catch (failure: Throwable) {
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

    private fun editProfile(
        profileId: String
    ) {
        if (isTunnelBusy()) {
            return
        }

        val profile =
            try {
                runtime.profiles.load(
                    profileId
                )
            } catch (
                failure: Throwable
            ) {
                AlertDialog.Builder(context)
                    .setTitle(
                        context.getString(
                            R.string.unable_load_profile
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

                return
            }
                ?: return

        profileConfigurationController.show(
            profileId = profileId,
            profile = profile
        )
    }

    private fun shareProfile(
        profileId: String
    ) {
        val profile =
            try {
                runtime.profiles.load(
                    profileId
                )
            } catch (
                failure: Throwable
            ) {
                showProfileTransferError(
                    context.getString(
                        R.string.unable_share_profile
                    ),
                    failure
                )
                return
            }
                ?: return

        try {
            val value =
                ProfileUriCodec.encode(
                    profile
                )

            val shareIntent =
                Intent(
                    Intent.ACTION_SEND
                ).apply {
                    type =
                        "application/octet-stream"

                    val exportDirectory =
                        File(
                            context.cacheDir,
                            "profile_exports"
                        ).apply {
                            mkdirs()
                        }

                    val safeName =
                        profile.name
                            .trim()
                            .replace(
                                Regex(
                                    "[^A-Za-z0-9._-]+"
                                ),
                                "_"
                            )
                            .trim('_')
                            .ifBlank {
                                "profile"
                            }

                    val exportFile =
                        File(
                            exportDirectory,
                            "$safeName.vp"
                        )

                    exportFile.writeText(
                        value,
                        Charsets.UTF_8
                    )

                    val exportUri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            exportFile
                        )

                    putExtra(
                        Intent.EXTRA_STREAM,
                        exportUri
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    context.getString(
                        R.string.share_profile
                    )
                )
            )
        } catch (
            failure: Throwable
        ) {
            showProfileTransferError(
                context.getString(
                    R.string.unable_share_profile
                ),
                failure
            )
        }
    }

    fun importProfileUri(
        value: String
    ): Boolean {
        if (isTunnelBusy()) {
            Toast.makeText(
                context,
                context.getString(
                    R.string.profile_import_busy
                ),
                Toast.LENGTH_SHORT
            ).show()

            return false
        }

        val profile =
            try {
                ProfileUriCodec.decode(
                    value
                )
            } catch (
                failure: Throwable
            ) {
                showProfileTransferError(
                    context.getString(
                        R.string.unable_import_profile
                    ),
                    failure
                )
                return false
            }

        val id =
            UUID.randomUUID()
                .toString()

        return try {
            runtime.profiles.save(
                id,
                profile
            )

            refreshProfiles(
                preferredId = id
            )

            Toast.makeText(
                context,
                context.getString(
                    R.string.profile_imported
                ),
                Toast.LENGTH_SHORT
            ).show()

            true
        } catch (
            failure: Throwable
        ) {
            runCatching {
                runtime.profiles.delete(
                    id
                )
            }

            showProfileTransferError(
                context.getString(
                    R.string.unable_import_profile
                ),
                failure
            )

            false
        }
    }

    private fun showProfileTransferError(
        title: String,
        failure: Throwable
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(
                failure.message
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: failure::class.java
                        .simpleName
            )
            .setPositiveButton(
                android.R.string.ok,
                null
            )
            .show()
    }

    private fun deleteProfile(
        profileId: String
    ) {
        if (isTunnelBusy()) {
            return
        }

        val name =
            profileItems.firstOrNull {
                it.id == profileId
            }?.name
                ?: context.getString(
                    R.string.profile_fallback_name
                )

        AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    R.string.delete_profile_title
                )
            )
            .setMessage(
                context.getString(
                    R.string.delete_profile_message,
                    name
                )
            )
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .setPositiveButton(
                context.getString(
                    R.string.action_delete
                )
            ) { _, _ ->
                try {
                    runtime.profiles.delete(
                        profileId
                    )

                    if (
                        currentProfileId ==
                        profileId
                    ) {
                        currentProfileId =
                            null
                    }

                    refreshProfiles()

                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.profile_deleted
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (
                    failure: Throwable
                ) {
                    AlertDialog.Builder(context)
                        .setTitle(
                            context.getString(
                                R.string.unable_delete_profile
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
            .show()
    }

    private fun loadProfile(
        profileId: String
    ) {
        val profile =
            try {
                runtime.profiles.load(
                    profileId
                )
            } catch (
                failure: Throwable
            ) {
                AlertDialog.Builder(context)
                    .setTitle(
                        context.getString(
                            R.string.unable_load_profile
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

                return
            }
                ?: return

        currentProfileId =
            profileId

        binding.textProtocol.text =
            context.getString(
                R.string.home_protocol_ssh
            )

        renderProfileCards()
    }

    private fun showValidationErrors(
        message: String
    ) {
        AlertDialog.Builder(context)
            .setTitle(
                context.getString(R.string.invalid_profile)
            )
            .setMessage(message)
            .setPositiveButton(
                android.R.string.ok,
                null
            )
            .show()
    }

    private fun formatDuration(
        elapsedMillis: Long
    ): String {
        val totalSeconds =
            elapsedMillis / 1_000L

        val hours =
            totalSeconds / 3_600L

        val minutes =
            (
                totalSeconds %
                    3_600L
            ) / 60L

        val seconds =
            totalSeconds % 60L

        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun formatBytes(
        bytes: Long
    ): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }

        val kib =
            bytes / 1024.0

        if (kib < 1024.0) {
            return String.format(
                Locale.US,
                "%.1f KiB",
                kib
            )
        }

        val mib =
            kib / 1024.0

        if (mib < 1024.0) {
            return String.format(
                Locale.US,
                "%.1f MiB",
                mib
            )
        }

        return String.format(
            Locale.US,
            "%.2f GiB",
            mib / 1024.0
        )
    }

    private data class ProfileItem(
        val id: String,
        val name: String,
        val sshHost: String
    )

    private companion object {
        const val ACTIVE_PROFILE_PREFERENCES =
            "profile_ui_preferences"

        const val ACTIVE_PROFILE_KEY =
            "active_profile_id"
    }
}
