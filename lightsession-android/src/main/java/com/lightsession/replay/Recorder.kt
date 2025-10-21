package com.lightsession.replay

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.lightsession.replay.ScreenDrawing
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.windowAttachCount
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class Recorder {

    companion object {
        // signal of bytes to indicate a repeated frame
        val REPEATED_FRAME_SIGNAL = byteArrayOf(0x52, 0x50, 0x54, 0x44) // "RPTD" in ASCII
    }

    // Instância da classe utilitária para captura de tela
    private val screenDrawing = ScreenDrawing()

    // Flag to indicate if the screen content has changed
    private val isScreenContentChanged = AtomicBoolean(false)
    private var isFirstCapture = true

    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor(
            LightSessionThreadFactory("LightSession-Scheduler", enableCounter = false)
        )
    }

    private var scheduledFuture: ScheduledFuture<*>? = null
    private var mainHandler: MainLooperHandler? = null
    private var contextRef: WeakReference<Context>? = null

    /**
     * Thread-SAFE Map that stores all views being monitored.
     * Key: Single View ID (System.IDENTITY_HASH_CODE)
     * Value: ViewInfo with weak reference and specific listener
     *
     * Concurrent hashmap is used because:
     * - views can be added/removed from different threads
     * - prevents endront modification exception during iteration
     * - Performance higher than Collections.SynchronizedMap()
     */
    private val monitoredViews = ConcurrentHashMap<Int, ViewInfo>()

    /**
     * Set of weak references for the main root views.
     * Used to track the main views of hierarchy and facilitate cleaning.
     *
     * Weak reference prevents memory leaks if view is removed
     * but still referenced here.
     */
    private val rootViews = mutableSetOf<WeakReference<View>>()

    /**
     * Data class to store information from a monitored view.
     *
     * @param viewRef weak reference for View (avoids memory leak)
     * @param drawListener Listener specific of this view to detect changes
     * @param isActive Flag to mark if monitoring is active (future use)
     */
    private data class ViewInfo(
        val viewRef: WeakReference<View>,
        val drawListener: ViewTreeObserver.OnDrawListener,
        val isActive: Boolean = true
    )

    /**
     * Starts the periodic screen capture system with smart detection of change.
     *
     * @param context application context (used to access windimanager)
     * @param delayMillis Interval between Millys segund Verifications
     * @param scaleFactor image scale factor (0.1 to 1.0)
     * @para onBitmapBytesReady callback called when new capture is ready
     *
     * Operation:
     * 1. Install views monitoring (detects visual changes)
     * 2. For any previous capture task (avoid overlap)
     * 3. Configures Unique Thread Performer for Background Capture
     * 4. Agenda periodic checks based on timer
     * 5. With each check, decides whether to capture new image or send repetition signal
     *
     * Decision logic:
     * - If first capture or screen has changed: Capture new image
     * - If not: Send repeated_frame_signal (save processing and bandwidth)
     *
     * ThreadSafety:
     * - Captures run in background thread (does not block UI)
     * - Results are posted back to UI Thread via Handler
     * - Atomic boolean used for change-sofe
     *
     * Why ScheduleDexecuterservice:
     * - More efficient than Handler.postdelayeed for repetitive tasks
     * - Better control of Life Cycle (Shutdown Graceful)
     * - Dedicated thread does not impact UI thread
     * - ScheduleWithfixedlaylandguarantees consistent interval between executions
     */
    fun capture(
        context: Context,
        delayMillis: Long,
        scaleFactor: Float = ScreenDrawing.Companion.ScalePresets.MEDIUM_QUALITY,
        onBitmapBytesReady: (ByteArray?) -> Unit
    ) {

        // Install monitoring to detect change changes
        installViewMonitoring()
        // Defines the scale factor in the utilitarian class
        screenDrawing.setGlobalScaleFactor(scaleFactor)
        // Stop any previous task to avoid overlap
        // configure references and handlers
        contextRef = WeakReference(context)
        mainHandler = MainLooperHandler() // to return results to UI thread
        // reset state flags
        isFirstCapture = true
        isScreenContentChanged.set(true) // Forcing first capture

        scheduledFuture = scheduler.scheduleWithFixedDelay({
            try {
                val currentContext = contextRef?.get()
                if (currentContext == null) {
                    Log.w("LightSession", "Context became null, stopping capture task")
                    return@scheduleWithFixedDelay
                }

                // Clean dead views periodically (maintenance)
                cleanupDeadViews()

                // decide if you need to capture new image
                if (isFirstCapture || isScreenContentChanged.getAndSet(false)) {
                    // screen changed or is first capture - capture new image
                    isFirstCapture = false
                    Log.d("LightSession", "Capturing new frame (content changed or first capture)")

                    mainHandler?.post {
                        val composedBitmapBytes = screenDrawing.captureCurrentScreenOptimized(scaleFactor)
                        onBitmapBytesReady(composedBitmapBytes)
                    }
                } else {
                    // Screen has not changed - Send repeated frame signal
                    Log.d("LightSession", "Repeated frame detected, sending signal")
                    mainHandler?.post {
                        onBitmapBytesReady(REPEATED_FRAME_SIGNAL)
                    }
                }
            } catch (e: Exception) {
                Log.e("LightSession", "Unexpected error in capture task", e)
                // Continue execution instead of crashing the scheduler
            }
        }, 100L, delayMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Factory Method to create a specific Draw listener for a View.
     *
     * Each view needs your own listener because:
     * - Allows you to identify which specific View has changed (useful for debug)
     * - Avoid conflicts when multiple views are modified simultaneously
     * - Facilitates selective removal of listeners
     *
     * @param viewId ID from View to Logging and Debug
     * @return Listener that marks content change when view is redesigned
     */
    private fun createDrawListener(viewId: Int): ViewTreeObserver.OnDrawListener {
        return ViewTreeObserver.OnDrawListener {
            // mark that the content has changed using atomicboolean to thread-safety
            isScreenContentChanged.set(true)
            Log.d("LightSessionCore", "Screen content changed detected from view: $viewId")
        }
    }

    /**
     * Install the views monitoring system in the application.
     *
     * This method is responsible for:
     * 1. Clean any previous monitoring (avoids duplication)
     * 2. Record all existing views at the moment
     * 3. Configure listener to detect new views that appear
     *
     * It is called automatically when capture() is started.
     *
     * Why do we need this:
     * - Android creates/destroys dynamically views (activities, fragments, dialogs)
     * - We need to detect changes in any visible view on the screen
     * - views may appear/disappear without warning (pop-ups, keyboard, etc.)
     */
    private fun installViewMonitoring() {
        try {
            // Clear previous monitoring to avoid duplicate listeners
            cleanupViewMonitoring()

            // Add all views that already exist at the moment
            Curtains.rootViews.forEach { view ->
                addViewToMonitoring(view, true)
            }

            Curtains.onRootViewsChangedListeners += onRootViewsChangedListener
            Log.d("LightSessionCore", "View monitoring installed with ${monitoredViews.size} views")
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "View monitoring setup failed: $e")
        }
    }

    /**
     * Fully removes the views monitoring system.
     *
     * Cleaning process:
     * 1. Removes Listener from future changes
     * 2. Removes all Draw listeners from monitored views
     * 3. Cleans all data collections
     * 4. Reset state flags
     *
     * Critical to avoid memory leaks and indefinite behavior.
     */
    fun uninstallViewMonitoring() {
        try {
            // Remove listener from future changes
            Curtains.onRootViewsChangedListeners -= onRootViewsChangedListener

            // Clean all existing monitoring
            cleanupViewMonitoring()

            // reset states for next execution
            isScreenContentChanged.set(false)
            isFirstCapture = true

            // Limpa pools da classe utilitária
            screenDrawing.clearObjectPools()

            Log.d("LightSessionCore", "View monitoring uninstalled")
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "View monitoring uninstall failed: $e")
        }
    }

    /**
     * Cleans all listeners and references of monitored views.
     *
     * Why is it necessary:
     * - Viewtreeobserver.andrawlistener is not automatically removed
     * - Orphaned listeners can cause significant memory leaks
     * - views may continue to try to notify nonexistent listeners
     *
     * Process:
     * 1. For each monitored view, remove your specific draw listener
     * 2. Treats exceptions individually (a view failure not for the process)
     * 3. Cleans all data collections
     */
    private fun cleanupViewMonitoring() {
        // Remove all listeners from monitored views
        monitoredViews.values.forEach { viewInfo ->
            viewInfo.viewRef.get()?.let { view ->
                try {
                    // Remove the specific listener from this view
                    view.viewTreeObserver.removeOnDrawListener(viewInfo.drawListener)
                } catch (e: Exception) {
                    // view may have been destroyed, but continue with other
                    Log.w("LightSessionCore", "Failed to remove draw listener: $e")
                }
            }
        }

        // Clean all collections
        monitoredViews.clear()
        rootViews.clear()
    }

    /**
     * Listener that is notified when views are added/removed from the hierarchy.
     *
     * This is the heart of the dynamic monitoring system:
     * - Detects when new views appear (dialogs, fragments, keyboards, etc.)
     * - Detects when views are removed (automatic cleanup)
     * - Maintains monitoring always updated without manual intervention
     */
    private val onRootViewsChangedListener = OnRootViewsChangedListener { view, added ->
        addViewToMonitoring(view, added)
    }

    /**
     * Add or remove a view from the monitoring system.
     *
     * @param view the view that was added/removed from the hierarchy
     * @param added True if the view has been added, false if it was removed
     *
     * Addition logic:
     * - Records View as Root View
     * - Checks if you have PhoneWindow (Activity/Dialog views)
     * - If there is no window attach count, wait for me to be ready
     * - Otherwise, it constitutes monitoring immediately
     *
     * Why WindowAttachCount matters:
     * - views can be created before they are attached to the window
     * - Monitoring unproked views can cause crashes
     * - DecorView is the real root of an Android window
     */
    private fun addViewToMonitoring(view: View, added: Boolean = true) {
        try {
            if (added) {
                // Add to root views list for tracking
                rootViews.add(WeakReference(view))

                view.phoneWindow?.let { window ->
                    // View belongs to a window (Activity, Dialog, etc.)
                    if (view.windowAttachCount == 0) {
                        // view has not yet been attached, wait for decorview
                        window.onDecorViewReady {
                            setupViewMonitoring(view)
                        }
                    } else {
                        // view already attached, you can monitor immediately
                        setupViewMonitoring(view)
                    }
                } ?: run {
                    // view without phonewindow (can be custom view), try to monitor directly
                    setupViewMonitoring(view)
                }
            } else {
                // view has been removed, clean monitoring
                removeViewFromMonitoring(view)
            }
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "Failed to add view to monitoring: $e")
        }
    }

    /**
     * Configures full monitoring for a specific view.
     *
     * @param view the view to be monitored
     *
     * Process:
     * 1. Generates unique ID for View (identity-based, not equals)
     * 2. Verifies if it is already being monitored (avoids duplication)
     * 3. Creates DrawListener specific for this view
     * 4. Record the listener on ViewTreeobserver
     * 5. stores information for later cleanup
     * 6. If it is viewgroup, recursively monitors the children
     * 7. Brand that there was change (first force capture)
     *
     * Why system.identityhashcode:
     * - View.hashcode() can be overlooked and cause collisions
     * - Identityhashcode guarantees uniqueness based on the identity of the object
     * - Consistent throughout the life of the object
     */
    private fun setupViewMonitoring(view: View) {
        try {
            val viewId = System.identityHashCode(view)

            // Check if it is already being monitored (avoid duplication)
            if (monitoredViews.containsKey(viewId)) {
                Log.d("LightSessionCore", "View $viewId already monitored")
                return
            }

            // Create specific listener for this view
            val drawListener = createDrawListener(viewId)

            // Registrar listener no ViewTreeObserver da view
            view.viewTreeObserver.addOnDrawListener(drawListener)

            // Store information for Cleanup and Control
            monitoredViews[viewId] = ViewInfo(
                viewRef = WeakReference(view),
                drawListener = drawListener
            )

            // If you are viewgroup, also monitor your children recursively
            if (view is android.view.ViewGroup) {
                setupChildViewsMonitoring(view)
            }

            Log.d("LightSessionCore", "View monitoring setup for view: $viewId")

            // Mark that there was a change to force initial capture
            isScreenContentChanged.set(true)
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "Failed to setup view monitoring: $e")
        }
    }

    /**
     * Recursively monitors all Views of a viewgroup.
     *
     * @param viewGroup the viewgroup whose children will be monitored
     *
     * Why is it necessary:
     * - OnDraw listener at View father does not detect specific changes from children
     * - Children can be modified (color, text, visibility) without redesigning the father
     * - Changes in deeply nested views can be lost
     * - Elements such as TextView, ImageView, etc. Only shoot draw in themselves
     *
     * Process:
     * 1. Iterate for all the children of Viewgroup
     * 2. For each child, check if it is already being monitored
     * 3. If it is not, creates and records a specific draw listener
     * 4. If the child is also Viewgroup, he recursively calls
     *
     * Optimization:
     * - Avoid duplication by checking if it is already monitored
     * - USA SYSTEM.IDENTITYHASHCODE FOR SINGLE IDENTIFICATION
     * - Treats exceptions individually (a failed child not for the process)
     */
    private fun setupChildViewsMonitoring(viewGroup: android.view.ViewGroup) {
        try {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                if (child != null) {
                    val childId = System.identityHashCode(child)

                    // Check if it is already being monitored
                    if (!monitoredViews.containsKey(childId)) {
                        // Create specific listener for this child
                        val childDrawListener = createDrawListener(childId)
                        child.viewTreeObserver.addOnDrawListener(childDrawListener)

                        // Store child information
                        monitoredViews[childId] = ViewInfo(
                            viewRef = WeakReference(child),
                            drawListener = childDrawListener
                        )

                        // If the child is also Viewgroup, monitor his children
                        if (child is android.view.ViewGroup) {
                            setupChildViewsMonitoring(child)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "Failed to setup child views monitoring: $e")
        }
    }

    /**
     * Removes a specific View from the monitoring system.
     *
     * @param view the view to be removed from monitoring
     *
     * Removal process:
     * 1. Locates View on the Monitoring Map
     * 2. Removes the specific draw of this view
     * 3. Removes Entry from the Monitoring Map
     * 4. Remove from root views if applicable
     *
     * Why is it important:
     * - Views removed but still monitored cause memory leaks
     * - Orphaned listeners may try to access destroyed views
     * - Maintain clean map improves iteration performance
     *
     * NOTE: This method removes only specific View, not your children.
     * Children are automatically removed by the system or periodic cleaning.
     */
    private fun removeViewFromMonitoring(view: View) {
        try {
            val viewId = System.identityHashCode(view)
            monitoredViews[viewId]?.let { viewInfo ->
                // Remover o draw listener da view
                view.viewTreeObserver.removeOnDrawListener(viewInfo.drawListener)

                // Remove from monitoring map
                monitoredViews.remove(viewId)
                Log.d("LightSessionCore", "View $viewId removed from monitoring")
            }

            // Remove from root views (clean dead references too)
            rootViews.removeAll { it.get() == view || it.get() == null }
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "Failed to remove view from monitoring: $e")
        }
    }

    /**
     * Periodically clean views that have been destroyed but are still being monitored.
     *
     * Why is it necessary:
     * - views can be destroyed without notifying the monitoring system
     * - Weakreference.get() returns null when object was collected by GC
     * - Keeping dead references on the map reduces performance and consumes memory
     *
     * Process:
     * 1. Itera for all monitored views
     * 2. *
     * 3. Collect IDS of the dead views
     * 4. Removes all the dead views from the map
     * 5. report how many were clean (useful for debug)
     *
     * Automatically called during the capture cycle to keep the system clean.
     */
    private fun cleanupDeadViews() {
        val deadViews = mutableListOf<Int>()

        // Identify views that were collected by the Garbage Collector
        monitoredViews.forEach { (viewId, viewInfo) ->
            if (viewInfo.viewRef.get() == null) {
                deadViews.add(viewId)
            }
        }

        // Remove dead views from the map
        deadViews.forEach { viewId ->
            monitoredViews.remove(viewId)
        }

        if (deadViews.isNotEmpty()) {
            Log.d("LightSessionCore", "Cleaned up ${deadViews.size} dead views")
        }
    }
}