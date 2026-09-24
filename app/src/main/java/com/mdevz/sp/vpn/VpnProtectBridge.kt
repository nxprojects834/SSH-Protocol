package com.mdevz.sp.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.net.Socket
import kotlin.coroutines.coroutineContext

class VpnProtectBridge {

    private data class Attachment(
        val generation: Long,
        val service: TunnelVpnService
    )

    sealed interface AdmissionRecoveryDisposition {
        data object Ready :
            AdmissionRecoveryDisposition

        data class Attached(
            val generation: Long,
            val controller: VpnTunnelController
        ) : AdmissionRecoveryDisposition

        data object RetainedUnverifiable :
            AdmissionRecoveryDisposition
    }

    sealed interface GenerationCleanupDisposition {
        data object RevokedUnowned :
            GenerationCleanupDisposition

        data class Attached(
            val controller: VpnTunnelController
        ) : GenerationCleanupDisposition

        data object RetainedUnverifiable :
            GenerationCleanupDisposition
    }

    private data class OwnershipState(
        val expectedGeneration: Long =
            NO_GENERATION,
        val attachment: Attachment? =
            null,
        val routingGeneration: Long =
            NO_GENERATION
    )

    private val ownershipLock =
        Any()

    private var ownership =
        OwnershipState()

    private var nextGeneration =
        NO_GENERATION

    fun prepareAdmissionRecovery():
        AdmissionRecoveryDisposition =
        synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            val expected =
                current.expectedGeneration

            val attached =
                current.attachment

            val routing =
                current.routingGeneration

            if (
                expected ==
                    NO_GENERATION
            ) {
                return@synchronized if (
                        attached == null &&
                        routing ==
                            NO_GENERATION
                    ) {
                        AdmissionRecoveryDisposition
                            .Ready
                    } else {
                        AdmissionRecoveryDisposition
                            .RetainedUnverifiable
                    }
            }

            if (
                attached == null &&
                routing ==
                    NO_GENERATION
            ) {
                ownership =
                    current.copy(
                        expectedGeneration =
                            NO_GENERATION
                    )

                return@synchronized AdmissionRecoveryDisposition
                        .Ready
            }

            if (
                attached != null &&
                attached.generation ==
                    expected &&
                (
                    routing ==
                        NO_GENERATION ||
                    routing ==
                        expected
                )
            ) {
                return@synchronized AdmissionRecoveryDisposition
                        .Attached(
                            generation =
                                expected,
                            controller =
                                attached.service
                        )
            }

