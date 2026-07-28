package com.lightsession.interaction

import android.app.Activity
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import com.lightsession.mapper.ScreenMapperIntegration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Custom Window.Callback implementation to intercept touch events
 * and track user interactions for session replay and automatic UI element click tracking.
 */
class InteractionAwareCallback(
    private val originalCallback: Window.Callback,
    private val activity: Activity,
) : Window.Callback {

    companion object {
        private const val MIN_DISTANCE = 10.0f // Minimum distance for a touch movement to be considered a swipe point

        /**
         * One tag for the file.
         *
         * It used to log under six — `UICallback`, `CoordinateScale`, `ActionRegistered`,
         * `LightSession`, `InteractionCallback`, `interactionData` — which meant no single
         * logcat filter showed what this class was doing, and none of them identified the
         * SDK to whoever was reading a host app's log.
         */
        private const val TAG = "LightSession.Touch"
    }

    private val currentInteractionPoints: MutableList<UserInteraction.InteractionPoint> = mutableListOf()

    private var gestureStartTime: Long = 0
    private var isTrackingGesture: Boolean = false

    init {
        Log.d(TAG, "tracking touches in ${activity.javaClass.simpleName}")
    }

    /**
     * Intercepts all touch events dispatched to the window.
     * This is the core method for tracking user interactions.
     */
    /**
     * Tracks the gesture, then hands the event on — and never lets the tracking stop the
     * hand-off.
     *
     * Everything in [track] is the SDK's own business, and it all used to run unguarded on
     * the way to `originalCallback.dispatchTouchEvent`. Two consequences, both bad and
     * neither obvious: an exception went straight into the host app's touch dispatch and
     * crashed it, and because the delegation is the last line, the app never received the
     * touch at all — so a bug here presented as an unresponsive screen a moment before the
     * crash.
     *
     * It is reachable. `JSONObject.put(String, Double)` throws on NaN or infinity, and a
     * `MotionEvent` can carry either. An SDK sitting in the touch path has to be inert on
     * failure, so the whole of it is caught and logged, once, and the event goes on
     * regardless.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        try {
            track(event)
        } catch (error: Throwable) {
            Log.e(TAG, "interaction tracking failed; the touch is unaffected", error)
        }
        return originalCallback.dispatchTouchEvent(event)
    }

    private fun track(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentInteractionPoints.clear()
                isTrackingGesture = true
                gestureStartTime = System.currentTimeMillis()

                currentInteractionPoints.add(
                    UserInteraction.InteractionPoint(event.x, event.y, System.currentTimeMillis()),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTrackingGesture && farEnoughFromLast(event)) {
                    currentInteractionPoints.add(
                        UserInteraction.InteractionPoint(event.x, event.y, System.currentTimeMillis()),
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isTrackingGesture) {
                    isTrackingGesture = false
                    val gestureEndTime = System.currentTimeMillis()

                    if (farEnoughFromLast(event)) {
                        currentInteractionPoints.add(
                            UserInteraction.InteractionPoint(
                                event.x,
                                event.y,
                                System.currentTimeMillis(),
                            ),
                        )
                    }

                    val type = if (currentInteractionPoints.size == 1) {
                        UserInteraction.InteractionType.TAP
                    } else {
                        UserInteraction.InteractionType.SWIPE
                    }


                    val interactionData = JSONObject().apply {
                        put("type", type.name)
                        put("points", interactionPointsToJson(currentInteractionPoints)) // ← Coordenadas escaladas
                        put("start_time", gestureStartTime)
                        put("end_time", gestureEndTime)
                        try {
                            val screenId = ScreenMapperIntegration.getInstance().getCurrentScreenId()
                            if (screenId != null) {
                                put("screen_id", screenId)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "could not read the current screen id", e)
                        }
                    }.toString()

                    try {
                        val sessionDataManager = ScreenMapperIntegration.getInstance()
                            .getSessionDataManager()
                        sessionDataManager?.addInteractionFromJson(interactionData)
                    } catch (e: Exception) {
                        Log.w(TAG, "could not hand the interaction to the session", e)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isTrackingGesture = false
                currentInteractionPoints.clear()
            }
        }
    }

    /**
     * Converts a list of UserInteraction.InteractionPoint objects into a JSON string
     * representing the path of the interaction, including current screen information.
     */
    private fun interactionPointsToJson(points: List<UserInteraction.InteractionPoint>): JSONArray {
        val jsonArray = JSONArray()

        // Get current screen from ScreenMapper
        val currentScreen = try {
            ScreenMapperIntegration.getInstance().getCurrentScreen()
        } catch (e: Exception) {
            Log.w(TAG, "could not read the current screen name", e)
            null
        }

        // Measured from the first point, which is the one recorded at ACTION_DOWN and so is
        // the start of the gesture. It used to be a default on the data class,
        // `timestamp - 123231`, which no point could compute correctly because a point does
        // not know when its gesture began — so every interaction shipped a `time_since_start`
        // of about fifty-five years. Nothing on the server reads the field, which is why it
        // went unnoticed rather than why it was harmless.
        val gestureStart = points.firstOrNull()?.timestamp ?: 0L

        points.forEach { point ->
            val jsonObject = JSONObject()
            jsonObject.put("x", point.x)
            jsonObject.put("y", point.y)
            jsonObject.put("timestamp", point.timestamp)
            jsonObject.put("time_since_start", point.timestamp - gestureStart)

            // Add current screen information to each point
            currentScreen?.let { screen ->
                jsonObject.put("screen", screen)
            }

            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    /**
     * Whether this event is far enough from the last recorded point to be worth keeping.
     *
     * A drag delivers a MOVE for every pixel the finger travels, and a replay does not need
     * them: [MIN_DISTANCE] apart is enough to draw the path, and the points are what the
     * interaction payload is made of.
     */
    private fun farEnoughFromLast(event: MotionEvent): Boolean {
        val last = currentInteractionPoints.lastOrNull() ?: return true
        return sqrt((event.x - last.x).pow(2) + (event.y - last.y).pow(2)) > MIN_DISTANCE
    }

    // --- Window.Callback Delegation Methods ---
    // These methods simply delegate the calls to the original Window.Callback
    // to ensure the application's default behavior is preserved.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = originalCallback.dispatchKeyEvent(event)
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = originalCallback.dispatchKeyShortcutEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = originalCallback.dispatchTrackballEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = originalCallback.dispatchGenericMotionEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean = originalCallback.dispatchPopulateAccessibilityEvent(event)
    override fun onCreatePanelView(featureId: Int): View? = originalCallback.onCreatePanelView(featureId)
    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = originalCallback.onCreatePanelMenu(featureId, menu)
    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = originalCallback.onPreparePanel(featureId, view, menu)
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = originalCallback.onMenuOpened(featureId, menu)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = originalCallback.onMenuItemSelected(featureId, item)
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = originalCallback.onWindowAttributesChanged(attrs)
    override fun onContentChanged() = originalCallback.onContentChanged()
    override fun onWindowFocusChanged(hasFocus: Boolean) = originalCallback.onWindowFocusChanged(hasFocus)
    override fun onAttachedToWindow() = originalCallback.onAttachedToWindow()
    override fun onDetachedFromWindow() = originalCallback.onDetachedFromWindow()
    override fun onPanelClosed(featureId: Int, menu: Menu) = originalCallback.onPanelClosed(featureId, menu)
    override fun onSearchRequested(): Boolean = originalCallback.onSearchRequested()
    override fun onSearchRequested(searchEvent: SearchEvent): Boolean = originalCallback.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? = originalCallback.onWindowStartingActionMode(callback)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = originalCallback.onWindowStartingActionMode(callback, type)
    override fun onActionModeStarted(mode: ActionMode) = originalCallback.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode) = originalCallback.onActionModeFinished(mode)

    /**
     * Represents a single user interaction (tap or swipe) with its properties.
     */
    data class UserInteraction(
        val type: InteractionType,
        val points: List<InteractionPoint>,
        val startTimestamp: Long,
        val endTimestamp: Long,
    ) {
        /**
         * Defines the type of user interaction.
         */
        enum class InteractionType {
            TAP,
            SWIPE
        }

        /**
         * Represents a single point within a user interaction, including coordinates and timestamps.
         */
        data class InteractionPoint(
            val x: Float,
            val y: Float,
            val timestamp: Long,
        )
    }
}