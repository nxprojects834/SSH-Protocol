package com.mdevz.sp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mdevz.sp.R
import com.mdevz.sp.core.model.TunnelState

class TunnelNotification(
    private val context: Context
) {

    fun ensureChannel() {
        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "SSH tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "SSH Protocol tunnel status"
            }

        manager.createNotificationChannel(channel)
    }

    fun build(
        state: TunnelState
    ): Notification {
        return NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.stat_sys_upload
            )
            .setContentTitle(
                context.getString(R.string.app_name)
            )
            .setContentText(
                stateText(state)
            )
            .setOngoing(
                state != TunnelState.DISCONNECTED &&
                    state != TunnelState.ERROR
            )
            .setOnlyAlertOnce(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    private fun stateText(
        state: TunnelState
    ): String =
        when (state) {
            TunnelState.DISCONNECTED ->
                "Disconnected"

            TunnelState.CONNECTING_TCP ->
                "Connecting TCP"

            TunnelState.CONNECTING_TLS ->
                "Negotiating TLS"

            TunnelState.SENDING_PAYLOAD ->
                "Sending payload"

            TunnelState.CONNECTING_SSH ->
                "Starting SSH"

            TunnelState.VERIFYING_HOST_KEY ->
                "Verifying SSH host key"

            TunnelState.AUTHENTICATING ->
                "Authenticating SSH"

            TunnelState.STARTING_SOCKS ->
                "Starting SOCKS5"

            TunnelState.STARTING_VPN ->
                "Starting VPN"

            TunnelState.CONNECTED ->
                "Connected"

            TunnelState.RECONNECTING ->
                "Reconnecting"

            TunnelState.STOPPING ->
                "Stopping"

            TunnelState.ERROR ->
                "Connection error"
        }

    companion object {
        const val CHANNEL_ID =
            "ssh_protocol_tunnel"

        const val NOTIFICATION_ID = 1001
    }
}
