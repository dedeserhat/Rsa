package ie.rsa.networkinspector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

private const val CHANNEL_SLOT_ALERTS = "rsa_slot_alerts"
private const val CHANNEL_SESSION_ALERTS = "rsa_session_alerts"
private const val NOTIFICATION_ID_SLOT = 1001
private const val NOTIFICATION_ID_SESSION = 1002

/**
 * Local notifications only, all triggered by something the user's own manual
 * WebView browsing already caused the app to observe - never by a background
 * poll, since this app doesn't run one.
 */
object NotificationHelper {

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val slotChannel = NotificationChannel(
            CHANNEL_SLOT_ALERTS,
            "Driving test slot alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a driving test slot appears in a response you triggered manually"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val sessionChannel = NotificationChannel(
            CHANNEL_SESSION_ALERTS,
            "Session status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tells you when you need to log in to MyRoadSafety again"
        }
        manager.createNotificationChannel(slotChannel)
        manager.createNotificationChannel(sessionChannel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun notifySlotFound(context: Context, slot: DrivingTestSlot) {
        if (!canPostNotifications(context)) return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID_SLOT, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_SLOT_ALERTS)
            .setContentTitle("🚗 Driving Test Slot Available!")
            .setContentText("${slot.centre} • ${slot.date} • ${slot.time}")
            .setStyle(Notification.BigTextStyle().bigText("${slot.centre}\n${slot.date} • ${slot.time}\n\nTap to open MyRoadSafety"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_SLOT + slot.fingerprint.hashCode(), notification)
    }

    fun notifyCentreAvailability(context: Context, centre: TestCentreStatus) {
        if (!canPostNotifications(context)) return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID_SLOT, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_SLOT_ALERTS)
            .setContentTitle("RSA availability detected")
            .setContentText("${centre.name} — ${centre.nextAvailability}")
            .setStyle(Notification.BigTextStyle().bigText("RSA availability detected — ${centre.name} — ${centre.nextAvailability}\n\nTap to open MyRoadSafety"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_SLOT + centre.fingerprint.hashCode(), notification)
    }

    fun notifyLoginRequired(context: Context) {
        if (!canPostNotifications(context)) return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID_SESSION, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_SESSION_ALERTS)
            .setContentTitle("Login required")
            .setContentText("Please log in to MyRoadSafety again")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_SESSION, notification)
    }
}
