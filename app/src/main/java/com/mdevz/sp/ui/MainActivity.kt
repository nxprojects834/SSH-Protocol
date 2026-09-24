package com.mdevz.sp.ui

import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import com.mdevz.sp.SSHProtocolApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdevz.sp.R
import com.mdevz.sp.core.config.ProfileUriCodec
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mdevz.sp.ui.home.HomeFragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdevz.sp.databinding.ActivityMainBinding
import com.mdevz.sp.service.TunnelRuntime
import com.mdevz.sp.service.TunnelService
import com.mdevz.sp.ssh.HostKeyMismatchException
import com.mdevz.sp.ssh.UnknownHostKeyException
import com.mdevz.sp.tunnel.TunnelFailure
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.IdentityHashMap

class MainActivity : AppCompatActivity(), HomeFragment.Host {

    private lateinit var binding:
        ActivityMainBinding

    private lateinit var runtime:
        TunnelRuntime

    private var pendingProfileId:
        String? = null

    private var lastRequestedProfileId:
        String? = null

    private var lastPresentedFailure:
        Throwable? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            result ->
            val profileId =
                pendingProfileId

            pendingProfileId = null

            if (
                result.resultCode ==
                    RESULT_OK
            ) {
                if (profileId != null) {
                    startTunnelService(
                        profileId
                    )
                }
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.vpn_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        DynamicColors.applyToActivityIfAvailable(
            this
        )

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val navController =
                        mainNavController()

                    if (
                        navController.currentDestination?.id ==
                            R.id.profileEditorFragment ||
                        navController.currentDestination?.id ==
                            R.id.aboutFragment
                    ) {
                        navController.navigateUp()
                        return
                    }

                    if (navController.popBackStack()) {
                        return
                    }

                    showExitConfirmation()
                }
            }
        )

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)
        setupEdgeToEdge()
        syncSystemBarAppearance()
        runtime =
            TunnelRuntime.get(this)

        setupBottomNavigation(
            savedInstanceState?.getInt(
                STATE_SELECTED_DESTINATION,
                R.id.menu_home
            ) ?: R.id.menu_home
        )

        observeRuntime()
        showPendingCrashReport()
        acceptProfileIntent(
            intent
        )
    }

    private fun showPendingCrashReport() {
        val report =
            SSHProtocolApplication
                .consumeCrashReport(this)
                ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    R.string.crash_report_title
                )
            )
            .setMessage(report)
            .setNegativeButton(
                R.string.action_close,
                null
            )
            .setPositiveButton(
                R.string.action_copy
            ) { _, _ ->
                val clipboard =
                    getSystemService(
                        ClipboardManager::class.java
                    )

                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        getString(
                            R.string.crash_report_clip_label
                        ),
                        report
                    )
                )

                Toast.makeText(
                    this,
                    getString(
                        R.string.crash_report_copied
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.rootContainer
        ) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type
                        .systemBars()
                )

            val cutout =
                insets.getInsets(
                    WindowInsetsCompat.Type
                        .displayCutout()
                )

            view.setPadding(
                maxOf(
                    bars.left,
                    cutout.left
                ),
                maxOf(
                    bars.top,
                    cutout.top
                ),
                maxOf(
                    bars.right,
                    cutout.right
                ),
                0
            )

            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.bottomNavigation
        ) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type
                        .navigationBars()
                )

            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                bars.bottom
            )

            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.fragmentContainer
        ) { view, insets ->
            val ime =
                insets.getInsets(
                    WindowInsetsCompat.Type
                        .ime()
                )

            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                ime.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(
            binding.rootContainer
        )
    }

    private fun syncSystemBarAppearance() {
        val nightMode =
            resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK

        val lightTheme =
            nightMode !=
                Configuration.UI_MODE_NIGHT_YES

        val controller =
            WindowInsetsControllerCompat(
                window,
                window.decorView
            )

        controller.isAppearanceLightStatusBars =
            lightTheme
        controller.isAppearanceLightNavigationBars =
            lightTheme
    }

    private fun setupBottomNavigation(
        selectedDestination: Int
    ) {
        val navHost =
            supportFragmentManager
                .findFragmentById(
                    R.id.fragmentContainer
                ) as NavHostFragment

        val navController =
            navHost.navController

        binding.bottomNavigation
            .setupWithNavController(
                navController
            )

        val destination =
            when (selectedDestination) {
                R.id.menu_home,
                R.id.menu_profiles,
                R.id.menu_logs,
                R.id.menu_settings ->
                    selectedDestination

                else ->
                    R.id.menu_home
            }

        if (
            navController.currentDestination
                ?.id != destination
        ) {
            navController.navigate(
                destination
            )
        }
    }

    private fun mainNavController(): NavController {
        val navHost =
            supportFragmentManager
                .findFragmentById(
                    R.id.fragmentContainer
                ) as NavHostFragment

        return navHost.navController
    }

    override fun onSaveInstanceState(
        outState: Bundle
    ) {
        outState.putInt(
            STATE_SELECTED_DESTINATION,
            binding.bottomNavigation.selectedItemId
        )
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(
            intent
        )

        setIntent(
            intent
        )

        acceptProfileIntent(
            intent
        )
    }


    private fun readExternalProfile(
        uri: Uri
    ): String? =
        runCatching {
            contentResolver
                .openInputStream(uri)
                ?.bufferedReader(
                    Charsets.UTF_8
                )
                ?.use {
                    reader ->
                    reader.readText()
                }
                ?.trim()
                ?.takeIf {
                    value ->
                    ProfileUriCodec.isProfileUri(
                        value
                    )
                }
        }.getOrNull()

    private fun acceptProfileIntent(
        intent: Intent
    ) {
        if (intent.action != Intent.ACTION_VIEW) {
            return
        }

        val data =
            intent.data
                ?: return

        val value =
            when (data.scheme) {
                "content",
                "file" ->
                    readExternalProfile(
                        data
                    )

                else ->
                    intent.dataString
            } ?: return

        if (!ProfileUriCodec.isProfileUri(value)) {
            return
        }

        binding.bottomNavigation.selectedItemId =
            R.id.menu_home

        val navController =
            mainNavController()

        if (
            navController.currentDestination
                ?.id != R.id.menu_home
        ) {
            navController.navigate(
                R.id.menu_home
            )
        }

        supportFragmentManager
            .executePendingTransactions()

        val navHost =
            supportFragmentManager
                .findFragmentById(
                    R.id.fragmentContainer
                ) as? NavHostFragment

        val home =
            navHost
                ?.childFragmentManager
                ?.primaryNavigationFragment
                as? HomeFragment

        home?.importProfileUri(
            value
        )
    }

    private fun observeRuntime() {
        lifecycleScope.launch {
            repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                runtime.engine.failure.collect {
                    renderFailure(it)
                }
            }
        }
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(
                getString(
                    R.string.exit_confirmation_message
                )
            )
            .setNegativeButton(
                R.string.action_cancel,
                null
            )
            .setPositiveButton(
                R.string.action_exit
            ) { _, _ ->
                finish()
            }
            .show()
    }

    private fun renderFailure(
        failure: TunnelFailure?
    ) {
        if (failure == null) {
            lastPresentedFailure = null
            return
        }

        val presentedCause =
            findHostKeyFailure(
                failure.cause
            ) ?: failure.cause

        if (
            presentedCause ===
                lastPresentedFailure
        ) {
            return
        }

        lastPresentedFailure =
            presentedCause

        when (presentedCause) {
            is HostKeyMismatchException ->
                showHostKeyMismatch(
                    presentedCause
                )

            is UnknownHostKeyException ->
                showUnknownHostKey(
                    presentedCause
                )

            else -> Unit
        }
    }

    private fun findHostKeyFailure(
        root: Throwable?
    ): Throwable? {
        if (root == null) {
            return null
        }

        val visited =
            IdentityHashMap<Throwable, Boolean>()

        val pending =
            ArrayDeque<Throwable>()

        var unknown:
            UnknownHostKeyException? = null

        pending.addLast(root)

        while (
            pending.isNotEmpty() &&
            visited.size <
                MAX_THROWABLE_GRAPH_NODES
        ) {
            val current =
                pending.removeFirst()

            if (
                visited.put(
                    current,
                    true
                ) != null
            ) {
                continue
            }

            when (current) {
                is HostKeyMismatchException ->
                    return current

                is UnknownHostKeyException ->
                    if (unknown == null) {
                        unknown = current
                    }
            }

            current.cause
                ?.let {
                    pending.addLast(it)
                }

            current.suppressed
                .forEach {
                    suppressed ->
                    pending.addLast(
                        suppressed
                    )
                }
        }

        return unknown
    }

    private fun showUnknownHostKey(
        failure: UnknownHostKeyException
    ) {
        val record =
            failure.record

        AlertDialog.Builder(this)
            .setTitle(
                getString(R.string.unknown_ssh_host_key)
            )
            .setMessage(
                getString(
                    R.string.host_key_unknown_message,
                    record.host,
                    record.port,
                    record.algorithm,
                    record.fingerprint
                )
            )
            .setNegativeButton(
                getString(R.string.action_reject),
                null
            )
            .setPositiveButton(
                getString(R.string.action_trust_reconnect)
            ) {
                _, _ ->

                try {
                   
                    runtime.knownHosts.trust(
                        record
                    )
                } catch (
                    persistenceFailure: Throwable
                ) {
                    AlertDialog.Builder(this)
                        .setTitle(
                            getString(R.string.unable_trust_host_key)
                        )
                        .setMessage(
                            persistenceFailure.message
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: getString(R.string.known_host_persistence_failed)
                        )
                        .setPositiveButton(
                            android.R.string.ok,
                            null
                        )
                        .show()

                    return@setPositiveButton
                }

                lastPresentedFailure = null

                lastRequestedProfileId
                    ?.let {
                        profileId ->
                        requestTunnelStart(
                            profileId
                        )
                    }
            }
            .show()
    }

    private fun showHostKeyMismatch(
        failure: HostKeyMismatchException
    ) {
        AlertDialog.Builder(this)
            .setTitle(
                getString(R.string.ssh_host_key_mismatch)
            )
            .setMessage(
                getString(
                    R.string.host_key_mismatch_message,
                    failure.received.host,
                    failure.received.port,
                    failure.expected.fingerprint,
                    failure.received.fingerprint
                )
            )
            .setPositiveButton(
                android.R.string.ok,
                null
            )
            .show()
    }

    override fun requestTunnelStart(
        profileId: String
    ) {
        lastRequestedProfileId =
            profileId

        val permissionIntent =
            VpnService.prepare(this)

        if (permissionIntent != null) {
            pendingProfileId =
                profileId

            vpnPermissionLauncher.launch(
                permissionIntent
            )
            return
        }

        pendingProfileId = null

        startTunnelService(
            profileId
        )
    }

    private fun startTunnelService(
        profileId: String
    ) {
        val intent =
            Intent(
                this,
                TunnelService::class.java
            ).apply {
                action =
                    TunnelService.ACTION_CONNECT

                putExtra(
                    TunnelService.EXTRA_PROFILE_ID,
                    profileId
                )
            }

        ContextCompat.startForegroundService(
            this,
            intent
        )
    }

    override fun requestTunnelStop() {
        val intent =
            Intent(
                this,
                TunnelService::class.java
            ).apply {
                action =
                    TunnelService.ACTION_STOP
            }

        startService(intent)
    }

    companion object {



        private const val STATE_SELECTED_DESTINATION =
            "selected_destination"
        

        private const val MAX_THROWABLE_GRAPH_NODES =
            128

        private const val TAG_HOME =
            "home"

        private const val TAG_LOGS =
            "logs"

        private const val TAG_SETTINGS =
            "settings"


    }
}
