package com.d4viddf.hyperbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [Tr1Action] Swipe-to-skip on the music island.
 *
 * HyperOS draws the Dynamic Island inside SystemUI, so a normal app can't receive touches on it.
 * An accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY) is drawn above the status bar, so we place
 * a transparent strip exactly over the island while music is active:
 *
 *  - horizontal swipe  -> next / previous track (MediaController transport controls)
 *  - tap / long press  -> overlay becomes untouchable and the same tap is re-injected with
 *                         dispatchGesture(), so the island still opens normally
 *  - swipe down        -> opens notifications (left half) or control center (right half),
 *                         so pulling down the status bar over the island keeps working
 *
 * The overlay is only touchable while a media session is playing/paused and the status bar is
 * visible (it steps aside in fullscreen video/games and in landscape).
 */
class MusicIslandGestureService : AccessibilityService() {

    companion object {
        private const val TAG = "MusicIslandGesture"
        @Volatile var instance: MusicIslandGestureService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** Called by the settings screen so changes apply immediately. */
        fun refreshFromSettings() {
            instance?.let { svc -> svc.mainHandler.post { svc.applyLayout(); svc.updateVisibility() } }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var mediaSessionManager: MediaSessionManager? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var mediaActive = false
    private var statusBarVisible = true
    private var replayingGesture = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { applyLayout(); updateVisibility() }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        mainHandler.post { trackControllers(controllers ?: emptyList()) }
    }

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = MusicIslandPrefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        createOverlay()
        startMediaTracking()
        Log.i(TAG, "Music island gesture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post { applyLayout(); updateVisibility() }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        instance = null
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        try { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controllerCallbacks.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        controllerCallbacks.clear()
        overlay?.let { if (attached) try { wm.removeView(it) } catch (_: Exception) {} }
        attached = false
        overlay = null
    }

    // =========================================================================
    //  MEDIA SESSION TRACKING
    // =========================================================================

    private fun listenerComponent() = ComponentName(this, NotificationReaderService::class.java)

    private fun startMediaTracking() {
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), mainHandler)
            trackControllers(mediaSessionManager?.getActiveSessions(listenerComponent()) ?: emptyList())
        } catch (e: SecurityException) {
            // Notification access not granted yet -> nothing to control
            Log.w(TAG, "No notification access, cannot read media sessions", e)
            mediaActive = false
            updateVisibility()
        }
    }

    private fun trackControllers(controllers: List<MediaController>) {
        controllerCallbacks.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        controllerCallbacks.clear()
        controllers.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = recomputeMediaActive()
                override fun onSessionDestroyed() = recomputeMediaActive()
            }
            c.registerCallback(cb, mainHandler)
            controllerCallbacks[c] = cb
        }
        recomputeMediaActive()
    }

    private fun isActiveState(c: MediaController): Boolean {
        return when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_CONNECTING -> true
            else -> false
        }
    }

    private fun recomputeMediaActive() {
        mediaActive = controllerCallbacks.keys.any { isActiveState(it) }
        updateVisibility()
    }

    /** Prefer the session that is actually playing, otherwise the most recent active one. */
    private fun currentController(): MediaController? {
        val list = try {
            mediaSessionManager?.getActiveSessions(listenerComponent()) ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { isActiveState(it) }
    }

    // =========================================================================
    //  OVERLAY
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val view = View(this)
        view.setOnTouchListener(IslandTouchHandler())
        view.setOnApplyWindowInsetsListener { v, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible != statusBarVisible) {
                statusBarVisible = visible
                mainHandler.post { updateVisibility() }
            }
            v.onApplyWindowInsets(insets)
        }

        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "HyperBridgeMusicIsland"
        }
        overlay = view
        params = lp
        applyLayout()
        try {
            wm.addView(view, lp)
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not add overlay", e)
        }
        updateVisibility()
    }

    /** Positions the touch strip over the island (centered on the camera cutout). */
    fun applyLayout() {
        val view = overlay ?: return
        val lp = params ?: return
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets
        val density = resources.displayMetrics.density

        val statusBarH = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val cutoutRect = insets.displayCutout?.boundingRectTop
        val centerX = if (cutoutRect != null && !cutoutRect.isEmpty) cutoutRect.centerX() else bounds.width() / 2

        val widthPct = MusicIslandPrefs.widthPercent(prefs).coerceIn(15, 90)
        val width = (bounds.width() * widthPct / 100f).roundToInt()
        val extraH = (MusicIslandPrefs.extraHeightDp(prefs) * density).roundToInt()
        val height = max(statusBarH, cutoutRect?.bottom ?: 0) + extraH
        val offsetX = (MusicIslandPrefs.offsetXDp(prefs) * density).roundToInt()

        lp.width = width
        lp.height = max(height, (24 * density).roundToInt())
        lp.x = (centerX - width / 2 + offsetX).coerceIn(0, max(0, bounds.width() - width))
        lp.y = 0

        if (attached) try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    fun updateVisibility() {
        val view = overlay ?: return
        val lp = params ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val enabled = MusicIslandPrefs.swipeEnabled(prefs)
        val debug = MusicIslandPrefs.showArea(prefs)

        // NOTE: we used to also require statusBarVisible here, but on HyperOS the inset visibility
        // reported to an accessibility overlay is unreliable (it kept the strip untouchable).
        val shouldCatchTouches = enabled && !replayingGesture && !landscape &&
                (mediaActive || debug)
        Log.d(TAG, "updateVisibility: catch=$shouldCatchTouches media=$mediaActive sb=$statusBarVisible land=$landscape")

        setTouchable(lp, shouldCatchTouches)
        // The view stays VISIBLE (so it keeps receiving window-inset updates); when it must not
        // interfere it is simply untouchable and transparent.
        view.setBackgroundColor(
            if (debug && enabled) Color.argb(110, 255, 64, 129) else Color.TRANSPARENT
        )
        if (attached) try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    private fun setTouchable(lp: WindowManager.LayoutParams, touchable: Boolean) {
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    // =========================================================================
    //  ACTIONS
    // =========================================================================

    private fun skip(next: Boolean) {
        val controller = currentController()
        if (controller == null) {
            Log.d(TAG, "No media controller to skip")
            return
        }
        val forward = if (MusicIslandPrefs.invertDirection(prefs)) !next else next
        if (forward) controller.transportControls.skipToNext() else controller.transportControls.skipToPrevious()
        overlay?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Log.i(TAG, "Skip ${if (forward) "next" else "previous"} -> ${controller.packageName}")
    }

    /**
     * Lets the touch fall through to SystemUI: make the overlay untouchable, replay the gesture at
     * the same screen position, then make it touchable again.
     */
    private fun replayTouch(x: Float, y: Float, durationMs: Long) {
        val lp = params ?: return
        replayingGesture = true
        setTouchable(lp, false)
        overlay?.let { v -> if (attached) try { wm.updateViewLayout(v, lp) } catch (_: Exception) {} }

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val restore = Runnable {
            replayingGesture = false
            updateVisibility()
        }
        // Small delay so the window manager has removed our touchable region before injecting
        mainHandler.postDelayed({
            val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed(restore, 350)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed(restore, 350)
                }
            }, mainHandler)
            if (!ok) mainHandler.post(restore)
        }, 40)
    }

    private fun pullDownShade(rawX: Float) {
        val screenW = wm.currentWindowMetrics.bounds.width()
        // HyperOS split shade: left half = notifications, right half = control center
        val action = if (rawX < screenW / 2f) AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS else AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        performGlobalAction(action)
    }

    // =========================================================================
    //  TOUCH HANDLING
    // =========================================================================

    private inner class IslandTouchHandler : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var downTime = 0L
        private var handled = false

        private val longPressMs = 450L
        private val longPress = Runnable {
            if (!handled) {
                handled = true
                // Re-inject a long press so HyperOS' own long-press behaviour still works
                replayTouch(downRawX, downRawY, 650)
            }
        }

        private fun restoreDebugColor(v: View) {
            v.setBackgroundColor(
                if (MusicIslandPrefs.showArea(prefs)) Color.argb(110, 255, 64, 129) else Color.TRANSPARENT
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val density = resources.displayMetrics.density
            val swipeThreshold = 36 * density
            val tapSlop = 10 * density

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (MusicIslandPrefs.showArea(prefs)) v.setBackgroundColor(Color.argb(140, 0, 200, 83))
                    downX = e.x; downY = e.y
                    downRawX = e.rawX; downRawY = e.rawY
                    downTime = e.eventTime
                    handled = false
                    mainHandler.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (handled) return true
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > tapSlop || abs(dy) > tapSlop) mainHandler.removeCallbacks(longPress)

                    if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.3f) {
                        handled = true
                        // finger moves left -> next track, right -> previous track
                        skip(next = dx < 0)
                    } else if (dy > swipeThreshold && dy > abs(dx) * 1.3f) {
                        handled = true
                        pullDownShade(downRawX)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    restoreDebugColor(v)
                    mainHandler.removeCallbacks(longPress)
                    if (!handled) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dx) < tapSlop && abs(dy) < tapSlop && e.eventTime - downTime < longPressMs) {
                            handled = true
                            replayTouch(downRawX, downRawY, 60)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    restoreDebugColor(v)
                    Log.w(TAG, "Touch CANCELLED - another window (SystemUI?) stole the gesture")
                    mainHandler.removeCallbacks(longPress)
                }
            }
            return true
        }
    }
}

/** Small SharedPreferences wrapper for the music island gesture settings. */
object MusicIslandPrefs {
    private const val FILE = "music_island_prefs"
    const val KEY_SWIPE = "swipe_enabled"
    const val KEY_INVERT = "invert_direction"
    const val KEY_WIDTH = "width_percent"
    const val KEY_EXTRA_H = "extra_height_dp"
    const val KEY_OFFSET_X = "offset_x_dp"
    const val KEY_SHOW_AREA = "show_area"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun swipeEnabled(p: SharedPreferences) = p.getBoolean(KEY_SWIPE, true)
    fun invertDirection(p: SharedPreferences) = p.getBoolean(KEY_INVERT, false)
    fun widthPercent(p: SharedPreferences) = p.getInt(KEY_WIDTH, 50)
    fun extraHeightDp(p: SharedPreferences) = p.getInt(KEY_EXTRA_H, 6)
    fun offsetXDp(p: SharedPreferences) = p.getInt(KEY_OFFSET_X, 0)
    fun showArea(p: SharedPreferences) = p.getBoolean(KEY_SHOW_AREA, false)
}
