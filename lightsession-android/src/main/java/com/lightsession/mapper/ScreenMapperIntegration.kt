package com.lightsession.mapper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.fragment.NavHostFragment
import com.lightsession.LightSessionConfig
import com.lightsession.Recording
import com.lightsession.ScreenGeometry
import com.lightsession.interaction.InteractionAwareCallback
import com.lightsession.replay.ScreenDrawing
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.OnTouchEventListener
import curtains.touchEventInterceptors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import android.view.Window
import com.lightsession.SessionDataManager

class ScreenMapperIntegration private constructor() : NavigationHandler {

    private var application: Application? = null
    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    private val registeredComposeControllers = mutableSetOf<WeakReference<NavController>>()

    /** Warned once per process; the message is advice, not an event. */
    private var composeIntegrationWarned = false

    /** The delayed integration check runs here, because
     *  `registeredComposeControllers` is read and written on the main thread
     *  everywhere else and is not synchronised. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val registeredConventionalListeners =
        ConcurrentHashMap<NavController, NavController.OnDestinationChangedListener>()

    private val screenNodes = ConcurrentHashMap<String, ScreenNode>()
    private var lastScreen: String? = null

    /**
     * The NavController destination, without any sub-screen suffix.
     *
     * Split from [lastScreen] rather than derived from it. [lastScreen] is what every
     * downstream consumer already reads — the screenshot, the capture id, the flow edge —
     * so making *that* the composed name is what lets a tab or a dialog become a real
     * screen without touching any of them. This holds the other half: the thing to compose
     * a new suffix onto, and the thing a repeated navigation is deduplicated against.
     */
    private var baseScreen: String? = null

    private var currentSubScreen: SubScreen? = null

    /**
     * Every tab that was already selected when this destination was entered.
     *
     * A list, and the diff against it is what identifies the reader's choice. A screen can
     * hold several things reporting `Role.Tab` — a bottom navigation bar has it as well as a
     * tab row — and semantics cannot tell them apart. What tells them apart is that the nav
     * item is a function of the destination and does not move without a navigation, so
     * whatever is selected now and was not selected on arrival is the tab that was tapped.
     *
     * This replaced taking the first selected tab found, which depended on the order the
     * composition happened to emit them in: get the nav bar first and the screen's own tabs
     * became invisible, because the value never changed as the reader switched them.
     */
    private var defaultTabs: List<String> = emptyList()

    /** Whether the pending read establishes the default rather than reporting a change. */
    private var pendingReadIsBaseline = false

    /** Set by [setDeclaredSubScreen]; see [SubScreen.Kind.DECLARED]. */
    private var declaredSubScreen: SubScreen? = null

    /** Set while a modal window is on screen, so its removal can be recognised. */
    private var modalRootView: java.lang.ref.WeakReference<android.view.View>? = null

    /**
     * What was showing underneath the open modal.
     *
     * Closing a dialog returns to whatever was behind it, which is not necessarily the
     * bare destination: dismiss a confirm dialog raised from the History tab and the
     * reader is back on History, not on the screen's default tab. Reporting the
     * destination there would record an arrival that never happened.
     */
    private var subScreenBeneathModal: SubScreen? = null

    private var sessionDataManager: SessionDataManager? = null

    private val skeletonGenerator = SkeletonGenerator()

    /**
     * Set from `LightSessionConfig.wireframeMode` at [init].
     *
     * Defaults to [LightSessionConfig.WireframeMode.RECTS] so a caller that reaches
     * this singleton without going through `LightSession.init` still gets the cheap
     * path rather than the one that encodes a JPEG per screen.
     */
    private var wireframeMode = LightSessionConfig.WireframeMode.RECTS

    /**
     * Set from `LightSessionConfig.captureRealScreens` at [init].
     *
     * Defaults to false here, unlike the config, so a caller that reaches this
     * singleton without going through `LightSession.init` never starts capturing real
     * screens by accident. The config's default is the product decision; this one is
     * the safe fallback when there is no config at all.
     */
    private var captureRealScreens = false

    /** Set from `LightSessionConfig.trackTabs` at [init]. */
    private var trackTabs = true

    /** Set from `LightSessionConfig.trackModals` at [init]. */
    private var trackModals = true

    /** Set from `LightSessionConfig.trueColourWireframes` at [init]. */
    private var trueColourWireframes = true

    /**
     * A wireframe, however it was produced. Exactly one field is set.
     *
     * The two modes disagree about *where* the drawing happens, not about anything
     * the caller does afterwards — so the call sites branch once, here, instead of
     * duplicating the send.
     */
    private class Wireframe(
        val bitmapBase64: String? = null,
        val skeleton: SkeletonFrame? = null,
    )

    /**
     * Captures the current screen's wireframe in whichever mode is configured.
     *
     * @param onComplete receives null when there was nothing to capture — a screen
     *   that never laid out, or a composition that never settled.
     */
    private fun captureWireframe(activity: Activity, onComplete: (Wireframe?) -> Unit) {
        when (wireframeMode) {
            LightSessionConfig.WireframeMode.RECTS ->
                skeletonGenerator.generateSkeletonFrame(activity) { frame ->
                    if (frame == null || !trueColourWireframes) {
                        onComplete(frame?.let { Wireframe(skeleton = it) })
                        return@generateSkeletonFrame
                    }
                    recolourFromScreen(frame) { coloured ->
                        onComplete(Wireframe(skeleton = coloured))
                    }
                }

            LightSessionConfig.WireframeMode.BITMAP ->
                skeletonGenerator.generateSkeletonBitmap(activity) { bitmap ->
                    onComplete(
                        bitmap?.let {
                            Wireframe(bitmapBase64 = skeletonGenerator.bitmapToBase64(it))
                        }
                    )
                }
        }
    }

