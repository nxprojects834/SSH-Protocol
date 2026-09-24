package com.mdevz.sp.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mdevz.sp.core.config.ProfileValidation
import com.mdevz.sp.core.model.TunnelState
import com.mdevz.sp.tunnel.ReconnectDecision
import com.mdevz.sp.tunnel.TunnelFailureClassifier
import com.mdevz.sp.vpn.TunnelVpnService
import com.mdevz.sp.vpn.VpnProtectBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class TunnelService : Service() {

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private lateinit var runtime:
        TunnelRuntime

    private lateinit var notification:
        TunnelNotification

    private var stateJob: Job? = null

    private var sessionJob: Job? = null

    private var sessionGeneration:
        Long = NO_VPN_GENERATION

    private var cleanupJob: Job? = null

    private var cpuWakeLock:
        PowerManager.WakeLock? = null

    private val commandReceived =
        AtomicBoolean(false)

    private val manualStopInProgress =
        AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()

        runtime =
            TunnelRuntime.get(this)

        notification =
            TunnelNotification(this)

        notification.ensureChannel()

        startForeground(
            TunnelNotification.NOTIFICATION_ID,
            notification.build(
                runtime.engine.state.value
            )
        )

        stateJob = serviceScope.launch {
            runtime.engine.state.collect {
                updateNotification(it)

            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                commandReceived.set(true)

                if (
                    cleanupJob?.isActive == true ||
                    manualStopInProgress.get()
                ) {
                    runtime.logger.error(
                        "Connect rejected: tunnel cleanup still in progress"
                    )

                    return START_NOT_STICKY
                }

                val profileId =
                    intent.getStringExtra(
                        EXTRA_PROFILE_ID
                    )

                if (profileId.isNullOrBlank()) {
                    runtime.logger.error(
                        "Connect rejected: missing profile id"
                    )

                    finishPreAttempt(
                        startId = startId,
                        reason =
                            "missing profile id"
                    )

                    return START_NOT_STICKY
                }

                startProfile(
                    profileId = profileId,
                    startId = startId
                )
            }

            ACTION_STOP -> {
                commandReceived.set(true)
                manualStopInProgress.set(true)

                if (
                    cleanupJob?.isActive == true
                ) {
                    runtime.logger.info(
                        "Stop already in progress"
                    )

                    return START_NOT_STICKY
                }

                val stoppedSession =
                    sessionJob

                val stoppedGeneration =
                    sessionGeneration

                stoppedSession?.cancel()

                val owner =
                    serviceScope.launch(
                        start = CoroutineStart.LAZY
                    ) {
                        val cleanupOwner =
                            currentCoroutineContext()[Job]

                        var stopFailure:
                            Throwable? = null

                        try {
                            try {
                                runtime.engine.disconnect()
                        } catch (
                            cancelled: CancellationException
                        ) {
                            throw cancelled
                        } catch (
                            failure: Throwable
                        ) {

                            stopFailure =
                                failure
                        }

                        if (
                            stoppedGeneration >
                                NO_VPN_GENERATION
                        ) {
                            try {
                                when (
                                    val cleanup =
                                        runtime.vpnProtectBridge
                                            .prepareGenerationCleanup(
                                                stoppedGeneration
                                            )
                                ) {
                                    VpnProtectBridge
                                        .GenerationCleanupDisposition
                                        .RevokedUnowned -> {

                                    }

                                    is VpnProtectBridge
                                        .GenerationCleanupDisposition
                                        .Attached -> {
                                        cleanup.controller
                                            .shutdown()

                                        runtime.vpnProtectBridge
                                            .cancelGeneration(
                                                stoppedGeneration
                                            )
                                    }

                                    VpnProtectBridge
                                        .GenerationCleanupDisposition
                                        .RetainedUnverifiable -> {
                                        error(
                                            "VPN generation remained owned during manual stop"
                                        )
                                    }
                                }
                            } catch (
                                cancelled: CancellationException
                            ) {
                                throw cancelled
                            } catch (
                                failure: Throwable
                            ) {
                                val primary =
                                    stopFailure

                                if (primary == null) {
                                    stopFailure =
                                        failure

                                    runtime.logger.error(
                                        "VPN service shutdown failed",
                                        failure
                                    )
                                } else if (
                                    primary !== failure
                                ) {
                                    primary.addSuppressed(
                                        failure
                                    )

                                    runtime.logger.error(
                                        "VPN service shutdown also failed",
                                        failure
                                    )
                                }
                            }
                        }
                    } finally {

                            if (
                                sessionJob === stoppedSession
                            ) {
                                sessionJob = null

                                if (
                                    sessionGeneration ==
                                        stoppedGeneration
                                ) {
                                    sessionGeneration =
                                        NO_VPN_GENERATION
                                }
                            }

                            manualStopInProgress.set(false)

                            if (
                                cleanupJob === cleanupOwner
                            ) {
                                cleanupJob = null
                            }

                            releaseCpuWakeLock()

                            stopSelf(startId)
                        }
                    }

                cleanupJob =
                    owner

                owner.start()
            }

            else -> {
                runtime.logger.error(
                    "Service started without a valid action"
                )

                finishPreAttempt(
                    startId = startId,
                    reason =
                        "invalid service action"
                )
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        sessionJob = null
        sessionGeneration =
            NO_VPN_GENERATION

        cleanupJob?.cancel()
        cleanupJob = null

        stateJob?.cancel()
        stateJob = null

        releaseCpuWakeLock()

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    private fun finishPreAttempt(
        startId: Int,
        reason: String
    ) {
        if (
            cleanupJob?.isActive == true
        ) {
            runtime.logger.info(
                "Pre-attempt cleanup already in progress: $reason"
            )

            return
        }

        val owner =
            serviceScope.launch(
                start = CoroutineStart.LAZY
            ) {
                val cleanupOwner =
                    currentCoroutineContext()[Job]

                try {

                    runtime.logger.info(
                        "Pre-attempt cleanup completed: $reason"
                    )
                } finally {
                    if (
                        cleanupJob === cleanupOwner
                    ) {
                        cleanupJob = null
                    }

                    stopSelf(startId)
                }
            }

        cleanupJob =
            owner

        owner.start()
    }

    private fun startProfile(
        profileId: String,
        startId: Int
    ) {
        val profile =
            try {
                runtime.profiles.load(
                    profileId
                )
            } catch (failure: Throwable) {
                runtime.logger.error(
                    "Unable to load profile",
                    failure
                )

                finishPreAttempt(
                    startId = startId,
                    reason =
                        "profile repository load failure"
                )

                return
            }

        if (profile == null) {
            runtime.logger.error(
                "Connect rejected: profile not found"
            )

            finishPreAttempt(
                startId = startId,
                reason =
                    "profile not found"
            )

            return
        }

        val validation =
            ProfileValidation.validate(
                profile
            )

        if (validation.isNotEmpty()) {
            runtime.logger.error(
                "Connect rejected: invalid profile"
            )

            finishPreAttempt(
                startId = startId,
                reason =
                    "profile validation failure"
            )

            return
        }

        if (
            sessionJob?.isActive == true
        ) {
            runtime.logger.error(
                "Connect rejected: tunnel session already active"
            )
            return
        }

        try {
            when (
                val recovery =
                    runtime.vpnProtectBridge
                        .prepareAdmissionRecovery()
            ) {
                VpnProtectBridge
                    .AdmissionRecoveryDisposition
                    .Ready -> {

                }

                is VpnProtectBridge
                    .AdmissionRecoveryDisposition
                    .Attached -> {

                    val recoveryOwner =
                        serviceScope.launch(
                            start =
                                CoroutineStart.LAZY
                        ) {
                            val ownerJob =
                                currentCoroutineContext()[
                                    Job
                                ]

                            var recovered =
                                false

                            try {

                                runtime.engine
                                    .disconnect()

                                recovery.controller
                                    .shutdown()

                                runtime.vpnProtectBridge
                                    .cancelGeneration(
                                        recovery.generation
                                    )

                                recovered =
                                    true
                            } catch (
                                cancelled:
                                    CancellationException
                            ) {
                                throw cancelled
                            } catch (
                                failure: Throwable
                            ) {
                                runtime.logger.error(
                                    "Connect rejected: retained tunnel recovery failed",
                                    failure
                                )
                            } finally {

                                if (
                                    cleanupJob ===
                                        ownerJob
                                ) {
                                    cleanupJob =
                                        null
                                }
                            }

                            if (recovered) {

                                startProfile(
                                    profileId =
                                        profileId,
                                    startId =
                                        startId
                                )
                            } else {
                                finishPreAttempt(
                                    startId =
                                        startId,
                                    reason =
                                        "retained tunnel recovery failure"
                                )
                            }
                        }

                    cleanupJob =
                        recoveryOwner

                    recoveryOwner.start()

                    return
                }

                VpnProtectBridge
                    .AdmissionRecoveryDisposition
                    .RetainedUnverifiable -> {
                    error(
                        "Previous VPN generation remains unverifiable"
                    )
                }
            }
        } catch (failure: Throwable) {

            runtime.logger.error(
                "Connect rejected: retained VPN ownership recovery failed",
                failure
            )

            finishPreAttempt(
                startId = startId,
                reason =
                    "retained VPN ownership recovery failure"
            )

            return
        }

        val vpnGeneration =
            try {
                runtime.vpnProtectBridge
                    .reserveGeneration()
            } catch (failure: Throwable) {

                runtime.logger.error(
                    "Connect rejected: VPN generation reservation failed",
                    failure
                )

                finishPreAttempt(
                    startId = startId,
                    reason =
                        "VPN generation reservation failure"
                )

                return
            }

        if (profile.cpuWakeLockEnabled) {
            try {
                acquireCpuWakeLock()
            } catch (failure: Throwable) {
                runtime.vpnProtectBridge
                    .cancelGeneration(
                        vpnGeneration
                    )

                runtime.logger.error(
                    "Connect rejected: unable to acquire CPU wake lock",
                    failure
                )

                finishPreAttempt(
                    startId = startId,
                    reason =
                        "CPU wake lock acquisition failure"
                )

                return
            }
        } else {
            releaseCpuWakeLock()
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(
                    this,
                    TunnelVpnService::class.java
                ).apply {
                    putExtra(
                        TunnelVpnService.EXTRA_VPN_GENERATION,
                        vpnGeneration
                    )
                }
            )
        } catch (failure: Throwable) {

            releaseCpuWakeLock()

            runtime.vpnProtectBridge
                .cancelGeneration(
                    vpnGeneration
                )

            runtime.logger.error(
                "Unable to start VPN protection boundary",
                failure
            )

            finishPreAttempt(
                startId = startId,
                reason =
                    "VPN protection boundary start failure"
            )

            return
        }

        val sessionOwner =
            try {
                serviceScope.launch(
                    start = CoroutineStart.LAZY
                ) {

                val ownerJob =
                    currentCoroutineContext()[Job]

                try {
                    var backoffIndex = 0
                    var reconnectCount = 0

                    var firstAttempt =
                        true

                    while (true) {
                    var stableConnection =
                        false

                    var connectedSince =
                        0L

                    val stabilityJob =
                        launch {
                            runtime.engine.state.collect {
                                state ->
                                when (state) {
                                    TunnelState.CONNECTED -> {
                                        if (
                                            connectedSince == 0L
                                        ) {
                                            connectedSince =
                                                SystemClock.elapsedRealtime()
                                        }
                                    }

                                    else -> {
                                        if (
                                            connectedSince != 0L &&
                                            SystemClock.elapsedRealtime() -
                                                connectedSince >=
                                                STABLE_CONNECTION_MILLIS
                                        ) {
                                            stableConnection =
                                                true
                                        }

                                        connectedSince =
                                            0L
                                    }
                                }
                            }
                        }

                    val httpPingPreferences =
                        getSharedPreferences(
                            HTTP_PING_PREFERENCES,
                            MODE_PRIVATE
                        )

                    val httpPingEnabled =
                        httpPingPreferences.getBoolean(
                            HTTP_PING_ENABLED,
                            false
                        )

                    val savedHttpPingUrl =
                        httpPingPreferences.getString(
                            HTTP_PING_URL,
                            HTTP_PING_DEFAULT_URL
                        )
                            ?.trim()
                            .orEmpty()

                    val httpPingUrl =
                        when {
                            savedHttpPingUrl.isBlank() ->
                                HTTP_PING_DEFAULT_URL

                            savedHttpPingUrl ==
                                HTTP_PING_LEGACY_URL ->
                                HTTP_PING_DEFAULT_URL

                            else ->
                                savedHttpPingUrl
                        }

                    val httpPingIntervalSeconds =
                        httpPingPreferences.getInt(
                            HTTP_PING_INTERVAL_SECONDS,
                            HTTP_PING_DEFAULT_INTERVAL_SECONDS
                        )
                            .coerceAtLeast(
                                HTTP_PING_MIN_INTERVAL_SECONDS
                            )

                    if (
                        savedHttpPingUrl != httpPingUrl ||
                        httpPingPreferences.getInt(
                            HTTP_PING_INTERVAL_SECONDS,
                            HTTP_PING_DEFAULT_INTERVAL_SECONDS
                        ) != httpPingIntervalSeconds
                    ) {
                        httpPingPreferences.edit()
                            .putString(
                                HTTP_PING_URL,
                                httpPingUrl
                            )
                            .putInt(
                                HTTP_PING_INTERVAL_SECONDS,
                                httpPingIntervalSeconds
                            )
                            .apply()
                    }

                    val httpPingStateJob =
                        if (httpPingEnabled) {
                            launch {
                                var pingWorker:
                                    Job? = null

                                try {
                                    runtime.engine.state.collect {
                                        state ->
                                        if (
                                            state ==
                                                TunnelState.CONNECTED
                                        ) {
                                            if (
                                                pingWorker
                                                    ?.isActive != true
                                            ) {
                                                pingWorker =
                                                    launch {
                                                        runHttpPingLoop(
                                                            httpPingUrl,
                                                            httpPingIntervalSeconds
                                                                .toLong()
                                                        )
                                                    }
                                            }
                                        } else {
                                            pingWorker?.cancel()
                                            pingWorker = null
                                        }
                                    }
                                } finally {
                                    pingWorker?.cancel()
                                }
                            }
                        } else {
                            null
                        }

                    var attemptFailure:
                        Throwable? = null

                    try {
                        val resetTraffic =
                            firstAttempt

                        firstAttempt =
                            false

                        runtime.engine.connect(
                            profile = profile,
                            vpnGeneration =
                                vpnGeneration,
                            resetTraffic =
                                resetTraffic
                        )
                    } catch (
                        cancelled: CancellationException
                    ) {
                        stabilityJob.cancel()
                        httpPingStateJob?.cancel()
                        throw cancelled
                    } catch (
                        failure: Throwable
                    ) {
                        attemptFailure =
                            failure
                    } finally {

                        if (
                            connectedSince != 0L &&
                            SystemClock.elapsedRealtime() -
                                connectedSince >=
                                STABLE_CONNECTION_MILLIS
                        ) {
                            stableConnection =
                                true
                        }

                        stabilityJob.cancel()
                        httpPingStateJob?.cancel()
                    }

                    val failure =
                        attemptFailure
                            ?: break

                    if (
                        manualStopInProgress.get()
                    ) {
                        break
                    }

                    if (!profile.autoReconnect) {
                        runtime.logger.info(
                            "Auto reconnect disabled"
                        )
                        break
                    }

                    if (
                        TunnelFailureClassifier
                            .classify(failure) !=
                            ReconnectDecision.RETRY
                    ) {
                        runtime.logger.info(
                            "Tunnel failure is terminal; reconnect not attempted"
                        )
                        break
                    }

                    if (stableConnection) {
                        backoffIndex = 0
                    }

                    if (
                        profile.maxReconnect > 0 &&
                        reconnectCount >= profile.maxReconnect
                    ) {
                        runtime.logger.info(
                            "Reconnect limit reached: " +
                                "$reconnectCount/${profile.maxReconnect}"
                        )
                        break
                    }

                    reconnectCount++

                    val delayMillis =
                        RECONNECT_BACKOFF_MILLIS[
                            backoffIndex
                        ]

                    if (
                        backoffIndex <
                        RECONNECT_BACKOFF_MILLIS
                            .lastIndex
                    ) {
                        backoffIndex++
                    }

                    runtime.engine
                        .prepareReconnect()

                    runtime.logger.info(
                        if (profile.maxReconnect == 0) {
                            "Reconnecting $reconnectCount in " +
                                "${delayMillis / 1000L}s"
                        } else {
                            "Reconnecting " +
                                "$reconnectCount/${profile.maxReconnect} in " +
                                "${delayMillis / 1000L}s"
                        }
                    )

                    delay(
                        delayMillis
                    )
                }

                if (
                    !manualStopInProgress.get()
                ) {
                    beginTerminalCleanup(
                        startId = startId,
                        sessionOwner = ownerJob,
                        vpnGeneration =
                            vpnGeneration
                    )
                }
                } finally {

                    if (
                        cleanupJob?.isActive != true &&
                        sessionJob === ownerJob
                    ) {
                        sessionJob = null

                        if (
                            sessionGeneration ==
                                vpnGeneration
                        ) {
                            sessionGeneration =
                                NO_VPN_GENERATION
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
                runtime.logger.error(
                    "Unable to create tunnel session supervisor",
                    failure
                )

                val recoveryOwner =
                    try {
                        serviceScope.launch(
                            start = CoroutineStart.LAZY
                        ) {
                            val cleanupOwner =
                                currentCoroutineContext()[Job]

                            try {
                                try {
                                    when (
                                        val cleanup =
                                            runtime.vpnProtectBridge
                                                .prepareGenerationCleanup(
                                                    vpnGeneration
                                                )
                                    ) {
                                        VpnProtectBridge
                                            .GenerationCleanupDisposition
                                            .RevokedUnowned -> {
                                        }

                                        is VpnProtectBridge
                                            .GenerationCleanupDisposition
                                            .Attached -> {
                                            cleanup.controller
                                                .shutdown()

                                            runtime.vpnProtectBridge
                                                .cancelGeneration(
                                                    vpnGeneration
                                                )
                                        }

                                        VpnProtectBridge
                                            .GenerationCleanupDisposition
                                            .RetainedUnverifiable -> {
                                            error(
                                                "VPN generation remained owned after failed session supervisor creation"
                                            )
                                        }
                                    }
                                } catch (
                                    cancelled: CancellationException
                                ) {
                                    throw cancelled
                                } catch (
                                    cleanupFailure: Throwable
                                ) {
                                    runtime.logger.error(
                                        "VPN service cleanup after session supervisor creation failure failed",
                                        cleanupFailure
                                    )
                                }

                                finishPreAttempt(
                                    startId = startId,
                                    reason =
                                        "session supervisor creation failure"
                                )
                            } finally {
                                if (
                                    cleanupJob === cleanupOwner
                                ) {
                                    cleanupJob = null
                                }
                            }
                        }
                    } catch (
                        cleanupLaunchFailure: Throwable
                    ) {
                        runtime.logger.error(
                            "Unable to create failed-session cleanup supervisor",
                            cleanupLaunchFailure
                        )

                        runtime.vpnProtectBridge
                            .cancelGeneration(
                                vpnGeneration
                            )

                        finishPreAttempt(
                            startId = startId,
                            reason =
                                "session cleanup supervisor creation failure"
                        )

                        return
                    }

                cleanupJob =
                    recoveryOwner

                recoveryOwner.start()

                return
            }

        sessionJob =
            sessionOwner

        sessionGeneration =
            vpnGeneration

        sessionOwner.start()
    }

    private fun beginTerminalCleanup(
        startId: Int,
        sessionOwner: Job?,
        vpnGeneration: Long
    ) {
        if (
            cleanupJob?.isActive == true
        ) {
            runtime.logger.info(
                "Terminal VPN cleanup already in progress"
            )

            return
        }

        val owner =
            serviceScope.launch(
                start = CoroutineStart.LAZY
            ) {
                val cleanupOwner =
                    currentCoroutineContext()[Job]

                try {
                    when (
                        val cleanup =
                            runtime.vpnProtectBridge
                                .prepareGenerationCleanup(
                                    vpnGeneration
                                )
                    ) {
                        VpnProtectBridge
                            .GenerationCleanupDisposition
                            .RevokedUnowned -> {

                        }

                        is VpnProtectBridge
                            .GenerationCleanupDisposition
                            .Attached -> {
                            cleanup.controller
                                .shutdown()

                            runtime.vpnProtectBridge
                                .cancelGeneration(
                                    vpnGeneration
                                )
                        }

                        VpnProtectBridge
                            .GenerationCleanupDisposition
                            .RetainedUnverifiable -> {
                            error(
                                "VPN generation remained owned during terminal cleanup"
                            )
                        }
                    }
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (
                    failure: Throwable
                ) {

                    runtime.logger.error(
                        "VPN service shutdown after terminal tunnel end failed",
                        failure
                    )
                } finally {

                    if (
                        sessionJob === sessionOwner
                    ) {
                        sessionJob = null

                        if (
                            sessionGeneration ==
                                vpnGeneration
                        ) {
                            sessionGeneration =
                                NO_VPN_GENERATION
                        }
                    }

                    if (
                        cleanupJob === cleanupOwner
                    ) {
                        cleanupJob = null
                    }

                    releaseCpuWakeLock()

                    stopSelf(startId)
                }
            }

        cleanupJob =
            owner

        owner.start()
    }

    private suspend fun runHttpPingLoop(
        url: String,
        intervalSeconds: Long
    ) {
        val intervalMillis =
            intervalSeconds
                .coerceAtLeast(
                    HTTP_PING_MIN_INTERVAL_SECONDS
                        .toLong()
                ) * 1_000L

        while (true) {
            val latencyMillis =
                try {
                    performHttpPing(url)
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (
                    failure: Throwable
                ) {
                    null
                }

            if (latencyMillis != null) {
                runtime.logger.info(
                    "HTTP Ping : ${latencyMillis} ms"
                )
            }

            delay(
                intervalMillis
            )
        }
    }

    private suspend fun performHttpPing(
        url: String
    ): Long =
        withContext(
            Dispatchers.IO
        ) {
            val started =
                SystemClock.elapsedRealtime()

            val connection =
                URL(url)
                    .openConnection() as
                    HttpURLConnection

            try {
                connection.connectTimeout =
                    HTTP_PING_TIMEOUT_MILLIS

                connection.readTimeout =
                    HTTP_PING_TIMEOUT_MILLIS

                connection.instanceFollowRedirects =
                    true

                connection.requestMethod =
                    "GET"

                connection.useCaches =
                    false

                connection.responseCode

                SystemClock.elapsedRealtime() -
                    started
            } finally {
                connection.disconnect()
            }
        }

    private fun acquireCpuWakeLock() {
        val existing =
            cpuWakeLock

        if (
            existing != null &&
            existing.isHeld
        ) {
            return
        }

        val powerManager =
            getSystemService(
                POWER_SERVICE
            ) as PowerManager

        val lock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:TunnelSession"
            )

        lock.setReferenceCounted(false)
        lock.acquire()

        cpuWakeLock =
            lock
    }

    private fun releaseCpuWakeLock() {
        val lock =
            cpuWakeLock
                ?: return

        cpuWakeLock =
            null

        if (lock.isHeld) {
            lock.release()
        }
    }

    private fun updateNotification(
        state: TunnelState
    ) {
        NotificationManagerCompat
            .from(this)
            .notify(
                TunnelNotification.NOTIFICATION_ID,
                notification.build(state)
            )
    }

    companion object {
        private const val HTTP_PING_PREFERENCES =
            "http_ping_settings"

        private const val HTTP_PING_ENABLED =
            "enabled"

        private const val HTTP_PING_URL =
            "url"

        private const val HTTP_PING_DEFAULT_URL =
            "https://clients3.google.com/generate_204"

        private const val HTTP_PING_LEGACY_URL =
            "https://www.google.com/generate_204"

        private const val HTTP_PING_INTERVAL_SECONDS =
            "interval_seconds"

        private const val HTTP_PING_DEFAULT_INTERVAL_SECONDS =
            10

        private const val HTTP_PING_MIN_INTERVAL_SECONDS =
            1

        private const val NO_VPN_GENERATION =
            0L

        private val RECONNECT_BACKOFF_MILLIS =
            longArrayOf(
                1_000L,
                2_000L,
                4_000L,
                8_000L,
                16_000L,
                30_000L
            )

        private const val STABLE_CONNECTION_MILLIS =
            30_000L
        private const val HTTP_PING_TIMEOUT_MILLIS =
            10_000

        const val ACTION_CONNECT =
            "com.mdevz.sp.action.CONNECT_TUNNEL"

        const val ACTION_STOP =
            "com.mdevz.sp.action.STOP_TUNNEL"

        const val EXTRA_PROFILE_ID =
            "com.mdevz.sp.extra.PROFILE_ID"
    }
}
