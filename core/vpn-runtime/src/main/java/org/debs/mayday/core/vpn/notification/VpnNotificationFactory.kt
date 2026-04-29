package org.debs.mayday.core.vpn.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.vpn.R
import org.debs.mayday.core.vpn.service.VpnCoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnNotificationFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.mayday_vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.mayday_vpn_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun create(state: VpnRuntimeState): Notification {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                1001,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mayday_notification)
            .setContentTitle(
                state.headline.ifBlank {
                    defaultTitle(state)
                },
            )
            .setContentText(
                state.detail.ifBlank {
                    defaultText(state)
                },
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    state.detail.ifBlank { defaultText(state) },
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        pendingIntent?.let(builder::setContentIntent)
        actionFor(state)?.let(builder::addAction)
        return builder.build()
    }

    private fun defaultTitle(state: VpnRuntimeState): String {
        return when (state.status) {
            VpnConnectionStatus.Idle -> context.getString(R.string.mayday_vpn_notification_idle_title)
            else -> context.getString(R.string.mayday_vpn_notification_title)
        }
    }

    private fun defaultText(state: VpnRuntimeState): String {
        return when (state.status) {
            VpnConnectionStatus.Idle -> context.getString(R.string.mayday_vpn_notification_idle_text)
            else -> context.getString(R.string.mayday_vpn_notification_text)
        }
    }

    private fun actionFor(state: VpnRuntimeState): NotificationCompat.Action? {
        return when (state.status) {
            VpnConnectionStatus.Starting,
            VpnConnectionStatus.Running,
            -> NotificationCompat.Action.Builder(
                R.drawable.ic_mayday_disconnect,
                context.getString(R.string.mayday_vpn_action_disconnect),
                PendingIntent.getService(
                    context,
                    REQUEST_DISCONNECT,
                    VpnCoreService.disconnectIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
            VpnConnectionStatus.Idle,
            VpnConnectionStatus.CoreMissing,
            VpnConnectionStatus.Error,
            -> NotificationCompat.Action.Builder(
                R.drawable.ic_mayday_connect,
                context.getString(R.string.mayday_vpn_action_connect),
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_CONNECT,
                    VpnCoreService.startIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
            VpnConnectionStatus.Stopping -> null
        }
    }

    companion object {
        const val CHANNEL_ID = "mayday.runtime"
        const val NOTIFICATION_ID = 4040
        private const val REQUEST_CONNECT = 1002
        private const val REQUEST_DISCONNECT = 1003
    }
}
