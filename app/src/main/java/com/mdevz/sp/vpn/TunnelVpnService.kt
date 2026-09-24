package com.mdevz.sp.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.mdevz.sp.core.model.TunnelState
import com.mdevz.sp.service.TunnelNotification
import com.mdevz.sp.service.TunnelRuntime
import hev.htproxy.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class TunnelVpnService :
    VpnService(),
    VpnTunnelController {

    private lateinit var runtime:
        TunnelRuntime

    private val native =
        TProxyService()

    private val running =
        AtomicBoolean(false)

    private val lifecycleLock =
        Any()

    private var nativeTeardownFailure:
        Throwable? = null

    private var tun:
        ParcelFileDescriptor? = null

    private var activeConfig:
        File? = null

    @Volatile
    private var activeGeneration:
        Long = NO_VPN_GENERATION

    override fun onCreate() {
        super.onCreate()

        runtime =
            TunnelRuntime.get(this)

        val notification =
            TunnelNotification(this)

        notification.ensureChannel()

        startForeground(
            VPN_NOTIFICATION_ID,
            notification.build(
                TunnelState.STARTING_VPN
            ),
            ServiceInfo
                .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val generation =
            intent?.getLongExtra(
                EXTRA_VPN_GENERATION,
                NO_VPN_GENERATION
            )
                ?: NO_VPN_GENERATION

        if (
            generation <=
                NO_VPN_GENERATION
        ) {
            runtime.logger.error(
                "VpnService rejected start without valid generation"
            )

            stopSelf(
                startId
            )

            return START_NOT_STICKY
        }

        val existingGeneration =
            activeGeneration

        if (
            existingGeneration >
                NO_VPN_GENERATION &&
            existingGeneration !=
                generation
        ) {

            runtime.logger.info(
                "VpnService ignored generation $generation because " +
                    "generation $existingGeneration already owns this instance"
            )

            return START_NOT_STICKY
        }

        val accepted =
            try {
                runtime.vpnProtectBridge
                    .attach(
                        vpnService = this,
                        generation = generation
                    )
            } catch (
                failure: Throwable
            ) {
                runtime.logger.error(
                    "VpnService generation validation failed",
                    failure
                )

                false
            }

        if (!accepted) {
            runtime.logger.info(
                "VpnService rejected stale generation $generation"
            )

            if (
                activeGeneration ==
                    NO_VPN_GENERATION
            ) {
                stopSelf(
                    startId
                )
            }

            return START_NOT_STICKY
        }

        activeGeneration =
            generation


        return START_NOT_STICKY
    }

    override fun onDestroy() {

        stopBlocking(
            strict = false
        )

        val teardownVerified =
            synchronized(
                lifecycleLock
            ) {
                nativeTeardownFailure ==
                    null
            }

        if (::runtime.isInitialized) {
            val generation =
                activeGeneration

            if (
                teardownVerified &&
                generation >
                    NO_VPN_GENERATION
            ) {
                runtime.vpnProtectBridge
                    .detach(
                        vpnService = this,
                        generation = generation
                    )

                activeGeneration =
                    NO_VPN_GENERATION

            } else if (
                !teardownVerified &&
                generation >
                    NO_VPN_GENERATION
            ) {
                runtime.logger.error(
                    "VpnService destruction retained generation " +
                        generation +
                        " because native teardown was not verified"
                )
            }
        }

        super.onDestroy()
    }

    override fun onRevoke() {
        stopBlocking(
            strict = false
        )

        val teardownVerified =
            synchronized(
                lifecycleLock
            ) {
                nativeTeardownFailure ==
                    null
            }

        if (::runtime.isInitialized) {
            val generation =
                activeGeneration

            if (
                teardownVerified &&
                generation >
                    NO_VPN_GENERATION
            ) {
                runtime.vpnProtectBridge
                    .detach(
                        vpnService = this,
                        generation = generation
                    )

                activeGeneration =
                    NO_VPN_GENERATION
            } else if (
                !teardownVerified &&
                generation >
                    NO_VPN_GENERATION
            ) {
                runtime.logger.error(
                    "VPN revoke retained generation " +
                        generation +
                        " because native teardown was not verified"
                )
            }

            runtime.logger.error(
                "VPN authorization was revoked"
            )
        }

        stopSelf()

        super.onRevoke()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        super.onBind(intent)

    override val isRunning: Boolean
        get() =
            running.get() &&
                native.TProxyIsRunning()

    override fun trafficBytes(): VpnTrafficBytes {
        if (!isRunning) {
            return VpnTrafficBytes(
                rxBytes = 0L,
                txBytes = 0L
            )
        }

        val stats =
            native.TProxyGetStats()

        if (stats.size < 4) {
            throw IOException(
                "hev-socks5-tunnel returned invalid traffic stats"
            )
        }

        return VpnTrafficBytes(
            rxBytes = stats[3],
            txBytes = stats[1]
        )
    }

    override suspend fun start(
        localSocksPort: Int
    ): VpnTunnelSession =
        withContext(
            Dispatchers.IO
        ) {
            require(
                localSocksPort in 1..65535
            ) {
                "SOCKS5 port must be 1..65535"
            }

            synchronized(
                lifecycleLock
            ) {
                if (
                    running.get() ||
                    tun != null ||
                    native.TProxyIsRunning()
                ) {
                    throw IllegalStateException(
                        "VPN tunnel is already running"
                    )
                }

                val config =
                    writeConfig(
                        localSocksPort
                    )

                var establishedTun:
                    ParcelFileDescriptor? = null

                try {
                    establishedTun =
                        Builder()
                            .setSession(
                                "SSH Protocol"
                            )
                            .setMtu(
                                VPN_MTU
                            )
                            .addAddress(
                                VPN_IPV4,
                                VPN_PREFIX
                            )
                            .addRoute(
                                "0.0.0.0",
                                0
                            )

                            .addDnsServer(
                                VPN_MAPDNS_ADDRESS
                            )
                            .establish()
                            ?: throw IOException(
                                "VpnService.Builder.establish() returned null"
                            )

                    val rawFd =
                        establishedTun
                            .detachFd()

                    establishedTun = null

                    var nativeWorkerOwnsFd =
                        false

                    try {
                        nativeTeardownFailure?.let { failure ->
                            throw IllegalStateException(
                                "Previous VPN native teardown remains unverified",
                                failure
                            )
                        }

                        val started =
                            native.TProxyStartService(
                                config.absolutePath,
                                rawFd
                            )

                        if (!started) {
                            throw IOException(
                                "hev-socks5-tunnel native start failed"
                            )
                        }

                        nativeWorkerOwnsFd =
                            true

                        if (
                            !native
                                .TProxyIsRunning()
                        ) {

                            val stopped =
                                try {
                                    native
                                        .TProxyStopService()
                                } catch (
                                    failure: Throwable
                                ) {
                                    nativeTeardownFailure =
                                        failure

                                    throw IOException(
                                        "hev-socks5-tunnel stopped during startup " +
                                            "and native teardown could not be verified",
                                        failure
                                    )
                                }

                            if (!stopped) {
                                val failure =
                                    IOException(
                                        "hev-socks5-tunnel stopped during startup " +
                                            "and native stop/join failed"
                                    )

                                nativeTeardownFailure =
                                    failure

                                throw failure
                            }

                            throw IOException(
                                "hev-socks5-tunnel did not remain running"
                            )
                        }
                    } finally {
                        if (!nativeWorkerOwnsFd) {

                            runCatching {

                                ParcelFileDescriptor
                                    .adoptFd(rawFd)
                                    .close()
                            }
                        }
                    }

                    activeConfig =
                        config

                    running.set(
                        true
                    )

                    runtime.logger.info(
                        "Starting VPN..."
                    )

                    VpnTunnelSession(
                        mtu =
                            VPN_MTU,
                        ipv4Address =
                            VPN_IPV4,
                        socksPort =
                            localSocksPort
                    )
                } catch (
                    failure: Throwable
                ) {
                    runCatching {
                        establishedTun
                            ?.close()
                    }

                    runCatching {
                        config.delete()
                    }

                    activeConfig = null
                    running.set(false)

                    throw failure
                }
            }
        }

    override suspend fun stop() {
        withContext(
            Dispatchers.IO
        ) {

            stopBlocking(
                strict = true
            )
        }
    }

    override suspend fun shutdown() {
        withContext(
            Dispatchers.IO
        ) {

            stopBlocking(
                strict = true
            )

            stopSelf()
        }
    }

    private fun stopBlocking(
        strict: Boolean = false
    ) {
        synchronized(
            lifecycleLock
        ) {
            var stopFailure:
                Throwable? =
                    nativeTeardownFailure

            try {

                val nativeRunning =
                    native
                        .TProxyIsRunning()

                if (
                    running.get() ||
                    nativeRunning
                ) {
                    val stopped =
                        native
                            .TProxyStopService()

                    if (!stopped) {
                        throw IOException(
                            "hev-socks5-tunnel native stop/join failed"
                        )
                    }
                }

                if (
                    native
                        .TProxyIsRunning()
                ) {
                    throw IOException(
                        "hev-socks5-tunnel is still running after stop"
                    )
                }
            } catch (
                failure: Throwable
            ) {
                val retained =
                    stopFailure

                if (
                    retained == null
                ) {
                    stopFailure =
                        failure
                } else if (
                    retained !== failure
                ) {
                    retained.addSuppressed(
                        failure
                    )
                }

                nativeTeardownFailure =
                    stopFailure

                runtimeOrNull()
                    ?.logger
                    ?.error(
                        "VPN native teardown failed",
                        failure
                    )
            }

            runCatching {
                tun?.close()
            }

            tun = null

            activeConfig
                ?.let {
                    runCatching {
                        it.delete()
                    }
                }

            activeConfig = null

            running.set(false)

            if (
                strict &&
                stopFailure != null
            ) {
                throw IOException(
                    "Unable to stop Android VPN native tunnel cleanly",
                    stopFailure
                )
            }
        }
    }

    private fun writeConfig(
        localSocksPort: Int
    ): File {
        val directory =
            File(
                filesDir,
                "hev"
            )

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IOException(
                "Unable to create hev configuration directory"
            )
        }

        val file =
            File(
                directory,
                "active.yml"
            )

        val config =
            buildString {
                appendLine(
                    "tunnel:"
                )
                appendLine(
                    "  name: tun0"
                )
                appendLine(
                    "  mtu: $VPN_MTU"
                )
                appendLine(
                    "  multi-queue: false"
                )
                appendLine(
                    "  ipv4: $VPN_IPV4"
                )
                appendLine(
                    "  icmp: 'off'"
                )
                appendLine(
                    "socks5:"
                )
                appendLine(
                    "  port: $localSocksPort"
                )
                appendLine(
                    "  address: 127.0.0.1"
                )
                appendLine(
                    "  udp: 'tcp'"
                )

                appendLine(
                    "mapdns:"
                )
                appendLine(
                    "  address: $VPN_MAPDNS_ADDRESS"
                )
                appendLine(
                    "  port: $VPN_MAPDNS_PORT"
                )
                appendLine(
                    "  network: $VPN_MAPDNS_NETWORK"
                )
                appendLine(
                    "  netmask: $VPN_MAPDNS_NETMASK"
                )
                appendLine(
                    "  cache-size: $VPN_MAPDNS_CACHE_SIZE"
                )
                appendLine(
                    "misc:"
                )
                appendLine(
                    "  log-file: null"
                )
                appendLine(
                    "  log-level: warn"
                )
            }

        file.writeText(
            config,
            Charsets.UTF_8
        )

        return file
    }

    private fun runtimeOrNull():
        TunnelRuntime? =
        if (::runtime.isInitialized) {
            runtime
        } else {
            null
        }

    companion object {

        private const val VPN_MAPDNS_ADDRESS =
            "198.18.0.2"

        private const val VPN_MAPDNS_PORT =
            53

        private const val VPN_MAPDNS_NETWORK =
            "100.64.0.0"

        private const val VPN_MAPDNS_NETMASK =
            "255.192.0.0"

        private const val VPN_MAPDNS_CACHE_SIZE =
            10000

        const val EXTRA_VPN_GENERATION =
            "com.mdevz.sp.extra.VPN_GENERATION"

        private const val NO_VPN_GENERATION =
            0L

        private const val VPN_NOTIFICATION_ID =
            1002

        private const val VPN_MTU =
            1500

        private const val VPN_IPV4 =
            "198.18.0.1"

        private const val VPN_PREFIX =
            32
    }
}
