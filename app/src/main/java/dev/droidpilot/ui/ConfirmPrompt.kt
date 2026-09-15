package dev.droidpilot.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.droidpilot.R
import dev.droidpilot.agent.AgentRunner

// The guardrail asks its question while the user is in another app, which is
// exactly when the activity is stopped and cannot show a dialog. A heads-up
// notification is the only channel that reaches them there
object ConfirmPrompt {

    private const val CHANNEL_ID = "confirm"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_ANSWER = "dev.droidpilot.CONFIRM_ANSWER"
    private const val EXTRA_APPROVED = "approved"

    // Long enough for the user to notice, short enough that a dead process
    // does not leave the question hanging on screen forever
    private const val TIMEOUT_MILLIS = 10 * 60 * 1000L

    private var receiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (receiver != null) return

        val app = context.applicationContext
        createChannel(app)

        val handler = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_ANSWER) return
                AgentRunner.answerConfirm(intent.getBooleanExtra(EXTRA_APPROVED, false))
                dismiss(context)
            }
        }
        ContextCompat.registerReceiver(
            app,
            handler,
            IntentFilter(ACTION_ANSWER),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = handler
    }

    // A disabled channel swallows the notification, and the agent would then
    // wait on an answer that can never arrive
    fun canReachUser(context: Context): Boolean {
        val app = context.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false

        createChannel(app)
        val channel = manager(app).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(context: Context, reason: String) {
        val app = context.applicationContext
        register(app)

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(app.getString(R.string.confirm_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Not ongoing. A process death between posting this and answering
            // it would otherwise strand a notification the user cannot swipe
            // away, with both actions wired to a receiver that no longer exists
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MILLIS)
            .addAction(0, app.getString(R.string.confirm_allow), answerIntent(app, true))
            .addAction(0, app.getString(R.string.confirm_deny), answerIntent(app, false))
            .build()

        manager(app).notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        manager(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun answerIntent(context: Context, approved: Boolean): PendingIntent {
        val intent = Intent(ACTION_ANSWER)
            .setPackage(context.packageName)
            .putExtra(EXTRA_APPROVED, approved)

        return PendingIntent.getBroadcast(
            context,
            if (approved) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_confirm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_confirm_description)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager(context).createNotificationChannel(channel)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
