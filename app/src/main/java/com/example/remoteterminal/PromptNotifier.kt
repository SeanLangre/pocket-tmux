package com.example.remoteterminal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalSession

/** Posts the "needs your input" alert and the ongoing notification used by [KeepAliveService]. */
class PromptNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Input needed", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Claude or another CLI is waiting for you to make a decision"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the session alive while the app is in the background"
            }
        )
    }

    fun serviceNotification(): Notification =
        Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Remote Terminal")
            .setContentText("Watching for prompts that need your input")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    fun notifyInputNeeded(line: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = Notification.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Input needed")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_ID, notification)
    }

    fun cancelAlert() = manager.cancel(ALERT_ID)

    private fun openAppIntent() = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SERVICE_ID = 1
        private const val ALERT_ID = 2
        private const val ALERT_CHANNEL = "input_needed"
        private const val SERVICE_CHANNEL = "connection"
    }
}

/**
 * Watches the terminal screen and raises a notification when it settles on a prompt that waits for
 * a decision (permission menus, y/n questions) while the app is in the background.
 */
class PromptDetector(
    private val terminal: TerminalSession,
    private val notifier: PromptNotifier,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotified: String? = null

    var inForeground = true
        set(value) {
            field = value
            if (value) notifier.cancelAlert()
        }

    private val check = Runnable {
        val prompt = findPrompt()
        Log.d(TAG, "settled: prompt=$prompt foreground=$inForeground lastNotified=$lastNotified")
        if (prompt == null) {
            lastNotified = null
        } else if (!inForeground && prompt != lastNotified) {
            lastNotified = prompt
            notifier.notifyInputNeeded(prompt)
        }
    }

    /** Clears the de-dupe state as soon as the prompt leaves the screen, even while output keeps flowing. */
    private val clearIfGone = Runnable {
        clearPending = false
        if (findPrompt() == null) lastNotified = null
    }
    private var clearPending = false

    /** Call on every screen update; the check runs once output has been quiet for [SETTLE_MS]. */
    fun onOutput() {
        handler.removeCallbacks(check)
        handler.postDelayed(check, SETTLE_MS)
        if (lastNotified != null && !clearPending) {
            clearPending = true
            handler.postDelayed(clearIfGone, CLEAR_INTERVAL_MS)
        }
    }

    private fun findPrompt(): String? {
        val emulator = terminal.emulator ?: return null
        val screen = emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
        val lines = screen.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(TAIL_LINES)
        // PATTERNS are ordered by usefulness as a notification body: prefer the question over footer hints.
        return PATTERNS.firstNotNullOfOrNull { pattern -> lines.lastOrNull { pattern.containsMatchIn(it) } }
            ?.take(MAX_LENGTH)
    }

    private companion object {
        const val TAG = "PromptDetector"
        const val SETTLE_MS = 1500L
        const val CLEAR_INTERVAL_MS = 500L
        const val TAIL_LINES = 12
        const val MAX_LENGTH = 160
        val PATTERNS = listOf(
            Regex("""Approval required|Run command:""", RegexOption.IGNORE_CASE),
            Regex("""\(y/n\)|\[y/n]|\[Y/n]|\[y/N]""", RegexOption.IGNORE_CASE),
            Regex("""\bDo you want to\b""", RegexOption.IGNORE_CASE),
            Regex("""\bWould you like to\b""", RegexOption.IGNORE_CASE),
            Regex("""^\W*1\.\s*Yes\b"""),
            Regex("""Esc to cancel|Enter to confirm|Press Enter to continue""", RegexOption.IGNORE_CASE),
        )
    }
}