    /**
     * Reads each widget's real colour out of a capture of the screen.
     *
     * Taken *after* the skeleton, not before, and that ordering is the point: the skeleton waits
     * for the composition to settle, so by the time it returns the screen is by definition no
     * longer changing — which is what makes the geometry and the pixels describe the same
     * moment. Capturing first would risk colouring a settled layout from an unsettled frame.
     *
     * Sampled from whatever the capture produces, masking included. A masked capture has grey
     * over its text, so a text block comes back the mask's colour rather than the paper's —
     * which is duller but needs no separate decision about reading unmasked pixels, and follows
     * `Masking.enabled` without a second switch to keep in step with it.
     *
     * Degrades to the palette. A capture that fails is a wireframe in template colours, which is
     * exactly what shipped before this existed.
     */
    private fun recolourFromScreen(frame: SkeletonFrame, onComplete: (SkeletonFrame) -> Unit) {
        val glyphs = Recolour.glyphSizedRects(frame)
        if (glyphs > 0) {
            // The colours are only meaningless because the rectangles are coarse. If the scan
            // ever starts emitting one per character, sampling would paint the text back — so
            // this refuses rather than asks.
            Log.w(
                "ScreenMapper",
                "$glyphs rect(s) are glyph-sized; keeping palette colours, since a colour per " +
                    "character would reconstruct the text",
            )
            onComplete(frame)
            return
        }

        screenDrawing.captureToBitmapAsync(ScreenDrawing.Companion.ScalePresets.ORIGINAL) { bitmap ->
            if (bitmap == null) {
                Log.d("ScreenMapper", "no capture to sample colours from; keeping the palette")
                onComplete(frame)
                return@captureToBitmapAsync
            }
            val coloured = try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                // One crossing into native code for the whole frame. Per-pixel `getPixel`
                // measured 173ms against 12ms for a screen's rectangles, all of it JNI.
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                Recolour.apply(frame, pixels, bitmap.width, bitmap.height)
            } catch (error: Throwable) {
                Log.w("ScreenMapper", "colour sampling failed; keeping the palette", error)
                frame
            } finally {
                // Back to the pool either way, or the next capture allocates.
                screenDrawing.recycleBitmap(bitmap)
            }
            onComplete(coloured)
        }
    }

    // `wireframeWouldDowngrade` was here, and is gone with what made it necessary.
    //
    // The two captures race and the wireframe can lose: its capture waits for the composition to
    // *settle*, which is unbounded, while the screenshot waits a fixed [SCREENSHOT_SETTLE_MS] from
    // navigation. On one app's home screen the screenshot landed at +2.6s and the wireframe only
    // settled at +5.2s, so the wireframe arrived last, overwrote the real screen, and the server's
    // supersede-delete removed the screenshot from the bucket. Dropping the late wireframe was the
    // only way to keep the better image.
    //
    // The server keeps each in its own slot now, so neither can displace the other and the race
    // has no consequence. Dropping the wireframe would instead cost the screen the layer a reader
    // can switch to — which is exactly the screens that need it, since a screen somebody stayed on
    // long enough to photograph is the one whose skeleton used to be deleted.

    fun getCurrentScreen(): String? {
        return lastScreen
    }

    /**
     * The Activity in the foreground, or null.
     *
     * Exposed because `ScreenDrawing` needs a `Window` to capture from when the software
     * draw cannot be used, and this class already tracks the foreground Activity through
     * its lifecycle callbacks. A second tracker would be a second thing to keep correct.
     */
    fun currentActivity(): Activity? = currentActivityWeakRef?.get()
        ?.takeUnless { it.isFinishing || it.isDestroyed }

    fun getCurrentScreenId(): String? {
        val screenName = lastScreen ?: return null
        val activity = currentActivityWeakRef?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return try {
            val appVersionCode = getAppVersionCode()
            val appVersionName = getAppVersionName()
            val theme = getCurrentTheme(activity)
            val screen = ScreenGeometry.size()
            generateScreenID(
                screenName = screenName,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                width = screen.width,
                height = screen.height,
                theme = theme
            )
        } catch (e: Exception) {
            Log.e("ScreenMapper", "Error generating current screen ID", e)
            null
        }
    }

    private val navigationFlows = mutableListOf<NavigationFlow>()
    private val utils = Utils()
    private val screenDrawing = ScreenDrawing()

    private val originalCallbacks = ConcurrentHashMap<Activity, Window.Callback>()

    private var currentScreenshotJob: Job? = null
    private var currentActivityWeakRef: WeakReference<Activity>? = null
    private var dataSender: DataSender? = null
    private var isScreenshotScheduledForCurrentScreen = false

    // Cache system
    private lateinit var cacheManager: CacheManager

    data class ScreenNode(
        val name: String,
        val type: ScreenType,
        val routes: MutableSet<String> = mutableSetOf(),
        val connections: MutableMap<String, Int> = mutableMapOf()
    )

    data class NavigationFlow(
        val from: String,
        val to: String,
    )

    enum class ScreenType {
        COMPOSE,
        CONVENTIONAL,
        ACTIVITY
    }

    companion object {
        /**
         * How long to wait before deciding a Compose host is not going to register
         * a NavController.
         *
         * `withNavigationTracking()` registers from a `LaunchedEffect`, so it lands
         * after the first composition. Three seconds clears that comfortably even on
         * a cold start on a slow device, and the check runs once per process.
         */
        private const val COMPOSE_INTEGRATION_GRACE_MS = 3_000L

        /**
         * How long a screen must sit untouched before it is captured for real.
         *
         * Long enough for animations, image loads and a network round trip to land, so
         * the capture is the arrived screen rather than one mid-build. Short enough
         * that an ordinary reader of the screen is still on it.
         */
        private const val SCREENSHOT_SETTLE_MS = 5_500L

        /**
         * How long after a touch ends before the sub-screen is re-read.
         *
         * Not zero: the tab's `selected` state flips inside the click handler, but with a
         * pager the move is a fling that keeps running after the finger leaves, and
         * reading at `ACTION_UP` catches the tab the user is swiping *away* from. Long
         * enough for the settle, short enough that the reading lands well inside the
         * screenshot's own wait.
         */
        private const val SUB_SCREEN_SETTLE_MS = 350L

        /**
         * How long after a window appears before its semantics are read.
         *
         * The window is reported at `WindowManager.addView`, which is before the
         * composition inside it has composed or laid out — measured on device, the tree
         * holds one node at that instant and three once idle. Reading synchronously in the
         * callback identifies every dialog as empty.
         */
        private const val MODAL_SETTLE_MS = 250L

        @Volatile
        private var instance: ScreenMapperIntegration? = null

        fun getInstance(): ScreenMapperIntegration {
            return instance ?: synchronized(this) {
                instance ?: ScreenMapperIntegration().also { instance = it }
            }
        }
    }

    fun init(
        application: Application,
        dataSender: DataSender,
        sessionDataManager: SessionDataManager,
        wireframeMode: LightSessionConfig.WireframeMode = LightSessionConfig.WireframeMode.RECTS,
        captureRealScreens: Boolean = false,
        trackTabs: Boolean = true,
        trackModals: Boolean = true,
        trueColourWireframes: Boolean = true,
    ) {
        if (this.application != null) {
            return
        }
        this.application = application
        this.dataSender = dataSender
        this.sessionDataManager = sessionDataManager
        this.wireframeMode = wireframeMode
        this.captureRealScreens = captureRealScreens
        this.trackTabs = trackTabs
        this.trackModals = trackModals
        this.trueColourWireframes = trueColourWireframes

        if (trackModals) {
            Curtains.onRootViewsChangedListeners += rootViewsListener
        }

        // Initialize cache manager
        cacheManager = CacheManager(application)

        // Obter AppVersionCode e AppVersionName
        val currentAppVersionCode = getAppVersionCode()
        val currentAppVersionName = getAppVersionName()

        cacheManager.handleAppVersionCheck(currentAppVersionName)

        setupActivityLifecycleCallbacks()
    }

    private fun getAppVersionCode(): Int {
        val currentApplication = application ?: return 0

        return runCatching {
            val packageManager = currentApplication.packageManager
            val packageName = currentApplication.packageName

            val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
        }.getOrDefault(0)
    }

    private fun getAppVersionName(): String {
        val currentApplication = application ?: return "unknown"

        return runCatching {
            val packageManager = currentApplication.packageManager
            val packageName = currentApplication.packageName

            val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun getCurrentTheme(context: Context): String {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (uiMode) {
            Configuration.UI_MODE_NIGHT_YES -> "Dark"
            Configuration.UI_MODE_NIGHT_NO -> "Light"
            Configuration.UI_MODE_NIGHT_UNDEFINED -> "Undefined"
            else -> "Unknown"
        }
    }

    private fun isActivityUsingCompose(activity: Activity): Boolean {
        val contentView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        fun containsComposeView(viewGroup: ViewGroup?): Boolean {
            if (viewGroup == null) return false
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                if (child is ComposeView) return true
                if (child is ViewGroup && containsComposeView(child)) return true
            }
            return false
        }
        return containsComposeView(contentView)
    }

    private var touchEventListener: OnTouchEventListener? = null

    private var onScreenClickListener: (() -> Unit)? = {
        // O usuário interagiu antes da tela assentar: ela não é uma tela
        // "parada" e não vale mapear. Essa é a heurística original.
        skeletonGenerator.cancelPendingCapture()
        cancelScreenshot()
    }

    private fun setupActivityLifecycleCallbacks() {
        touchEventListener = OnTouchEventListener { motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> onScreenClickListener?.invoke()
                // The end of the gesture, not its start: at ACTION_DOWN the tab has not
                // changed yet, and on a pager it has not even begun to.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleSubScreenRead()
            }
        }

        activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                currentActivityWeakRef = WeakReference(activity)

                val originalCallback = activity.window.callback
                if (originalCallback !is InteractionAwareCallback) {
                    originalCallbacks[activity] = originalCallback
                    activity.window.callback = InteractionAwareCallback(originalCallback, activity)
                }

                activity.window?.let { window ->
                    touchEventListener?.let { listener ->
                        window.touchEventInterceptors -= listener
                        window.touchEventInterceptors += listener
                    }
                }

                if (activity is ComponentActivity) {
                    if (isActivityUsingCompose(activity)) {
                        /**
                         * A Compose host. Its NavController is created inside the
                         * composition by `rememberNavController()`, and Compose keeps
                         * no global registry of them — so unlike the Fragment case
                         * below, there is nothing here for the SDK to find. The host
                         * app has to hand it over with
                         * `rememberNavController().withNavigationTracking()`.
                         *
                         * That is a documented requirement, not an oversight. What
                         * *was* an oversight is that nothing said so: an app that
                         * skipped the call recorded frames normally and produced a
                         * screen map with no screen names, which is indistinguishable
                         * from the SDK being broken. Hence the check below.
                         */
                        warnIfNavControllerMissing(activity)
                    } else {
                        if (checkNavControllerConventional(activity)) {
                            /**
                            This means that this activity is likely a host activity for navigable
                            fragments, using nav_host and nav_controller in the conventional way.
                            The NavController is inflated synchronously, meaning we can access it
                            via the FragmentManager and add a listener to it.
                             */
                            if (activity is FragmentActivity) {
                                val fragmentManager = activity.supportFragmentManager
                                val fragments = fragmentManager.fragments
                                for (fragment in fragments) {
                                    if (fragment is NavHostFragment) {
                                        val navController = fragment.navController
                                        registerConventionalNavController(navController)
                                    }
                                }
                            }
                        } else {
                            /**
                             * Handles navigation for Activities that don't use NavController or Compose.
                             *
                             * This typically applies to:
                             * 1. Simple screens (e.g., splash screens, About screens) with no internal navigation
                             * 2. Legacy projects not using Navigation Component/Jetpack Compose
                             * 3. Special cases like:
                             * - Deep link handlers
                             * - Dialog-style Activities
                             * - Third-party SDK UIs with their own navigation
                             *
                             * Navigation between these Activities is done via Intents. Any internal "navigation"
                             * is managed through FragmentTransactions or direct view manipulation.
                             */
                            handleActivityNavigation(activity)
                        }
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                originalCallbacks.remove(activity)?.let {
                    activity.window.callback = it
                }

                activity.window?.let { window ->
                    touchEventListener?.let { listener ->
                        window.touchEventInterceptors -= listener
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is FragmentActivity) {
                    val fragmentManager = activity.supportFragmentManager
                    val fragments = fragmentManager.fragments
                    for (fragment in fragments) {
                        if (fragment is NavHostFragment) {
                            val navController = fragment.navController
                            unregisterConventionalNavController(navController)
                        }
                    }
                }
                if (currentActivityWeakRef?.get() == activity) {
                    skeletonGenerator.cancelPendingCapture()
                    cancelScreenshot()
                    currentActivityWeakRef = null
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                activity.window?.let { window ->
                    touchEventListener?.let { listener ->
                        window.touchEventInterceptors -= listener
                    }
                }

                // Also here, not only in `onActivityPaused`. This map holds Activities by
                // strong reference from a singleton that lives as long as the process, so
                // an entry that is never removed is a leaked Activity and every View in
                // it. Pause normally precedes destroy, but "normally" is not a lifetime
                // guarantee, and the cost of asking twice is a hash lookup.
                originalCallbacks.remove(activity)

                if (activity is FragmentActivity) {
                    val fragmentManager = activity.supportFragmentManager
                    val fragments = fragmentManager.fragments
                    for (fragment in fragments) {
                        if (fragment is NavHostFragment) {
                            val navController = fragment.navController
                            unregisterConventionalNavController(navController)
                        }
                    }
                }
                if (currentActivityWeakRef?.get() == activity) {
                    skeletonGenerator.cancelPendingCapture()
                    cancelScreenshot()
                    currentActivityWeakRef = null
                }
            }
        }
        application?.registerActivityLifecycleCallbacks(activityLifecycleCallbacks!!)
    }

    private fun checkNavControllerConventional(activity: Activity): Boolean {
        return try {
            if (activity !is FragmentActivity) return false
            val fragmentManager = activity.supportFragmentManager
            val fragments = fragmentManager.fragments
            fragments.any { fragment ->
                fragment != null && (fragment.javaClass.simpleName.contains("NavHostFragment") ||
                        try {
                            NavHostFragment.findNavController(fragment); true
                        } catch (e: Exception) {
                            false
                        })
            }
        } catch (e: Exception) {
            Log.e("ScreenMapper", "Error when checking Navcontroller conventionally", e)
            false
        }
    }

    private fun registerConventionalNavController(navController: NavController) {
        if (registeredConventionalListeners.containsKey(navController)) return

        val listener =
            NavController.OnDestinationChangedListener { _, destination, _ ->
                handleConventionalNavigation(destination)
            }

        navController.addOnDestinationChangedListener(listener)
        registeredConventionalListeners[navController] = listener
    }

    /**
     * Complains, once, if a Compose host never handed over its NavController.
     *
     * Delayed rather than immediate: `withNavigationTracking()` registers from a
     * `LaunchedEffect`, which runs after the first composition, so at
     * `onActivityResumed` nothing has arrived yet and an immediate check would warn
     * at every app that *does* integrate correctly.
     *
     * The message names the exact line to add. A warning that only says "screen
     * tracking is not working" costs the reader the same afternoon this cost me.
     */
    private fun warnIfNavControllerMissing(activity: Activity) {
        if (composeIntegrationWarned) return
        val activityName = activity.javaClass.simpleName

        mainHandler.postDelayed({
            registeredComposeControllers.removeAll { it.get() == null }
            if (registeredComposeControllers.isNotEmpty() || composeIntegrationWarned) return@postDelayed
            composeIntegrationWarned = true

            Log.w(
                "ScreenMapper",
                "$activityName hosts Compose but no NavController was registered, so the " +
                    "screen map will have no screen names. Compose keeps no global registry " +
                    "of NavControllers, so LightSession cannot find yours — hand it over:\n" +
                    "    import com.lightsession.mapper.withNavigationTracking\n" +
                    "    val navController = rememberNavController().withNavigationTracking()\n" +
                    "Session recording and replay are unaffected either way."
            )
        }, COMPOSE_INTEGRATION_GRACE_MS)
    }

    internal fun registerComposeNavController(navController: NavController) {
        registeredComposeControllers.removeAll { it.get() == null }
        if (registeredComposeControllers.any { it.get() == navController }) return

        val listener =
            NavController.OnDestinationChangedListener { _, destination, _ ->
                handleComposeNavigation(destination)
            }

        navController.addOnDestinationChangedListener(listener)
        registeredComposeControllers.add(WeakReference(navController))
    }

    private fun unregisterConventionalNavController(navController: NavController) {
        val listener = registeredConventionalListeners.remove(navController)
        listener?.let {
            navController.removeOnDestinationChangedListener(it)
        }
    }

    override fun handleActivityNavigation(activity: Activity) {
        val screenName = utils.getActivityClassName(activity)

        if (screenName == baseScreen) {
            return
        }

        getOrCreateScreenNode(screenName, ScreenType.ACTIVITY).apply {
            routes.add(screenName)
        }

        if (lastScreen != null) {
            trackNavigationFlow(lastScreen!!, screenName)
        } else {
            // First screen - send it as initial screen without navigation flow
            sendInitialScreen(screenName, ScreenType.ACTIVITY, activity)
        }

        enterDestination(screenName)
    }

    override fun handleConventionalNavigation(destination: NavDestination) {
        val screenName = utils.getFragmentClassNameSafely(destination)

        if (screenName == baseScreen) {
            return
        }

        getOrCreateScreenNode(screenName, ScreenType.CONVENTIONAL).apply {
            routes.add(screenName)
        }

        if (lastScreen != null) {
            trackNavigationFlow(lastScreen!!, screenName)
        } else {
            // First screen - send it as initial screen without navigation flow
            val activity = currentActivityWeakRef?.get()
            if (activity != null) {
                sendInitialScreen(screenName, ScreenType.CONVENTIONAL, activity)
            }
        }

        enterDestination(screenName)
    }

    override fun handleComposeNavigation(destination: NavDestination) {
        val route = destination.route ?: "unknown_route"
        val screenName = utils.extractScreenNameFromRoute(route)

        getOrCreateScreenNode(screenName, ScreenType.COMPOSE).apply {
            routes.add(route)
        }

        if (lastScreen != null) {
            trackNavigationFlow(lastScreen!!, screenName)
        } else {
            // First screen - send it as initial screen without navigation flow
            val activity = currentActivityWeakRef?.get()
            if (activity != null) {
                sendInitialScreen(screenName, ScreenType.COMPOSE, activity)
            }
        }
        enterDestination(screenName)
    }

    /**
     * A navigation landed: this destination is now the screen, with nothing on top of it.
     *
     * Clearing the sub-screen here is not housekeeping — it is what stops a tab from
     * leaking onto the next destination. Without it, leaving `dashboard › History` for
     * `profile` would keep the suffix and report `profile › History`, a screen that does
     * not exist.
     */
    private fun enterDestination(screenName: String) {
        baseScreen = screenName
        currentSubScreen = null
        defaultTabs = emptyList()
        modalRootView = null
        subScreenBeneathModal = null
        // A panel does not survive the destination it was declared on. Its `onDispose` will
        // arrive, but composition teardown races the NavController and can land after the
        // next screen has been reported.
        declaredSubScreen = null
        lastScreen = screenName
        // Which tab this destination arrived on has to be learned, not assumed, and it
        // cannot be read yet — the NavController reports the destination before its
        // content composes. This read supersedes any pending one from the touch that
        // caused the navigation, which is the same gesture seen from the other end.
        scheduleSubScreenRead(baseline = true)
    }

    /**
     * Moves to a different part of the current destination, as if it were a navigation.
     *
     * Deliberately routed through [trackNavigationFlow], the same call an ordinary
     * navigation makes. A tab change *is* a navigation from the reader's point of view,
     * and treating it as one means the edge shows up in the flow graph, the new name gets
     * an id and a capture, and none of that had to be reimplemented here.
     */
    private fun applySubScreen(next: SubScreen?) {
        val base = baseScreen ?: return
        if (!SubScreens.shouldReport(currentSubScreen, next)) return

        val from = lastScreen ?: base
        val to = SubScreens.compose(base, next)
        currentSubScreen = next
        if (to == from) {
            // The suffix resolved back to the bare destination — the reader returned to
            // the tab this screen arrived on. Recorded, but there is nowhere to move to.
            return
        }

        Log.d("ScreenMapper", "Sub-screen change: $from -> $to")
        getOrCreateScreenNode(to, screenNodes[base]?.type ?: ScreenType.COMPOSE)
        lastScreen = to
        trackNavigationFlow(from, to)
    }

    /**
     * Re-reads what is on top of the destination after a touch, and reports it if it moved.
     *
     * Hung off the end of a touch rather than off a click handler, because there is no
     * click handler to hook: the `Tab` and the sheet are the app's own composables and the
     * SDK never sees them. Reading the semantics tree afterwards is the only vantage point
     * that works for a tap on a tab row, a swipe across a pager, and a sheet dragged open
     * or shut — none of which look alike from here.
     */
    /**
     * The app naming a part of the screen the SDK cannot see for itself.
     *
     * Applied straight away rather than at the next gesture, because the caller knows
     * exactly when its panel appeared and there may be no gesture at all — a sheet can open
     * from a timer, a deep link, or the result of a request.
     *
     * Passing null returns to whatever the SDK can work out on its own, which is why the
     * tab is re-read on the way out: dismissing a declared sheet reveals the tab beneath it,
     * and that tab is the screen the reader is now on.
     */
    fun setDeclaredSubScreen(name: String?) {
        val next = SubScreens.sanitize(name)?.let { SubScreen(SubScreen.Kind.DECLARED, it) }
        if (next == declaredSubScreen) return
        declaredSubScreen = next
        if (next != null) {
            applySubScreen(next)
        } else {
            // Not simply `applySubScreen(null)`: the destination may have tabs, and naming
            // the bare screen here would report an arrival somewhere the reader is not.
            scheduleSubScreenRead()
        }
    }

    /** See `LightSession.clearSubScreen`. */
    fun clearDeclaredSubScreen(name: String) {
        if (declaredSubScreen?.label != SubScreens.sanitize(name)) return
        setDeclaredSubScreen(null)
    }

    /**
     * Watches for modal windows opening and closing.
     *
     * A Compose `Dialog` is still a real window — `DialogWrapper` extends
     * `ComponentDialog` — and so is a `ModalBottomSheet`. Both therefore reach
     * `WindowManager.addView` and both are reported here, which is the same trace the
     * View world left and the reason this costs no new machinery. What does *not* reach
     * here is a sheet drawn inside the composition; that one is invisible at the window
     * level and is not detected.
     */
    private val rootViewsListener = OnRootViewsChangedListener { view, added ->
        if (!trackModals) return@OnRootViewsChangedListener
        if (added) {
            // Not read now: the window arrives before its content is composed.
            mainHandler.postDelayed({ readModal(view) }, MODAL_SETTLE_MS)
        } else if (modalRootView?.get() === view) {
            modalRootView = null
            applySubScreen(subScreenBeneathModal)
            subScreenBeneathModal = null
        }
    }

    private fun readModal(view: android.view.View) {
        val activity = currentActivityWeakRef?.get() ?: return
        // The Activity's own window is not a modal, and neither is a window belonging to
        // an Activity that has since gone away.
        if (view === activity.window?.decorView) return
        if (!view.isAttachedToWindow) return

        val modal = try {
            SubScreenReader.identifyModal(view)
        } catch (error: Throwable) {
            Log.d("ScreenMapper", "Modal read failed", error)
            null
        } ?: return

        modalRootView = java.lang.ref.WeakReference(view)
        subScreenBeneathModal = currentSubScreen
        applySubScreen(modal)
    }

    private fun scheduleSubScreenRead(baseline: Boolean = false) {
        if (!trackTabs && !trackModals) return
        pendingReadIsBaseline = baseline
        mainHandler.removeCallbacks(subScreenReadRunnable)
        mainHandler.postDelayed(subScreenReadRunnable, SUB_SCREEN_SETTLE_MS)
    }

    private val subScreenReadRunnable = Runnable {
        // A windowed modal owns the screen while it is up, and its own content may hold
        // tabs; reading them would replace the dialog's name with a tab from inside it.
        // Its closing arrives as a window removal, so nothing is missed by not looking.
        //
        // An in-composition overlay is the opposite case and must keep being read: no
        // window is removed when it closes, so this is the only thing that ever notices.
        if (currentSubScreen?.kind == SubScreen.Kind.MODAL) return@Runnable
        val activity = currentActivityWeakRef?.get() ?: return@Runnable
        val root = activity.window?.decorView ?: return@Runnable
        val tabs = try {
            SubScreenReader.selectedTabs(root)
        } catch (error: Throwable) {
            // A failed read is not worth taking the host app down for, and it is not worth
            // reporting either: the previous sub-screen stays current, which is the same
            // answer this would have given on a screen with neither tabs nor a sheet.
            Log.d("ScreenMapper", "sub-screen read failed", error)
            emptyList()
        }

        if (pendingReadIsBaseline) {
            defaultTabs = tabs
            Log.d("ScreenMapper", "arrived on $baseScreen; tabs selected: $tabs")
            return@Runnable
        }

        // What is selected now and was not on arrival. A nav bar's item is in `defaultTabs`
        // and stays there, so it drops out without having to be recognised.
        val chosen = tabs.firstOrNull { it !in defaultTabs }
        Log.d("ScreenMapper", "tabs now $tabs, arrived with $defaultTabs, chose $chosen")

        // What the app declared wins over what was read: it is the more specific claim, and
        // the panel it names is drawn over the tab the read found.
        val next = declaredSubScreen
            ?: chosen?.takeIf { trackTabs }?.let { SubScreen(SubScreen.Kind.TAB, it) }
        applySubScreen(next)
    }

    private fun getOrCreateScreenNode(name: String, type: ScreenType): ScreenNode {
        return screenNodes.getOrPut(name) {
            ScreenNode(name, type)
        }
    }
    /**
     * The geometry and theme a capture is being reported under.
     *
     * Holds the numbers rather than a `DisplayMetrics`, because the metrics object a caller
     * happens to have is exactly what went wrong here: three of them existed and gave three
     * answers. See [ScreenGeometry].
     */
    data class ScreenParams(val width: Int, val height: Int, val currentTheme: String)


    /**
     * Tracks the navigation flow between two screens.
     * This function is the core of the navigation tracking system. It is called every time a
     * navigation event occurs.
     *
     * @param from The origin screen name.
     * @param to The destination screen name.
     */
    private fun trackNavigationFlow(from: String, to: String) {
        // Gated inside the funnel rather than at its callers: there are seven of those, and a
        // gate per call site is a gate the eighth one forgets. See [Recording] for why the screen
        // map is covered at all — a wireframe of a screen is a picture of it.
        if (!Recording.enabled) return

        screenNodes[from]?.connections?.merge(to, 1, Int::plus)

        val appVersionCode = getAppVersionCode()
        val appVersionName = getAppVersionName()

        val screenParams = currentActivityWeakRef?.get()?.run {
            val theme = getCurrentTheme(this)

            if (theme != null) {
                val screen = ScreenGeometry.size()
                ScreenParams(screen.width, screen.height, theme)
            } else {
                null
            }
        }

        fun generateId(screenName: String): String? {
            return screenParams?.let { params ->
                generateScreenID(
                    screenName = screenName,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    width = params.width,
                    height = params.height,
                    theme = params.currentTheme
                )
            }
        }

        val fromScreenId = generateId(from)
        val toScreenId = generateId(to)

        sessionDataManager?.addNavigation(
            fromScreen = from,
            toScreen = to,
            screenType = screenNodes[to]?.type?.name ?: "UNKNOWN",
            transitionType = "navigation"
        )

        val flowKey = "$fromScreenId->$toScreenId"
        val flowCacheKey = generateCacheKey(flowKey)

        if (cacheManager.isFlowSent(flowCacheKey)) {
            Log.d("ScreenMapper", "Navigation flow already mapped: $flowKey (skipping)")
            val toCacheKey = generateCacheKey(toScreenId.toString())
            if (!cacheManager.isScreenFullyCaptured(toCacheKey)) {
                isScreenshotScheduledForCurrentScreen = true
                scheduleScreenshot()
            } else {
                Log.d("ScreenMapper", "Screen $to already fully captured. Skipping screenshot.")
            }
            return
        }

        navigationFlows.add(NavigationFlow(from, to))
        Log.d("ScreenMapper", "New navigation flow detected: $from -> $to. Scheduling screenshot.")

        currentActivityWeakRef?.get()?.let { activity ->
            (activity as? ComponentActivity)?.lifecycleScope?.launch {
                sendNavigationData(from, to, activity)
            }
        }

        // Use toScreenId instead of just 'to' for cache key consistency
        val toCacheKey = toScreenId?.let { generateCacheKey(it) }
        if (toCacheKey != null && !cacheManager.isScreenFullyCaptured(toCacheKey)) {
            isScreenshotScheduledForCurrentScreen = true
            scheduleScreenshot()
        } else {
            Log.d("ScreenMapper", "Screen $to already fully captured. Skipping screenshot.")
        }
    }

    fun getSessionDataManager(): SessionDataManager? = sessionDataManager

    /**
     * Sends navigation data to the server.
     * This includes sending a placeholder for the destination screen if it hasn't been sent before,
     * and sending the navigation flow itself.
     *
     * @param from The origin screen name.
     * @param to The destination screen name.
     */
    private suspend fun sendNavigationData(from: String, to: String, activity: Activity) {
        try {
            val appVersionCode = getAppVersionCode()
            val appVersionName = getAppVersionName()
            val currentTheme = getCurrentTheme(activity)

            val screen = ScreenGeometry.size()
            val screenWidth = screen.width
            val screenHeight = screen.height

            val screenId = generateScreenID(
                screenName = to,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                width = screenWidth,
                height = screenHeight,
                theme = currentTheme
            )
            val screenCacheKey = generateCacheKey(screenId)

            if (!cacheManager.isScreenSent(screenCacheKey)) {
                screenNodes[to]?.let { toNode ->
                    captureWireframe(activity) { wireframe ->
                        // Sent even when the screenshot already landed. The two are stored in
                        // separate slots now, so a wireframe arriving second adds a layer instead
                        // of replacing the better image — which is what the dropped-wireframe
                        // guard here used to be protecting against.
                        if (wireframe != null) {
                            (activity as? ComponentActivity)?.lifecycleScope?.launch {
                                val result = dataSender?.sendScreenData(
                                    screenId = screenId,
                                    screenName = to,
                                    screenType = toNode.type,
                                    bitmapBase64 = wireframe.bitmapBase64,
                                    skeleton = wireframe.skeleton,
                                    width = screenWidth,
                                    height = screenHeight,
                                    appVersionCode = appVersionCode,
                                    appVersionName = appVersionName,
                                    theme = currentTheme
                                )

                                if (result?.isSuccess == true) {
                                    cacheManager.markScreenAsSent(screenCacheKey, false)
                                    Log.d("ScreenMapper", "Skeleton screen sent for: $screenId (${screenWidth}x${screenHeight})")
                                } else {
                                    Log.e("ScreenMapper", "Failed to send skeleton screen for: $to", result?.exceptionOrNull())
                                }
                            }
                        } else {
                            Log.e("ScreenMapper", "Failed to generate skeleton")
                        }
                    }
                }
            }

            val fromScreenId = generateScreenID(
                screenName = from,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                width = screenWidth,
                height = screenHeight,
                theme = currentTheme
            )

            val toScreenId = generateScreenID(
                screenName = to,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                width = screenWidth,
                height = screenHeight,
                theme = currentTheme
            )

            // Envia os NOMES das telas, não os IDs compostos.
            //
            // O ID composto embute versão, resolução e tema
            // (`home_2.1_2_1080_2337_Light`). Mandá-lo como extremidade do fluxo
            // fazia o servidor registrar uma tela nova por resolução, então o
            // grafo enchia de nós fantasma duplicados — um por combinação de
            // aparelho. A identidade da tela é o nome; o resto descreve a captura.
            val flowResult = dataSender?.sendNavigationFlow(
                fromScreen = from,
                toScreen = to,
                transitionType = "navigation",
                timestamp = System.currentTimeMillis(),
                appVersionCode = appVersionCode,
                appVersionName = appVersionName
            )

            val flowKey = "$fromScreenId->$toScreenId"
            val flowCacheKey = generateCacheKey(flowKey)
            if (flowResult?.isSuccess == true) {
                cacheManager.markFlowAsSent(flowCacheKey)
                Log.d("ScreenMapper", "Navigation flow sent and cached: $flowKey")
            } else {
                Log.e("ScreenMapper", "Failed to send navigation flow", flowResult?.exceptionOrNull())
            }
        } catch (e: Exception) {
            Log.e("ScreenMapper", "Error sending data to server", e)
        }
    }

    /**
     * Sends the initial screen data to the server.
     * This is called when the first screen is detected (lastScreen is null).
     * It sends the screen without a navigation flow.
     *
     * @param screenName The name of the screen.
     * @param screenType The type of the screen (ACTIVITY, CONVENTIONAL, or COMPOSE).
     * @param activity The activity reference to get context and theme.
     */
    private fun sendInitialScreen(screenName: String, screenType: ScreenType, activity: Activity) {
        if (!Recording.enabled) return
        val scope = (activity as? ComponentActivity)?.lifecycleScope ?: return

        scope.launch {
            try {
                val appVersionCode = getAppVersionCode()
                val appVersionName = getAppVersionName()
                val currentTheme = getCurrentTheme(activity)

                val screen = ScreenGeometry.size()
                val screenWidth = screen.width
                val screenHeight = screen.height

                val screenId = generateScreenID(
                    screenName = screenName,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    width = screenWidth,
                    height = screenHeight,
                    theme = currentTheme
                )
                val screenCacheKey = generateCacheKey(screenId)

                if (!cacheManager.isScreenSent(screenCacheKey)) {
                    captureWireframe(activity) { wireframe ->
                        // See the same send on the navigation path: with a slot each, a late
                        // wireframe adds a layer rather than displacing the screenshot.
                        if (wireframe != null) {
                            scope.launch {
                                val result = dataSender?.sendScreenData(
                                    screenId = screenId,
                                    screenName = screenName,
                                    screenType = screenType,
                                    bitmapBase64 = wireframe.bitmapBase64,
                                    skeleton = wireframe.skeleton,
                                    width = screenWidth,
                                    height = screenHeight,
                                    appVersionCode = appVersionCode,
                                    appVersionName = appVersionName,
                                    theme = currentTheme
                                )

                                if (result?.isSuccess == true) {
                                    cacheManager.markScreenAsSent(screenCacheKey, false)
                                    Log.d("ScreenMapper", "Initial skeleton screen sent: $screenId (${screenWidth}x${screenHeight})")
                                } else {
                                    Log.e("ScreenMapper", "Failed to send initial skeleton screen: $screenName", result?.exceptionOrNull())
                                }
                            }
                        } else {
                            Log.e("ScreenMapper", "Failed to generate skeleton for initial screen")
                        }
                    }
                }

                // Schedule screenshot for the initial screen
                val toCacheKey = generateCacheKey(screenId)
                if (!cacheManager.isScreenFullyCaptured(toCacheKey)) {
                    isScreenshotScheduledForCurrentScreen = true
                    scheduleScreenshot()
                } else {
                    Log.d("ScreenMapper", "Initial screen $screenName already fully captured. Skipping screenshot.")
                }
            } catch (e: Exception) {
                Log.e("ScreenMapper", "Error sending initial screen data to server", e)
            }
        }
    }

    internal fun unregisterComposeNavController(navController: NavController) {
        registeredComposeControllers.removeAll {
            val controller = it.get()
            controller == null || controller == navController
        }
    }

    /**
     * Schedules the upgrade from wireframe to a real screenshot of the screen.
     *
     * Waits [SCREENSHOT_SETTLE_MS], and the wait is what gives the capture its worth:
     * animations finish, images load, network content arrives. Anything captured before
     * that is a screen mid-build rather than the screen.
     *
     * Any touch or further navigation cancels it through [cancelScreenshot] — if the user
     * interacted, the screen has changed, and capturing after that records a state that is
     * not the one they arrived at.
     *
     * Governed by `LightSessionConfig.captureRealScreens`, whose kdoc is worth rereading:
     * with it on, the bucket keeps a picture of every screen in the app for as long as the
     * project exists. Masking does apply here — `ScreenDrawing` consults [Masking] at
     * capture time, and `maskText` is on by default — so what leaves the device has its
     * text covered. Turning masking off while leaving this on is what stores an unmasked
     * picture of every screen indefinitely.
     */
    private fun scheduleScreenshot() {
        // The real-screenshot upgrade is its own producer: it runs on a delay, so recording can
        // be stopped between the schedule and the capture. Checked again where the capture
        // happens, for that gap.
        if (!Recording.enabled) return
        cancelScreenshot()

        if (!captureRealScreens) return

        val activity = currentActivityWeakRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.d("ScreenMapper", "Cannot schedule screenshot: invalid or finishing activity.")
            return
        }

        val scope = (activity as? ComponentActivity)?.lifecycleScope ?: run {
            Log.w("ScreenMapper", "No lifecycleScope for this Activity; screenshot not scheduled.")
            return
        }

        isScreenshotScheduledForCurrentScreen = true
        currentScreenshotJob = scope.launch {
            try {
                delay(SCREENSHOT_SETTLE_MS)
                // The gap the schedule-time check cannot cover: several seconds pass here, and
                // recording may have been stopped in them.
                if (!Recording.enabled) return@launch
                // `lifecycleScope` já cancela na destruição, mas a Activity pode
                // estar terminando sem que o escopo tenha sido cancelado ainda.
                if (!activity.isFinishing && !activity.isDestroyed) {
                    takeScreenshot(activity)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("ScreenMapper", "Screenshot canceled for ${activity.javaClass.simpleName} (touch or navigation).")
                // Repropagado: engolir um cancelamento faz a corrotina parecer ter
                // completado, e o escopo do lifecycle deixa de conseguir encerrá-la.
                throw e
            } catch (e: Exception) {
                Log.e("ScreenMapper", "Error capturing screen for ${activity.javaClass.simpleName}", e)
            } finally {
                isScreenshotScheduledForCurrentScreen = false
            }
        }
    }

    private fun cancelScreenshot() {
        // NÃO cancela a geração de skeleton aqui. scheduleScreenshot() chama
        // esta função na primeira linha, e sendInitialScreen/trackNavigationFlow
        // chamam scheduleScreenshot() logo depois de iniciar a captura — cancelar
        // aqui matava toda captura microssegundos após iniciá-la.
        // O cancelamento certo vem do toque e do ciclo de vida; ver abaixo.
        if (currentScreenshotJob?.isActive == true) {
            currentScreenshotJob?.cancel()
            Log.d("ScreenMapper", "Scheduled screenshot was canceled.")
        }
        currentScreenshotJob = null
        isScreenshotScheduledForCurrentScreen = false
    }

    /**
     * Suspending because the capture is: on a screen holding a hardware bitmap the
     * software draw is impossible and PixelCopy answers asynchronously. Already called
     * from inside a coroutine, so this costs nothing.
     */
    private suspend fun takeScreenshot(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            Log.d("ScreenMapper", "Invalid activity for screenshot. Finishing or destroyed.")
            return
        }

        try {
            val appVersionCode = getAppVersionCode()
            val appVersionName = getAppVersionName()
            val currentTheme = getCurrentTheme(activity)

            // Read once, and before the capture: these size the screen id this screenshot is
            // filed under, so they must be the same pair the capture itself was taken at.
            val screen = ScreenGeometry.size()
            val screenWidth = screen.width
            val screenHeight = screen.height

            val (bitmapBase64, _) = screenDrawing.captureScreenAsBase64Async()

            if (bitmapBase64 != null) {
                Log.d("ScreenMapper", "Screenshot of screen ${activity.javaClass.simpleName} taken successfully! Using dimensions: ${screenWidth}x${screenHeight}")

                lastScreen?.let { screenName ->
                    val scope = (activity as? ComponentActivity)?.lifecycleScope ?: return
                    scope.launch {
                        try {
                            // Use activity dimensions for consistency with flow and initial screen data
                            val screenId = generateScreenID(
                                screenName = screenName,
                                appVersionName = appVersionName,
                                appVersionCode = appVersionCode,
                                width = screenWidth,
                                height = screenHeight,
                                theme = currentTheme
                            )

                            val result = dataSender?.updateScreenshot(
                                screenId,
                                screenName,
                                bitmapBase64,
                                screenWidth,
                                screenHeight,
                                appVersionCode,
                                appVersionName,
                                currentTheme
                            )

                            if (result?.isSuccess == true) {
                                val screenCacheKey = generateCacheKey(screenId)
                                cacheManager.markScreenAsSent(screenCacheKey, true)
                                Log.d("ScreenMapper", "Real screenshot sent and cached for: $screenId")
                            } else {
                                Log.e("ScreenMapper", "Failed to send real screenshot", result?.exceptionOrNull())
                            }
                        } catch (e: Exception) {
                            Log.e("ScreenMapper", "Error sending screenshot to server", e)
                        }
                    }
                }
            } else {
                Log.e("ScreenMapper", "Failed to capture screenshot")
            }

        } catch (e: Exception) {
            Log.e("ScreenMapper", "Failed to take screenshot of screen ${activity.javaClass.simpleName}: ${e.message}", e)
        } finally {
            isScreenshotScheduledForCurrentScreen = false
        }
    }

    /**
     * Generates a composite screen ID including screen name, app version, dimensions, and theme.
     * This ID uniquely identifies a screen for a specific app version, dimensions, and theme,
     * consistent with the Go backend's generation logic.
     *
     * @param screenName The name of the screen (e.g., "MainActivity", "HomeFragment", "LoginScreen").
     * @param appVersionName The app's version name (e.g., "1.0.0").
     * @param appVersionCode The app's version code (e.g., 1, 2, 3).
     * @param width The width of the screen in pixels.
     * @param height The height of the screen in pixels.
     * @param theme The current theme (e.g., "Light", "Dark", "Undefined").
     * @return A unique string identifier for the screen.
     */
    @SuppressLint("DefaultLocale")
    private fun generateScreenID(
        screenName: String,
        appVersionName: String,
        appVersionCode: Int,
        width: Int,
        height: Int,
        theme: String
    ): String {
        // Concatenate the attributes that form a unique ID for the screen/version/dimensions/theme combination.
        // Consistent with the Go backend's logic: "%s_%s_%d_%d_%d_%s"
        return String.format(
            "%s_%s_%d_%d_%d_%s",
            screenName,
            appVersionName,
            appVersionCode,
            width,
            height,
            theme
        )
    }

    /**
     * Generates a cache key that includes the API key to ensure cache invalidation
     * when the API key changes. This is used for cache lookups only.
     *
     * @param screenId The screen ID generated by generateScreenID
     * @return A cache key that includes the API key
     */
    private fun generateCacheKey(screenId: String): String {
        val apiKey = dataSender?.getApiKey() ?: ""
        val apiKeyHash = if (apiKey.isNotEmpty()) apiKey.take(8) else "nokey"
        return "${screenId}_${apiKeyHash}"
    }
}

@Composable
fun NavHostController.withNavigationTracking(): NavHostController {
    LaunchedEffect(this) {
        try {
            ScreenMapperIntegration.getInstance().registerComposeNavController(this@withNavigationTracking)
        } catch (e: Exception) {
            Log.e("ComposeExtensions", "Error registering NavController", e)
        }
    }

    DisposableEffect(this) {
        onDispose {
            try {
                ScreenMapperIntegration.getInstance().unregisterComposeNavController(this@withNavigationTracking)
            } catch (e: Exception) {
                Log.e("ComposeExtensions", "Erro ao desregistrar NavController", e)
            }
        }
    }
    return this
}