            AdmissionRecoveryDisposition
                .RetainedUnverifiable
        }

    fun reserveGeneration(): Long =
        synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            check(
                current.expectedGeneration ==
                    NO_GENERATION
            ) {
                "A VPN admission generation is already reserved"
            }

            check(
                current.attachment == null
            ) {
                "A VPN service generation is still attached"
            }

            check(
                current.routingGeneration ==
                    NO_GENERATION
            ) {
                "VPN routing ownership is still active"
            }

            check(
                nextGeneration !=
                    Long.MAX_VALUE
            ) {
                "VPN admission generation exhausted"
            }

            val generation =
                nextGeneration + 1L

            nextGeneration =
                generation

            ownership =
                current.copy(
                    expectedGeneration =
                        generation
                )

            generation
        }

    fun attach(
        vpnService: TunnelVpnService,
        generation: Long
    ): Boolean {
        requireValidGeneration(
            generation
        )

        return synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            if (
                current.expectedGeneration !=
                    generation
            ) {
                return@synchronized false
            }

            val attached =
                current.attachment

            if (attached != null) {
                return@synchronized (
                    attached.generation ==
                        generation &&
                        attached.service ===
                            vpnService
                    )
            }

            ownership =
                current.copy(
                    attachment =
                        Attachment(
                            generation =
                                generation,
                            service =
                                vpnService
                        )
                )

            true
        }
    }

    fun detach(
        vpnService: TunnelVpnService,
        generation: Long
    ) {
        requireValidGeneration(
            generation
        )

        synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            val attached =
                current.attachment
                    ?: return@synchronized

            if (
                attached.generation !=
                    generation ||
                attached.service !==
                    vpnService
            ) {
                return@synchronized
            }

            ownership =
                current.copy(
                    attachment =
                        null,
                    routingGeneration =
                        if (
                            current.routingGeneration ==
                                generation
                        ) {
                            NO_GENERATION
                        } else {
                            current.routingGeneration
                        }
                )
        }
    }

    fun cancelGeneration(
        generation: Long
    ) {
        requireValidGeneration(
            generation
        )

        synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            ownership =
                current.copy(
                    expectedGeneration =
                        if (
                            current.expectedGeneration ==
                                generation
                        ) {
                            NO_GENERATION
                        } else {
                            current.expectedGeneration
                        },
                    attachment =
                        current.attachment
                            ?.takeUnless {
                                it.generation ==
                                    generation
                            },
                    routingGeneration =
                        if (
                            current.routingGeneration ==
                                generation
                        ) {
                            NO_GENERATION
                        } else {
                            current.routingGeneration
                        }
                )
        }
    }

    fun prepareGenerationCleanup(
        generation: Long
    ): GenerationCleanupDisposition {
        requireValidGeneration(
            generation
        )

        return synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            if (
                current.expectedGeneration !=
                    generation
            ) {
                return@synchronized GenerationCleanupDisposition
                        .RetainedUnverifiable
            }

            val attached =
                current.attachment

            if (
                attached != null &&
                attached.generation ==
                    generation
            ) {
                return@synchronized GenerationCleanupDisposition
                        .Attached(
                            controller =
                                attached.service
                        )
            }

            if (
                current.routingGeneration ==
                    generation
            ) {
                return@synchronized GenerationCleanupDisposition
                        .RetainedUnverifiable
            }

            if (
                attached != null ||
                current.routingGeneration !=
                    NO_GENERATION
            ) {
                return@synchronized GenerationCleanupDisposition
                        .RetainedUnverifiable
            }

            ownership =
                current.copy(
                    expectedGeneration =
                        NO_GENERATION
                )

            GenerationCleanupDisposition
                .RevokedUnowned
        }
    }

    fun currentController(
        generation: Long
    ): VpnTunnelController? {
        requireValidGeneration(
            generation
        )

        return synchronized(
            ownershipLock
        ) {
            ownership.attachment
                ?.takeIf {
                    it.generation ==
                        generation
                }
                ?.service
        }
    }

    suspend fun awaitController(
        generation: Long,
        timeoutMillis: Long =
            DEFAULT_ATTACH_TIMEOUT_MILLIS
    ): VpnTunnelController {
        requireValidGeneration(
            generation
        )

        return withTimeout(
            timeoutMillis
        ) {
            while (true) {
                coroutineContext
                    .ensureActive()

                val snapshot =
                    synchronized(
                        ownershipLock
                    ) {
                        val current =
                            ownership

                        Pair(
                            current.expectedGeneration,
                            current.attachment
                        )
                    }

                check(
                    snapshot.first ==
                        generation
                ) {
                    "VPN admission generation is no longer active"
                }

                val attached =
                    snapshot.second

                if (
                    attached != null &&
                    attached.generation ==
                        generation
                ) {
                    return@withTimeout attached.service
                }

                delay(
                    ATTACH_POLL_MILLIS
                )
            }

            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException(
                "Unreachable VPN attachment state"
            )
        }
    }

    fun markRoutingActive(
        generation: Long
    ) {
        requireValidGeneration(
            generation
        )

        synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            val attached =
                current.attachment

            check(
                current.expectedGeneration ==
                    generation &&
                    attached != null &&
                    attached.generation ==
                        generation
            ) {
                "Cannot activate routing for a stale VPN generation"
            }

            check(
                current.routingGeneration ==
                    NO_GENERATION ||
                    current.routingGeneration ==
                        generation
            ) {
                "Another VPN generation already owns routing"
            }

            ownership =
                current.copy(
                    routingGeneration =
                        generation
                )
        }
    }

    fun markRoutingInactive(
        generation: Long
    ) {
        requireValidGeneration(
            generation
        )

        synchronized(
            ownershipLock
        ) {
            val current =
                ownership

            if (
                current.routingGeneration ==
                    generation
            ) {
                ownership =
                    current.copy(
                        routingGeneration =
                            NO_GENERATION
                    )
            }
        }
    }

    fun protect(
        generation: Long,
        socket: Socket
    ): Boolean {
        requireValidGeneration(
            generation
        )

        val controller =
            synchronized(
                ownershipLock
            ) {
                val current =
                    ownership

                if (
                    current.expectedGeneration !=
                        generation
                ) {
                    return@synchronized null
                }

                current.attachment
                    ?.takeIf {
                        it.generation ==
                            generation
                    }
                    ?.service
            }
                ?: return false

        return controller.protect(
            socket
        )
    }

    fun hasActiveService(
        generation: Long
    ): Boolean {
        requireValidGeneration(
            generation
        )

        return synchronized(
            ownershipLock
        ) {
            ownership.attachment
                ?.generation ==
                generation
        }
    }

    fun isRoutingActive(
        generation: Long
    ): Boolean {
        requireValidGeneration(
            generation
        )

        return synchronized(
            ownershipLock
        ) {
            ownership.routingGeneration ==
                generation
        }
    }

    fun isGenerationExpected(
        generation: Long
    ): Boolean {
        requireValidGeneration(
            generation
        )

        return synchronized(
            ownershipLock
        ) {
            ownership.expectedGeneration ==
                generation
        }
    }

    private fun requireValidGeneration(
        generation: Long
    ) {
        require(
            generation >
                NO_GENERATION
        ) {
            "VPN generation must be positive"
        }
    }

    companion object {
        private const val NO_GENERATION =
            0L

        private const val DEFAULT_ATTACH_TIMEOUT_MILLIS =
            5_000L

        private const val ATTACH_POLL_MILLIS =
            25L
    }
}
