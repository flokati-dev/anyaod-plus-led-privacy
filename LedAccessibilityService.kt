package com.codecandy.blinkify

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * LedAccessibilityService - Accessibility Service for AnyAOD+ LED
 *
 * PRIVACY NOTICE
 *
 * This service uses Accessibility permissions EXCLUSIVELY for:
 *   1. Detecting Screen On/Off events (to show/hide overlays)
 *   2. Detecting user activity (to hide overlays when user becomes active)
 *   3. Detecting phone apps (to hide overlays during calls)
 *
 * The service reads:
 *   - ONLY the package name of the active app (e.g. "com.whatsapp")
 *   - ONLY event type filtered to TYPE_WINDOW_STATE_CHANGED
 *
 * The service does NOT read:
 *   - NO screen content
 *   - NO texts
 *   - NO passwords
 *   - NO keyboard input
 *   - NO content descriptions
 *
 * The complete rendering logic lives in NotificationRenderEngine.kt
 */
class LedAccessibilityService : AccessibilityService() {

    companion object {
        // Shared state (readable by other Activities)
        @Volatile var userIsActiveOnScreen = false
        @Volatile var isCallUiVisible = false
        @Volatile var lastScreenOffTimeMs = 0L
        @Volatile var isInConfigurationChange = false

        @Volatile var isRunning = false

        // Intent Actions (for broadcast communication)
        const val EXTRA_COLOR = "color"
        const val ACTION_SET_ACTIVE = "com.codecandy.blinkify.SET_ACTIVE"
        const val ACTION_STOP_TEST = "com.codecandy.blinkify.STOP_TEST"
        const val ACTION_TEST_DOT = "com.codecandy.blinkify.TEST_DOT"
        const val ACTION_UPDATE_DOT_LAYOUT = "com.codecandy.blinkify.UPDATE_DOT_LAYOUT"
        const val ACTION_DISMISS_BLACK = "com.codecandy.blinkify.DISMISS_BLACK"
        const val ACTION_SET_TEST_ALPHA = "com.codecandy.blinkify.SET_TEST_ALPHA"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var renderEngine: NotificationRenderEngine

    // BroadcastReceiver for commands from Activities
    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                "com.codecandy.blinkify.FULL_SYNC" -> {
                    val pkgs = intent.getStringArrayExtra("packages")
                    renderEngine.handleFullSync(pkgs)
                }

                ACTION_TEST_DOT -> {
                    renderEngine.handleTestMode(intent)
                }

                ACTION_STOP_TEST -> {
                    renderEngine.handleStopTest()
                }

                ACTION_UPDATE_DOT_LAYOUT -> {
                    renderEngine.handleUpdateLayout(intent)
                }

                ACTION_SET_ACTIVE -> {
                    renderEngine.handleSetActive(intent)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    userIsActiveOnScreen = false
                    lastScreenOffTimeMs = System.currentTimeMillis()
                    renderEngine.handleScreenOff()
                }

                Intent.ACTION_SCREEN_ON -> {
                    // Marker for later use
                }

                Intent.ACTION_USER_PRESENT -> {
                    userIsActiveOnScreen = true
                    if (!AppPrefs.isPowerButtonAlwaysOn(this@LedAccessibilityService)) {
                        renderEngine.handleUserPresent()
                    } else {
                        handler.postDelayed({ renderEngine.applyState() }, 300L)
                    }
                }

                ACTION_DISMISS_BLACK -> {
                    renderEngine.hideBlackOverlay()
                }

                DotEngineService.ACTION_TICK -> {
                    renderEngine.handleEngineTick()
                }

                ACTION_SET_TEST_ALPHA -> {
                    val alpha = intent.getFloatExtra("alpha", 1f)
                    renderEngine.setTestAlpha(alpha)
                }
            }
        }
    }

    override fun onServiceConnected() {
        isRunning = true
        super.onServiceConnected()

        // Initialize rendering engine
        renderEngine = NotificationRenderEngine(this, handler)

        // Register BroadcastReceiver
        val filter = IntentFilter().apply {
            addAction("com.codecandy.blinkify.FULL_SYNC")
            addAction("com.codecandy.blinkify.REQUEST_SYNC")
            addAction(ACTION_SET_ACTIVE)
            addAction(ACTION_STOP_TEST)
            addAction(ACTION_TEST_DOT)
            addAction(ACTION_UPDATE_DOT_LAYOUT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_DISMISS_BLACK)
            addAction(DotEngineService.ACTION_TICK)
            addAction(ACTION_SET_TEST_ALPHA)
        }

        // PRIVACY/SECURITY: RECEIVER_NOT_EXPORTED means only our own app
        // can send broadcasts to this receiver. System broadcasts like
        // SCREEN_ON/SCREEN_OFF/USER_PRESENT still work because Android
        // treats the system as same-app for these broadcasts.
        ContextCompat.registerReceiver(this, cmdReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Apply initial state
        handler.post { renderEngine.applyState() }
    }

    /**
     * PRIVACY: This is the ONLY place where accessibility events are processed.
     *
     * What IS read:
     *   - event.packageName (e.g. "com.whatsapp") - ONLY the app package name
     *   - event.eventType - ONLY TYPE_WINDOW_STATE_CHANGED (window switched)
     *
     * What is NEVER read:
     *   - event.getText()            - NO texts
     *   - event.getSource()          - NO screen content / view hierarchy
     *   - event.contentDescription   - NO content descriptions
     *   - No other event types       - NO keyboard input, NO text selection,
     *                                  NO scrolling, NO clicks, NO focus changes
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        // ONLY react to window-state-changes (app switches)
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // ONLY read the package name (e.g. "com.whatsapp")
        val pkg = e.packageName?.toString() ?: return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val screenMode = AppPrefs.getScreenMode(this)

        val timeSinceScreenOff = System.currentTimeMillis() - lastScreenOffTimeMs
        val isAodStartPhase = timeSinceScreenOff < 1000L

        // User-activity detection (to hide overlays when user becomes active)
        if (!renderEngine.isTestMode) {
            if (pm.isInteractive && !km.isKeyguardLocked && !isAodStartPhase && !isInConfigurationChange &&
                (screenMode == "ownaod" || screenMode == "black" || screenMode == "aod" || screenMode == "lockscreen")) {

                if (pkg != "com.android.systemui" && pkg != packageName) {
                    if (!userIsActiveOnScreen) {
                        userIsActiveOnScreen = true
                        renderEngine.handleUserPresent()
                    }
                }
            }
        }

        // Call-UI handling (hide overlays during calls)
        if (isInCallUiPkg(pkg)) {
            if (!isCallUiVisible) {
                isCallUiVisible = true
                sendBroadcast(Intent(ACTION_DISMISS_BLACK).apply { setPackage(packageName) })
                sendBroadcast(Intent(OwnAodActivity.ACTION_DISMISS_OWNAOD).apply { setPackage(packageName) })
                renderEngine.hideDots()
                renderEngine.applyState()
            }
        } else {
            if (isCallUiVisible) {
                isCallUiVisible = false
                renderEngine.applyState()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        renderEngine.cleanup()
        try { unregisterReceiver(cmdReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /**
     * Checks if a package name belongs to a phone/in-call app.
     * Used to hide overlays during calls so they don't cover the call UI.
     *
     * PRIVACY: Only compares against a hardcoded list of known in-call
     * packages. No data is stored, logged, or transmitted.
     */
    private fun isInCallUiPkg(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg == "com.samsung.android.incallui" ||
                pkg == "com.android.incallui" ||
                pkg == "com.miui.incallui"
    }
}
