package com.mdevz.sp.tunnel

import com.mdevz.sp.core.model.SshProfile
import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import com.mdevz.sp.core.model.TrafficStats
import com.mdevz.sp.core.model.TunnelState
import com.mdevz.sp.socks.Socks5Server
import com.mdevz.sp.ssh.KnownHostStore
import com.mdevz.sp.ssh.SshStage
import com.mdevz.sp.ssh.TrileadSshClient
import com.mdevz.sp.vpn.VpnProtectBridge
import com.mdevz.sp.vpn.VpnTunnelController
import com.mdevz.sp.vpn.VpnTrafficBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

private data class TunnelResources(
    val vpnGeneration: Long,
    val attemptRxBytes: Long,
    val attemptTxBytes: Long,
    val keepAliveJob: Job?,
    val vpnController: VpnTunnelController?,
    val socksServer: Socks5Server?,
    val sshClient: TrileadSshClient?,
    val transport: TunnelConnection?
)

class TunnelEngine(
    private val knownHostStore: KnownHostStore,
    private val vpnProtectBridge: VpnProtectBridge,
    private val logger: TunnelLogger
) {

    private val lifecycleMutex = Mutex()

    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private val _state =
        MutableStateFlow(
            TunnelState.DISCONNECTED
        )

    val state: StateFlow<TunnelState> =
        _state.asStateFlow()

    private val _traffic =
        MutableStateFlow(
            TrafficStats()
        )

    val traffic: StateFlow<TrafficStats> =
        _traffic.asStateFlow()

    private val _failure =
        MutableStateFlow<TunnelFailure?>(
            null
        )

    val failure: StateFlow<TunnelFailure?> =
        _failure.asStateFlow()

    private val rxBytes =
        AtomicLong(0L)

    private val txBytes =
        AtomicLong(0L)

    private val completedRxBytes =
        AtomicLong(0L)

    private val completedTxBytes =
        AtomicLong(0L)

    private val lastAttemptRxBytes =
        AtomicLong(0L)

    private val lastAttemptTxBytes =
        AtomicLong(0L)

    private var connectJob: Job? = null

    private var transport:
        TunnelConnection? = null

    private var sshClient:
        TrileadSshClient? = null

    private var socksServer:
        Socks5Server? = null

    private var vpnController:
        VpnTunnelController? = null

    private var keepAliveJob:
        Job? = null

    private var cancellationTeardownFailure:
        Throwable? = null

    suspend fun connect(
        profile: SshProfile,
        vpnGeneration: Long,
        resetTraffic: Boolean = true
    ) {
        val snapshot =
            ConnectionSnapshot.fromProfile(
                profile
            )

        require(
            vpnGeneration > 0L
        ) {
            "VPN generation must be positive"
        }

        val callerJob =
            currentCoroutineContext()[Job]

        val staleResources =
            lifecycleMutex.withLock {
                check(
                    connectJob == null &&
                        (
                            _state.value ==
                                TunnelState.DISCONNECTED ||
                            _state.value ==
                                TunnelState.ERROR ||
                            _state.value ==
                                TunnelState.RECONNECTING
                        )
                ) {
                    "Tunnel is already active: ${_state.value}"
                }

                _state.value =
                    TunnelState.CONNECTING_TCP

                _failure.value = null

                cancellationTeardownFailure =
                    null

                lastAttemptRxBytes.set(0L)
                lastAttemptTxBytes.set(0L)

                if (resetTraffic) {
                    completedRxBytes.set(0L)
                    completedTxBytes.set(0L)

                    rxBytes.set(0L)
                    txBytes.set(0L)

                    publishTraffic(
                        connectedAt = 0L
                    )
                } else {

                    rxBytes.set(
                        completedRxBytes.get()
                    )
                    txBytes.set(
                        completedTxBytes.get()
                    )

                    publishTraffic(
                        connectedAt = 0L
                    )
                }

                detachResourcesLocked(
                    skipJob =
                        callerJob,
                    vpnGeneration = 0L
                )
            }

        try {
            closeResources(
                staleResources
            )
        } catch (
            teardownFailure: Throwable
        ) {
            lifecycleMutex.withLock {
                _failure.value =
                    TunnelFailure(
                        stage =
                            TunnelState
                                .CONNECTING_TCP
                                .name,
                        message =
                            safeMessage(
                                teardownFailure
                            ),
                        cause =
                            teardownFailure
                    )

                _state.value =
                    TunnelState.ERROR
            }

            logger.error(
                "Unable to clean stale tunnel resources",
                teardownFailure
            )

            throw teardownFailure
        }

        val job =
            lifecycleMutex.withLock {
                if (
                    connectJob != null ||
                    _state.value !=
                        TunnelState.CONNECTING_TCP
                ) {
                    null
                } else {
                    scope.launch {
                        runConnection(
                            snapshot = snapshot,
                            vpnGeneration =
                                vpnGeneration
                        )
                    }.also {
                        connectJob = it
                    }
                }
            }

        if (job == null) {

            lifecycleMutex.withLock {
                if (
                    connectJob == null &&
                    _state.value ==
                        TunnelState.STOPPING
                ) {
                    _failure.value = null
                    _state.value =
                        TunnelState.DISCONNECTED
                }
            }

            throw CancellationException(
                "Tunnel connect cancelled before launch"
            )
        }

        job.join()

        val failure =
            _failure.value

        if (
            _state.value ==
                TunnelState.ERROR &&
            failure != null
        ) {
            throw failure.cause
        }
    }

    suspend fun prepareReconnect() {
        lifecycleMutex.withLock {
            check(
                _state.value ==
                    TunnelState.ERROR &&
                    connectJob == null
            ) {
                "Cannot enter reconnect backoff from ${_state.value}"
            }

            _state.value =
                TunnelState.RECONNECTING
        }
    }

    suspend fun disconnect() {
        data class StopTargets(
            val job: Job?,
            val vpn: VpnTunnelController?,
            val socks: Socks5Server?,
            val ssh: TrileadSshClient?,
            val connection: TunnelConnection?
        )

        val targets =
            lifecycleMutex.withLock {
                if (
                    _state.value ==
                        TunnelState.DISCONNECTED &&
                    connectJob == null
                ) {
                    return
                }

                _state.value =
                    TunnelState.STOPPING

                logger.info(
                    "Disconnecting..."
                )

                StopTargets(
                    job = connectJob,
                    vpn = vpnController,
                    socks = socksServer,
                    ssh = sshClient,
                    connection = transport
                )
            }

        var ingressFailure:
            Throwable? = null

        fun recordIngressFailure(
            failure: Throwable
        ) {
            val primary =
                ingressFailure

            if (primary == null) {
                ingressFailure =
                    failure
            } else if (
                primary !== failure
            ) {
                primary.addSuppressed(
                    failure
                )
            }
        }

        targets.vpn?.let {
            try {
                it.stop()
            } catch (
                failure: Throwable
            ) {
                recordIngressFailure(
                    failure
                )
            }
        }

        targets.socks?.let {
            try {
                it.stop()
            } catch (
                failure: Throwable
            ) {
                recordIngressFailure(
                    failure
                )
            }
        }

        targets.job?.cancel()

        withContext(
            Dispatchers.IO
        ) {
            targets.ssh?.let {
                runCatching {
                    it.disconnect()
                }
            }

            targets.connection?.let {
                runCatching {
                    it.close()
                }
            }
        }

        targets.job?.join()

        data class RemainingCleanup(
            val resources: TunnelResources,
            val ownerTeardownFailure: Throwable?
        )

        val remaining =
            lifecycleMutex.withLock {
                if (
                    connectJob ===
                        targets.job
                ) {
                    connectJob = null
                }

                val ownerFailure =
                    cancellationTeardownFailure

                cancellationTeardownFailure =
                    null

                RemainingCleanup(
                    resources =
                        detachResourcesLocked(
                            skipJob =
                                currentCoroutineContext()[Job],
                            vpnGeneration = 0L
                        ),
                    ownerTeardownFailure =
                        ownerFailure
                )
            }

        var teardownFailure =
            ingressFailure

        remaining.ownerTeardownFailure
            ?.let {
                val primary =
                    teardownFailure

                if (primary == null) {
                    teardownFailure = it
                } else if (
                    primary !== it
                ) {
                    primary.addSuppressed(
                        it
                    )
                }
            }

        try {
            closeResources(
                remaining.resources
            )
        } catch (
            secondPassFailure: Throwable
        ) {
            val primary =
                teardownFailure

            if (primary == null) {
                teardownFailure =
                    secondPassFailure
            } else if (
                primary !==
                    secondPassFailure
            ) {
                primary.addSuppressed(
                    secondPassFailure
                )
            }
        }

        if (teardownFailure != null) {
            val failure =
                teardownFailure

            lifecycleMutex.withLock {
                _failure.value =
                    TunnelFailure(
                        stage =
                            TunnelState
                                .STOPPING
                                .name,
                        message =
                            safeMessage(
                                failure
                            ),
                        cause =
                            failure
                    )

                _state.value =
                    TunnelState.ERROR
            }

            logger.error(
                "Tunnel stop failed",
                failure
            )

            throw failure
        }

        lifecycleMutex.withLock {
            _failure.value = null

            _state.value =
                TunnelState.DISCONNECTED

            logger.info(
                "Disconnected"
            )
        }
    }

    private suspend fun runConnection(
        snapshot: ConnectionSnapshot,
        vpnGeneration: Long
    ) {
        val ownerJob =
            currentCoroutineContext()[Job]

        try {
            logger.info(
                "Connecting..."
            )

            val payloadLabel =
                when {
                    !snapshot.payloadEnabled ->
                        "Off"

                    snapshot.payloadMode.name ==
                        "ENHANCED" ->
                        "Enhanced"

                    else ->
                        "Normal"
                }

            val tunnelType =
                buildString {
                    append("SSH")

                    if (snapshot.tlsEnabled) {
                        append(" + TLS")
                    }

                    if (snapshot.payloadEnabled) {
                        append(" + ")
                        append(payloadLabel)
                        append(" Payload")
                    }
                }

            logger.info(
                "Tunnel Type : $tunnelType"
            )

            logger.info(
                "Host : ${snapshot.sshHost}"
            )

            logger.info(
                "Port : ${snapshot.sshPort}"
            )

            logger.info(
                "Payload : $payloadLabel"
            )

            logger.info(
                "Proxy : " +
                    if (snapshot.payloadEnabled) {
                        snapshot.proxyHost
                    } else {
                        "Not used"
                    }
            )

            logger.info(
                "Proxy Port : " +
                    if (snapshot.payloadEnabled) {
                        snapshot.proxyPort
                    } else {
                        "Not used"
                    }
            )

            vpnProtectBridge
                .awaitController(
                    generation =
                        vpnGeneration
                )

            currentCoroutineContext()
                .ensureActive()

            val currentTransport =
                ConnectionPipelineBuilder.build(
                    snapshot = snapshot,
                    protectSocket = {
                        socket ->
                        vpnProtectBridge.protect(
                            generation =
                                vpnGeneration,
                            socket =
                                socket
                        )
                    },
                    onStage = {
                        stage ->
                        when (stage) {
                            ConnectionPipelineStage.CONNECTING_TCP ->
                                setState(
                                    TunnelState.CONNECTING_TCP
                                )

                            ConnectionPipelineStage.CONNECTING_TLS ->
                                setState(
                                    TunnelState.CONNECTING_TLS
                                )

                            ConnectionPipelineStage.TLS_CONNECTED ->
                                logger.info(
                                    "TLS handshake completed"
                                )

                            ConnectionPipelineStage.SENDING_PAYLOAD -> {
                                setState(
                                    TunnelState.SENDING_PAYLOAD
                                )

                                val payloadContext =
                                    com.mdevz.sp.payload.PayloadContext(
                                        sshHost =
                                            snapshot.sshHost,
                                        sshPort =
                                            snapshot.sshPort,
                                        proxyHost =
                                            snapshot.proxyHost,
                                        proxyPort =
                                            snapshot.proxyPort
                                    )

                                val payloadLog =
                                    com.mdevz.sp.payload.PayloadPlaceholderResolver
                                        .resolve(
                                            input =
                                                snapshot.payload,
                                            context =
                                                payloadContext
                                        )
                                        .replace(
                                            "\\",
                                            "\\\\"
                                        )
                                        .replace(
                                            "\r",
                                            "\\r"
                                        )
                                        .replace(
                                            "\n",
                                            "\\n"
                                        )

                                logger.info(
                                    "Payload Data : $payloadLog"
                                )
                            }

                            ConnectionPipelineStage.PAYLOAD_SENT ->
                                logger.info(
                                    "Payload sent using " +
                                        snapshot.payloadMode
                                )
                        }
                    }
                )

            currentCoroutineContext()
                .ensureActive()

            lifecycleMutex.withLock {
                transport =
                    currentTransport
            }

            val client =
                TrileadSshClient(
                    knownHostStore =
                        knownHostStore,
                    onStage = {
                        sshStage ->
                        when (sshStage) {
                            SshStage.CONNECTING ->
                                setState(
                                    TunnelState
                                        .CONNECTING_SSH
                                )

                            SshStage
                                .VERIFYING_HOST_KEY ->
                                setState(
                                    TunnelState
                                        .VERIFYING_HOST_KEY
                                )

                            SshStage.AUTHENTICATING -> {
                                logger.info(
                                    "Authenticating..."
                                )

                                setState(
                                    TunnelState
                                        .AUTHENTICATING
                                )
                            }
                        }
                    }
                )

            lifecycleMutex.withLock {
                sshClient = client
            }

            client.connect(
                currentTransport,
                snapshot
            )

            currentCoroutineContext()
                .ensureActive()

            setState(
                TunnelState.STARTING_SOCKS
            )

            val server =
                Socks5Server(
                    sshClient = client,
                    listenPort =
                        snapshot.localSocksPort,

                    onError = {
                        failure ->
                        logger.error(
                            "SOCKS client error",
                            failure
                        )
                    },

                )

            logger.info(
                "Starting SOCKS5..."
            )

            server.start()

            currentCoroutineContext()
                .ensureActive()

            lifecycleMutex.withLock {
                socksServer = server
            }

            val keepAliveFailure =
                startKeepAlive(
                    client = client,
                    seconds =
                        snapshot.keepAliveSeconds
                )

            setState(
                TunnelState.STARTING_VPN
            )

            val controller =
                vpnProtectBridge
                    .awaitController(
                        generation =
                            vpnGeneration
                    )

            lifecycleMutex.withLock {
                vpnController =
                    controller
            }

            controller.start(
                localSocksPort =
                    snapshot.localSocksPort
            )

            currentCoroutineContext()
                .ensureActive()

            if (!controller.isRunning) {
                throw TunnelRuntimeLostException(
                    "Android VPN native tunnel stopped during startup"
                )
            }

            vpnProtectBridge
                .markRoutingActive(
                    generation =
                        vpnGeneration
                )

            val connectedAt =
                System.currentTimeMillis()

            publishTraffic(
                connectedAt =
                    connectedAt
            )

            setState(
                TunnelState.CONNECTED
            )

            logger.info(
                "Connected"
            )

            while (
                currentCoroutineContext()
                    .isActive
            ) {
                delay(
                    CONNECTED_HEALTH_INTERVAL_MILLIS
                )

                currentCoroutineContext()
                    .ensureActive()

                if (
                    keepAliveFailure
                        ?.isCompleted == true
                ) {

                    throw keepAliveFailure.await()
                }

                if (!controller.isRunning) {
                    throw TunnelRuntimeLostException(
                        "Android VPN native tunnel stopped while connected"
                    )
                }

                if (!server.isRunning) {
                    throw TunnelRuntimeLostException(
                        "Local SOCKS5 server stopped while connected"
                    )
                }

                val nativeTraffic =
                    controller.trafficBytes()

                lastAttemptRxBytes.accumulateAndGet(
                    nativeTraffic.rxBytes,
                    ::maxOf
                )

                lastAttemptTxBytes.accumulateAndGet(
                    nativeTraffic.txBytes,
                    ::maxOf
                )

                rxBytes.set(
                    completedRxBytes.get() +
                        lastAttemptRxBytes.get()
                )

                txBytes.set(
                    completedTxBytes.get() +
                        lastAttemptTxBytes.get()
                )

                publishTraffic()
            }
        } catch (
            cancelled: CancellationException
        ) {
            val resources =
                lifecycleMutex.withLock {
                    val detached =
                        detachResourcesLocked(
                            skipJob =
                                ownerJob,
                            vpnGeneration =
                                vpnGeneration
                        )

                    if (
                        _state.value !=
                            TunnelState.STOPPING
                    ) {
                        _state.value =
                            TunnelState.DISCONNECTED
                    }

                    detached
                }

            try {
                closeResources(
                    resources
                )
            } catch (
                teardownFailure: Throwable
            ) {

                lifecycleMutex.withLock {
                    cancellationTeardownFailure =
                        teardownFailure
                }

                if (
                    teardownFailure !==
                        cancelled
                ) {
                    cancelled.addSuppressed(
                        teardownFailure
                    )
                }

                logger.error(
                    "Tunnel cancellation cleanup failed",
                    teardownFailure
                )
            }

            throw cancelled
        } catch (
            failure: Throwable
        ) {
            val failedStage =
                _state.value.name

            logger.error(
                "Tunnel failed at " +
                    "$failedStage: " +
                    safeMessage(failure),
                failure
            )

            val resources =
                lifecycleMutex.withLock {
                    val detached =
                        detachResourcesLocked(
                            skipJob =
                                ownerJob,
                            vpnGeneration =
                                vpnGeneration
                        )

                    _failure.value =
                        TunnelFailure(
                            stage =
                                failedStage,
                            message =
                                safeMessage(failure),
                            cause =
                                failure
                        )

                    _state.value =
                        TunnelState.ERROR

                    detached
                }

            try {
                closeResources(
                    resources
                )
            } catch (
                teardownFailure: Throwable
            ) {

                if (
                    teardownFailure !==
                        failure
                ) {
                    failure.addSuppressed(
                        teardownFailure
                    )
                }

                logger.error(
                    "Tunnel failure cleanup also failed",
                    teardownFailure
                )
            }
        } finally {
            lifecycleMutex.withLock {
                if (
                    connectJob === ownerJob
                ) {
                    connectJob = null
                }
            }
        }
    }

    private fun startKeepAlive(
        client: TrileadSshClient,
        seconds: Int
    ): CompletableDeferred<Throwable>? {
        if (seconds == 0) {
            return null
        }

        keepAliveJob?.cancel()

        val failureSignal =
            CompletableDeferred<Throwable>()

        keepAliveJob =
            scope.launch {
                try {
                    while (
                        isActive &&
                        client.isConnected
                    ) {
                        delay(
                            seconds * 1000L
                        )

                        if (
                            !isActive ||
                            !client.isConnected
                        ) {
                            break
                        }

                        client.sendKeepAlive()
                    }
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (
                    failure: Throwable
                ) {

                    failureSignal.complete(
                        failure
                    )
                }
            }

        return failureSignal
    }

    private fun detachResourcesLocked(
        skipJob: Job?,
        vpnGeneration: Long
    ): TunnelResources {
        val keepAlive =
            keepAliveJob
                ?.takeIf {
                    it !== skipJob
                }

        keepAliveJob = null

        val resources =
            TunnelResources(
                vpnGeneration =
                    vpnGeneration,
                attemptRxBytes =
                    lastAttemptRxBytes.get(),
                attemptTxBytes =
                    lastAttemptTxBytes.get(),
                keepAliveJob =
                    keepAlive,
                vpnController =
                    vpnController,
                socksServer =
                    socksServer,
                sshClient =
                    sshClient,
                transport =
                    transport
            )

        vpnController = null
        socksServer = null
        sshClient = null
        transport = null

        return resources
    }

    private suspend fun closeResources(
        resources: TunnelResources
    ) {
        withContext(
            NonCancellable +
                Dispatchers.IO
        ) {
            var teardownFailure:
                Throwable? = null

            fun recordFailure(
                message: String,
                failure: Throwable
            ) {
                logger.error(
                    message,
                    failure
                )

                val primary =
                    teardownFailure

                if (primary == null) {
                    teardownFailure =
                        failure
                } else if (primary !== failure) {
                    primary.addSuppressed(
                        failure
                    )
                }
            }

            resources.keepAliveJob
                ?.let {
                    try {
                        it.cancelAndJoin()
                    } catch (
                        failure: Throwable
                    ) {
                        recordFailure(
                            "Keepalive teardown failed",
                            failure
                        )
                    }
                }

            resources.socksServer
                ?.let {
                    try {
                        it.stop()
                    } catch (
                        failure: Throwable
                    ) {
                        recordFailure(
                            "SOCKS5 teardown failed",
                            failure
                        )
                    }
                }

            resources.vpnController
                ?.let {
                    var finalTraffic:
                        VpnTrafficBytes? = null

                    try {

                        finalTraffic =
                            it.trafficBytes()
                    } catch (
                        failure: Throwable
                    ) {
                        recordFailure(
                            "Unable to capture final VPN traffic",
                            failure
                        )
                    }

                    var vpnStopped =
                        false

                    try {
                        it.stop()
                        vpnStopped = true

                        if (
                            resources.vpnGeneration > 0L
                        ) {
                            vpnProtectBridge
                                .markRoutingInactive(
                                    generation =
                                        resources.vpnGeneration
                                )
                        }
                    } catch (
                        failure: Throwable
                    ) {

                        recordFailure(
                            "VPN teardown could not be verified",
                            failure
                        )
                    }

                    if (
                        vpnStopped &&
                        finalTraffic != null
                    ) {
                        val committedAttemptRx =
                            maxOf(
                                resources.attemptRxBytes,
                                finalTraffic.rxBytes
                            )

                        val committedAttemptTx =
                            maxOf(
                                resources.attemptTxBytes,
                                finalTraffic.txBytes
                            )

                        completedRxBytes.addAndGet(
                            committedAttemptRx
                        )

                        completedTxBytes.addAndGet(
                            committedAttemptTx
                        )

                        lastAttemptRxBytes.set(
                            committedAttemptRx
                        )

                        lastAttemptTxBytes.set(
                            committedAttemptTx
                        )

                        rxBytes.set(
                            completedRxBytes.get()
                        )

                        txBytes.set(
                            completedTxBytes.get()
                        )
                    }
                }
                ?: run {
                    if (
                        resources.vpnGeneration > 0L
                    ) {
                        vpnProtectBridge
                            .markRoutingInactive(
                                generation =
                                    resources.vpnGeneration
                            )
                    }
                }

            resources.sshClient
                ?.let {
                    try {
                        it.disconnect()
                    } catch (
                        failure: Throwable
                    ) {
                        recordFailure(
                            "SSH teardown failed",
                            failure
                        )
                    }
                }

            resources.transport
                ?.let {
                    try {
                        it.close()
                    } catch (
                        failure: Throwable
                    ) {
                        recordFailure(
                            "Transport teardown failed",
                            failure
                        )
                    }
                }

            publishTraffic(
                connectedAt = 0L
            )

            teardownFailure
                ?.let {
                    throw TunnelTeardownException(
                        message =
                            "Tunnel resource teardown could not be verified",
                        cause =
                            it
                    )
                }
        }
    }

    private companion object {

        const val CONNECTED_HEALTH_INTERVAL_MILLIS =
            1_000L
    }

    private fun setState(
        state: TunnelState
    ) {
        _state.value = state
    }

    private fun publishTraffic(
        connectedAt: Long =
            _traffic.value.connectedAt
    ) {
        _traffic.value =
            TrafficStats(
                rxBytes =
                    rxBytes.get(),
                txBytes =
                    txBytes.get(),
                connectedAt =
                    connectedAt
            )
    }

    private fun safeMessage(
        failure: Throwable
    ): String =
        failure.message
            ?.takeIf {
                it.isNotBlank()
            }
            ?.take(1000)
            ?: failure::class.java.simpleName
}
