package com.mdevz.sp.service

import android.content.Context
import com.mdevz.sp.core.config.SharedPreferencesProfileRepository
import com.mdevz.sp.ssh.SharedPreferencesKnownHostStore
import com.mdevz.sp.tunnel.MemoryTunnelLogger
import com.mdevz.sp.tunnel.TunnelEngine
import com.mdevz.sp.vpn.VpnProtectBridge

class TunnelRuntime private constructor(
    context: Context
) {

    private val appContext =
        context.applicationContext

    val logger =
        MemoryTunnelLogger()

    val knownHosts =
        SharedPreferencesKnownHostStore(
            appContext
        )

    val profiles =
        SharedPreferencesProfileRepository(
            appContext
        )

    val vpnProtectBridge =
        VpnProtectBridge()

    val engine =
        TunnelEngine(
            knownHostStore = knownHosts,
            vpnProtectBridge =
                vpnProtectBridge,
            logger = logger
        )

    companion object {
        @Volatile
        private var instance:
            TunnelRuntime? = null

        fun get(
            context: Context
        ): TunnelRuntime =
            instance
                ?: synchronized(this) {
                    instance
                        ?: TunnelRuntime(
                            context.applicationContext
                        ).also {
                            instance = it
                        }
                }
    }
}
