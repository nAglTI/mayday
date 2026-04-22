package org.debs.mayday.core.vpn.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.vpn.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnNotificationFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(state.headline)
            .setContentText(state.detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        pendingIntent?.let(builder::setContentIntent)
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "mayday.runtime"
        const val NOTIFICATION_ID = 4040
    }
}
