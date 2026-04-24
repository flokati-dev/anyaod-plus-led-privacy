package com.codecandy.blinkify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * AodNotificationListener - Notification Listener for AnyAOD+ LED
 *
 * PRIVACY NOTICE
 *
 * This service uses Notification Listener permission EXCLUSIVELY for:
 *   1. Detecting WHICH app posted a notification (package name only)
 *   2. Determining the ORDER of notifications (via postTime)
 *
 * The service reads:
 *   - ONLY sbn.packageName (e.g. "com.whatsapp")
 *   - ONLY sbn.postTime (timestamp, used solely for ordering)
 *
 * The service does NOT read:
 *   - NO sbn.notification.extras      - NO notification content
 *   - NO sbn.notification.tickerText  - NO preview text
 *   - NO title, body, subText, or any user-facing text
 *   - NO sender names, contact info, or message content
 *   - NO attachments, images, or action buttons
 *   - NO passwords, 2FA codes, or sensitive payloads
 *
 * The only logging is generic error messages (e.g. "REBUILD error: ...").
 * Notification CONTENT is never logged.
 *
 * All rendering happens in NotificationRenderEngine.kt based solely on
 * the package name + ordering. The visible dots/labels/borders use the
 * app icon + user-configured color from the app's settings.
 */
class AodNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    // Our own source of truth: pkg -> "last known notification time".
    // LinkedHashMap preserves insertion order for stable ordering.
    private val pkgLastTime = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        rebuildFromActiveNotifications()
        pushBroadcast()  // Sends BOTH broadcasts (FULL_SYNC + ALL_NOTIFS)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            rebuildFromActiveNotifications()
            pushBroadcast()  // Sends both: FULL_SYNC + ALL_NOTIFS
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("com.codecandy.blinkify.REFRESH_NOTIFS")
        // PRIVACY/SECURITY: RECEIVER_NOT_EXPORTED - only our own app can
        // trigger a refresh. No external apps can probe this receiver.
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    /**
     * PRIVACY: This is the ONLY place where new notifications are inspected.
     *
     * What IS read:
     *   - sbn.packageName - ONLY the app package name
     *   - sbn.postTime    - ONLY the timestamp (for ordering only)
     *
     * What is NEVER read:
     *   - sbn.notification.extras (title, text, subText, bigText, etc.)
     *   - sbn.notification.tickerText
     *   - sbn.notification.actions
     *   - sbn.notification.category
     *   - Any other notification payload
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == "com.android.systemui") return

        // Always add to "seen apps" (for the "Recent Apps" list in Settings).
        // This only stores the package name — no content is saved.
        AppPrefs.addSeen(this, pkg)

        // If app is not enabled by the user → ignore
        if (!AppPrefs.isEnabled(this, pkg)) {
            return
        }

        // Remember locally right away (Doze-safe)
        markPkg(pkg, sbn.postTime)
        pushBroadcast()

        // FIX: Follow-up syncs ONLY for non-AOD/Lockscreen modes.
        // In AOD/Lockscreen the follow-up syncs cause rebuild cascades:
        // Each sync changes desiredCount → hideDots()+showDots() → views
        // get torn down → flicker. In DOZE_SUSPEND these syncs don't
        // reliably get delivered anyway.
        val screenMode = AppPrefs.getScreenMode(this)
        val isAodOrLockscreen = (screenMode == "aod" || screenMode == "lockscreen")

        if (!isAodOrLockscreen) {
            // Two short follow-up syncs (Samsung/Doze) — only for ownaod/black
            handler.removeCallbacksAndMessages("late_sync_$pkg")
            handler.postAtTime({
                rebuildFromActiveNotifications()
                pushBroadcast()
            }, "late_sync_$pkg", SystemClock.uptimeMillis() + 350)

            handler.postAtTime({
                rebuildFromActiveNotifications()
                pushBroadcast()
            }, "late_sync_$pkg", SystemClock.uptimeMillis() + 1200)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == "com.android.systemui") return

        handler.postDelayed({
            rebuildFromActiveNotifications()
            pushBroadcast()
        }, 400)
    }

    /**
     * Marks a package as having a new notification.
     * PRIVACY: Only stores package name + timestamp. No content.
     */
    private fun markPkg(pkg: String, postTime: Long) {
        // To ensure "newest on the right":
        // remove first, then re-insert so it ends up at the end (right).
        pkgLastTime.remove(pkg)
        pkgLastTime[pkg] = postTime
    }

    /**
     * Rebuilds the internal package list from currently active notifications.
     * PRIVACY: Only reads packageName + postTime. Never touches notification content.
     */
    private fun rebuildFromActiveNotifications() {
        try {
            val list = activeNotifications ?: emptyArray()

            // 1) Only relevant notifications (enabled + not systemui)
            val filtered = list.filter { n ->
                val pkg = n.packageName
                pkg != "com.android.systemui" && AppPrefs.isEnabled(this, pkg)
            }

            // 2) Determine the NEWEST postTime per package
            val newestPerPkg = HashMap<String, Long>()
            for (n in filtered) {
                val pkg = n.packageName
                val t = n.postTime
                val prev = newestPerPkg[pkg]
                if (prev == null || t > prev) newestPerPkg[pkg] = t
            }

            // 3) Sort by time: oldest left, newest right
            val sorted = newestPerPkg.entries
                .sortedBy { it.value } // -> newest ends up on the far right
                .map { it.key }

            // 4) Transfer to our local map (stable order)
            pkgLastTime.clear()
            for (pkg in sorted) {
                pkgLastTime[pkg] = newestPerPkg[pkg] ?: 0L
            }

        } catch (e: Exception) {
            // PRIVACY: Only logs the generic exception message — never
            // notification content, package names, or user data.
            Log.e("Blinkify", "REBUILD error: ${e.message}")
        }
    }

    /**
     * Pushes the current package list to the rest of the app via broadcasts.
     * PRIVACY: Only package names are transmitted (no content, no timestamps,
     * no user data). Broadcasts use setPackage(packageName) to ensure they
     * stay within our own app.
     */
    private fun pushBroadcast() {
        // In OFF mode: no sync broadcasts at all
        if (AppPrefs.getScreenMode(this) == "off") return

        val currentNotifications = try { activeNotifications } catch (_: Exception) { null }

        val sortedPkgs: Array<String> = if (currentNotifications != null) {
            // Take the NEWEST time per package
            val newestPerPkg = HashMap<String, Long>()

            currentNotifications.forEach { n ->
                val pkg = n.packageName
                if (pkg == "com.android.systemui") return@forEach
                if (!AppPrefs.isEnabled(this, pkg)) return@forEach

                val t = n.postTime
                val prev = newestPerPkg[pkg]
                if (prev == null || t > prev) newestPerPkg[pkg] = t
            }

            newestPerPkg.entries
                .sortedBy { it.value } // oldest left, newest right
                .map { it.key }
                .toTypedArray()
        } else {
            // Doze fallback: stable order from our local map
            pkgLastTime.keys.toTypedArray()
        }

        // FULL_SYNC broadcast: enabled apps only. Stays within our own package.
        val intent = Intent("com.codecandy.blinkify.FULL_SYNC").apply {
            setPackage(packageName)
            putExtra("packages", sortedPkgs)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        sendBroadcast(intent)

        // Pure Mode: write directly into SharedState
        if (AppPrefs.isPureMode(this)) {
            PureNotifState.update(sortedPkgs.toList())
        }

        // Universal Mode SharedState — Doze-safe
        UniversalNotifState.update(sortedPkgs.toList())

        // Direct method call on the RenderEngine (Doze-safe).
        // onNotificationPosted() has special status as a
        // NotificationListenerService callback and is called even in DOZE.
        if (!AppPrefs.isPureMode(this)) {
            NotificationRenderEngine.instance?.handleFullSyncDirect(sortedPkgs)
        }

        // ALL_NOTIFS broadcast: for NotifBadges (all apps regardless of
        // "enabled"-toggle, but still no notification content).
        val allPkgs: Array<String> = if (currentNotifications != null) {
            val allNewestPerPkg = HashMap<String, Long>()

            currentNotifications.forEach { n ->
                val pkg = n.packageName
                if (pkg == "com.android.systemui") return@forEach
                // No isEnabled check here — NotifBadges show all apps

                val t = n.postTime
                val prev = allNewestPerPkg[pkg]
                if (prev == null || t > prev) allNewestPerPkg[pkg] = t
            }

            allNewestPerPkg.entries
                .sortedBy { it.value }
                .map { it.key }
                .toTypedArray()
        } else {
            emptyArray()
        }

        val allIntent = Intent("com.codecandy.blinkify.ALL_NOTIFS").apply {
            setPackage(packageName)
            putExtra("packages", allPkgs)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        sendBroadcast(allIntent)
    }
}
