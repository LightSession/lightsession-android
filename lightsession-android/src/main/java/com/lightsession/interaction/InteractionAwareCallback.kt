package com.lightsession.interaction

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.lightsession.mapper.ScreenMapperIntegration
import com.lightsession.replay.ScreenDrawing
import com.lightsession.replay.ScreenDrawing.Companion.ScalePresets
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
) : GestureDetector.SimpleOnGestureListener(), Window.Callback {

    companion object {
        private const val MIN_DISTANCE = 10.0f // Minimum distance for a touch movement to be considered a swipe point
    }

    private val gestureDetector: GestureDetector = GestureDetector(activity, this)
    private val interactionHistory: MutableList<UserInteraction> = mutableListOf()
    private val currentInteractionPoints: MutableList<UserInteraction.InteractionPoint> = mutableListOf()

    private var gestureStartTime: Long = 0
    private var isTrackingGesture: Boolean = false

    init {
        val activityName = activity.javaClass.simpleName
        Log.d("UICallback", "Initialized for activity: $activityName")
    }

    private fun getScaledCoordinates(originalX: Float, originalY: Float): Pair<Float, Float> {
        return try {
            // Pega o scale factor atual do ScreenDrawing
            val screenDrawing = ScreenDrawing()
            val scaleFactor = ScalePresets.ORIGINAL

            Log.d("CoordinateScale", "Scale factor: $scaleFactor | Original: ($originalX, $originalY)")

            val scaledX = originalX * scaleFactor
            val scaledY = originalY * scaleFactor

            Log.d("CoordinateScale", "Scaled: ($scaledX, $scaledY)")

            Pair(scaledX, scaledY)
        } catch (e: Exception) {
            Log.e("InteractionCallback", "Error scaling coordinates", e)
            // Fallback para coordenadas originais em caso de erro
            Pair(originalX, originalY)
        }
    }


    /**
     * Intercepts all touch events dispatched to the window.
     * This is the core method for tracking user interactions.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Log.d("ActionRegistered", "Gesture: BOTAO PRESSIONADO")
                currentInteractionPoints.clear()
                isTrackingGesture = true
                gestureStartTime = System.currentTimeMillis()

                val (scaledX, scaledY) = getScaledCoordinates(event.x, event.y)
                currentInteractionPoints.add(
                    UserInteraction.InteractionPoint(
                        scaledX, // ← Agora usa coordenadas escaladas
                        scaledY, // ← Agora usa coordenadas escaladas
                        System.currentTimeMillis()
                    )
                )
                Log.d("ActionRegistered", "Original: ${event.x} ${event.y} | Scaled: $scaledX $scaledY")
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTrackingGesture) {
                    val (scaledX, scaledY) = getScaledCoordinates(event.x, event.y)

                    if (currentInteractionPoints.isEmpty() ||
                        distance(scaledX, scaledY, currentInteractionPoints.last()) > MIN_DISTANCE
                    ) {
                        currentInteractionPoints.add(
                            UserInteraction.InteractionPoint(
                                scaledX, // ← Coordenadas escaladas
                                scaledY, // ← Coordenadas escaladas
                                System.currentTimeMillis()
                            )
                        )
                        Log.d("ActionRegistered", "Move - Original: ${event.x} ${event.y} | Scaled: $scaledX $scaledY")
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                Log.d("ActionRegistered", "Gesture: BOTAO DESPRESSIONADO")
                if (isTrackingGesture) {
                    isTrackingGesture = false
                    val gestureEndTime = System.currentTimeMillis()

                    val (scaledX, scaledY) = getScaledCoordinates(event.x, event.y)

                    if (currentInteractionPoints.isEmpty() ||
                        distance(scaledX, scaledY, currentInteractionPoints.last()) > MIN_DISTANCE
                    ) {
                        currentInteractionPoints.add(
                            UserInteraction.InteractionPoint(
                                scaledX, // ← Coordenadas escaladas
                                scaledY, // ← Coordenadas escaladas
                                System.currentTimeMillis()
                            )
                        )
                    }

                    val type = if (currentInteractionPoints.size == 1) {
                        UserInteraction.InteractionType.TAP
                    } else {
                        UserInteraction.InteractionType.SWIPE
                    }

                    val interaction = UserInteraction(
                        type,
                        currentInteractionPoints, // ← Agora contém coordenadas escaladas
                        gestureStartTime,
                        gestureEndTime,
                    )

                    interactionHistory.add(interaction)

                    // IMPORTANTE: Para findViewAtCoordinates, ainda usar coordenadas originais
                    // porque estamos procurando elementos na tela real, não na screenshot
                    if (type == UserInteraction.InteractionType.TAP) {
                        val touchX = event.x.toInt() // ← Coordenadas originais para encontrar a view
                        val touchY = event.y.toInt() // ← Coordenadas originais para encontrar a view

                        val clickedView = findViewAtCoordinates(activity.window.decorView, touchX, touchY)

                        if (clickedView != null) {
                            val viewDescription = getViewDescription(clickedView)
                            Log.d("LightSession", "Detected TAP on View: $viewDescription")

                            val eventProperties = mutableMapOf<String, Any>(
                                "ui_element" to viewDescription,
                                "type" to clickedView.javaClass.simpleName,
                                "resourceId" to (getResourceId(clickedView) ?: ""),
                                "screen" to activity.javaClass.simpleName,
                                "x" to touchX, // Coordenadas originais para identificação
                                "y" to touchY, // Coordenadas originais para identificação
                                // Opcionalmente, incluir também as escaladas para debug
                                "x_scaled" to scaledX,
                                "y_scaled" to scaledY
                            )

                            when (clickedView) {
                                is EditText -> {
                                    eventProperties["interaction_type"] = "input_focus"
                                }
                                else -> {
                                    eventProperties["interaction_type"] = "click"
                                }
                            }
                        } else {
                            Log.d("LightSession", "Detected TAP, but no specific View found at coordinates.")
                        }
                    }

                    // Enviar dados de interação (com coordenadas escaladas para o replay visual)
                    val interactionData = JSONObject().apply {
                        put("type", type.name)
                        put("points", interactionPointsToJson(currentInteractionPoints)) // ← Coordenadas escaladas
                        put("start_time", gestureStartTime)
                        put("end_time", gestureEndTime)
                    }.toString()

                    try {
                        val sessionDataManager = ScreenMapperIntegration.getInstance()
                            .getSessionDataManager()
                        sessionDataManager?.addInteractionFromJson(interactionData)
                    } catch (e: Exception) {
                        Log.e("InteractionCallback", "Error sending interaction to SessionDataManager", e)
                    }
                    Log.d("interactionData", "Sending USER_INTERACTION breadcrumb: $interactionData")
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isTrackingGesture = false
                currentInteractionPoints.clear()
            }
        }
        return originalCallback.dispatchTouchEvent(event)
    }

    /**
     * Recursively finds the deepest visible View at the given screen coordinates.
     * This is crucial for identifying which specific UI element was interacted with.
     */
    private fun findViewAtCoordinates(parent: View?, x: Int, y: Int): View? {
        if (parent == null) return null

        val hitRect = Rect()
        parent.getGlobalVisibleRect(hitRect) // Get the global visible rectangle of the parent view

        // Check if the parent itself contains the coordinates
        if (hitRect.contains(x, y)) {
            // If the parent is a ViewGroup, recursively search its children
            if (parent is ViewGroup) {
                // Iterate children in reverse order (from last drawn to first)
                // to find the topmost view at the coordinates.
                for (i in parent.childCount - 1 downTo 0) {
                    val child = parent.getChildAt(i)
                    // Check if the child is visible and potentially contains the coordinates
                    if (child.visibility == View.VISIBLE) {
                        val found = findViewAtCoordinates(child, x, y) // Recursive call
                        if (found != null) {
                            return found // Return the innermost view found
                        }
                    }
                }
            }
            // If no child was found at the coordinates, or if it's not a ViewGroup,
            // check if the parent itself is interactive (e.g., clickable, has listeners, or an ID).
            // This heuristic helps identify elements the user would typically interact with.
            if (parent.isClickable || parent.hasOnClickListeners() || parent.id != View.NO_ID) {
                return parent
            }
        }
        return null // No interactive View found at the coordinates
    }

    /**
     * Generates a descriptive string for a given View, useful for logging and analysis.
     * It includes the View type, text content (for Button/TextView), and resource ID.
     */
    private fun getViewDescription(view: View): String {
        val description = StringBuilder()

        when (view) {
            is Button -> {
                description.append("Button: ")
                when {
                    !view.text.isNullOrEmpty() -> {
                        description.append("'${view.text}'")
                    }
                    view.contentDescription != null -> {
                        description.append("'${view.contentDescription}'")
                    }
                    else -> {
                        description.append("no_text")
                    }
                }
            }
            is TextView -> { // Catches general TextViews, including EditText
                description.append("TextView: ")
                when {
                    !view.text.isNullOrEmpty() -> {
                        // For EditTexts, you might want to mask actual text for privacy
                        description.append("text_present")
                    }
                    !view.hint.isNullOrEmpty() -> {
                        description.append("hint: '${view.hint}'")
                    }
                    else -> {
                        description.append("no_text")
                    }
                }
            }
            is ViewGroup -> {
                description.append("ViewGroup (${view.javaClass.simpleName})")
            }
            else -> {
                description.append("View (${view.javaClass.simpleName})")
            }
        }

        getResourceId(view)?.let { resourceId ->
            description.append(" [ID: $resourceId]")
        }

        return description.toString()
    }

    /**
     * Retrieves the resource entry name (e.g., "my_button_id") for a given View's ID.
     */
    private fun getResourceId(view: View): String? {
        return try {
            if (view.id != View.NO_ID) {
                view.resources.getResourceEntryName(view.id)
            } else null
        } catch (e: Exception) {
            // Ignore, the ID might not be a public resource or invalid
            Log.e("LightSession", "Error getting resource ID: ${e.message}")
            null
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
            Log.e("InteractionCallback", "Error getting current screen", e)
            null
        }

        points.forEach { point ->
            val jsonObject = JSONObject()
            jsonObject.put("x", point.x)
            jsonObject.put("y", point.y)
            jsonObject.put("timestamp", point.timestamp)
            jsonObject.put("time_since_start", point.timeSinceStart)

            // Add current screen information to each point
            currentScreen?.let { screen ->
                jsonObject.put("screen", screen)
            }

            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    // --- GestureDetector.SimpleOnGestureListener Callbacks ---

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        // Log coordinates similar to the Java version's extracted method
        Log.d("ActionRegistered", "Gesture: ${e.x} ${e.y}")
        // This callback is triggered by GestureDetector for single taps.
        // Our logic in dispatchTouchEvent already handles TAP detection based on ACTION_UP.
        // Returning true here indicates the event was consumed by the GestureDetector.
        return true
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        if (isTrackingGesture && e2 != null) {
            if (currentInteractionPoints.isEmpty() ||
                distance(e2.x, e2.y, currentInteractionPoints.last()) > MIN_DISTANCE
            ) {
                // Log coordinates for scroll events
                Log.d("ActionRegistered", "Gesture: ${e2.x} ${e2.y}")
            }
        }
        // This callback is triggered by GestureDetector for scroll events.
        // Our logic in ACTION_MOVE within dispatchTouchEvent already adds points for SWIPEs.
        // Keeping this method returning true ensures GestureDetector correctly recognizes scrolls.
        return true
    }

    /**
     * Calculates the Euclidean distance between two points.
     */
    private fun distance(x1: Float, y1: Float, point: UserInteraction.InteractionPoint): Float {
        return sqrt((x1 - point.x).pow(2) + (y1 - point.y).pow(2))
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
            val timeSinceStart: Long = timestamp - 123231
        )
    }
}