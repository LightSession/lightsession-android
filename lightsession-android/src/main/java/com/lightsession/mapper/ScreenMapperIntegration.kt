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
import android.util.DisplayMetrics
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
import com.lightsession.interaction.InteractionAwareCallback
import com.lightsession.replay.ScreenDrawing
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
                    onComplete(frame?.let { Wireframe(skeleton = it) })
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
     * Whether sending this wireframe would replace a better image.
     *
     * The two captures race, and the wireframe can lose. Its capture waits for the
     * composition to *settle*, which is unbounded, while the real screenshot waits a
     * fixed [SCREENSHOT_SETTLE_MS] from navigation. On Phoenix's home screen the
     * screenshot landed at +2.6s and the wireframe only finished settling at +5.2s —
     * so the wireframe arrived last and overwrote the real screen, and the server's
     * supersede-delete then removed the screenshot from the bucket.
     *
     * The guard at the top of the send is checked *before* the capture starts, so it
     * cannot see this. This is the same question asked again after the wait, which is
     * the only point where the answer is current. It also saves an upload that was
     * always going to be discarded.
     */
    private fun wireframeWouldDowngrade(screenCacheKey: String): Boolean =
        captureRealScreens && cacheManager.isScreenFullyCaptured(screenCacheKey)

    fun getCurrentScreen(): String? {
        return lastScreen
    }

    fun getCurrentScreenId(): String? {
        val screenName = lastScreen ?: return null
        val activity = currentActivityWeakRef?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return try {
            val appVersionCode = getAppVersionCode()
            val appVersionName = getAppVersionName()
            val displayMetrics = activity.resources.displayMetrics
            val theme = getCurrentTheme(activity)
            generateScreenID(
                screenName = screenName,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                width = displayMetrics.widthPixels,
                height = displayMetrics.heightPixels,
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
        private const val SCREENSHOT_SETTLE_MS = 2_500L

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
    ) {
        if (this.application != null) {
            return
        }
        this.application = application
        this.dataSender = dataSender
        this.sessionDataManager = sessionDataManager
        this.wireframeMode = wireframeMode
        this.captureRealScreens = captureRealScreens


        // Initialize cache manager
        cacheManager = CacheManager(application)

        // Obter AppVersionCode e AppVersionName
        val currentAppVersionCode = getAppVersionCode()
        val currentAppVersionName = getAppVersionName()

        cacheManager.handleAppVersionCheck(currentAppVersionName)

        setupActivityLifecycleCallbacks()
    }

    // Função para obter o versionCode
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

    // Função para obter o versionName
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

    // Nova função para obter o tema da tela
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
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                onScreenClickListener?.invoke()
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

        if (screenName == lastScreen) {
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

        lastScreen = screenName
    }

    override fun handleConventionalNavigation(destination: NavDestination) {
        val screenName = utils.getFragmentClassNameSafely(destination)

        if (screenName == lastScreen) {
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

        lastScreen = screenName
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
        lastScreen = screenName
    }

    private fun getOrCreateScreenNode(name: String, type: ScreenType): ScreenNode {
        return screenNodes.getOrPut(name) {
            ScreenNode(name, type)
        }
    }
    data class ScreenParams(val displayMetrics: DisplayMetrics, val currentTheme: String)


    /**
     * Tracks the navigation flow between two screens.
     * This function is the core of the navigation tracking system. It is called every time a
     * navigation event occurs.
     *
     * @param from The origin screen name.
     * @param to The destination screen name.
     */
    private fun trackNavigationFlow(from: String, to: String) {
        screenNodes[from]?.connections?.merge(to, 1, Int::plus)

        val appVersionCode = getAppVersionCode()
        val appVersionName = getAppVersionName()

        val screenParams = currentActivityWeakRef?.get()?.run {
            val metrics = resources?.displayMetrics
            val theme = getCurrentTheme(this)

            if (metrics != null && theme != null) {
                ScreenParams(metrics, theme)
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
                    width = params.displayMetrics.widthPixels,
                    height = params.displayMetrics.heightPixels,
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
     * @param activity A referência da Activity para obter o Context e o Tema.
     */
    private suspend fun sendNavigationData(from: String, to: String, activity: Activity) {
        try {
            val appVersionCode = getAppVersionCode()
            val appVersionName = getAppVersionName()
            val currentTheme = getCurrentTheme(activity)

            val displayMetrics = activity.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

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
                        if (wireframeWouldDowngrade(screenCacheKey)) {
                            Log.d("ScreenMapper", "Real screenshot already stored for $to; wireframe dropped.")
                        } else if (wireframe != null) {
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
        val scope = (activity as? ComponentActivity)?.lifecycleScope ?: return

        scope.launch {
            try {
                val appVersionCode = getAppVersionCode()
                val appVersionName = getAppVersionName()
                val currentTheme = getCurrentTheme(activity)

                // Get dimensions from activity
                val displayMetrics = activity.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

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
                        if (wireframeWouldDowngrade(screenCacheKey)) {
                            Log.d("ScreenMapper", "Real screenshot already stored for $screenName; wireframe dropped.")
                        } else if (wireframe != null) {
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
     * Agenda o upgrade do wireframe para o print real da tela.
     *
     * Espera [SCREENSHOT_SETTLE_MS] antes de capturar, e é a espera que dá valor
     * à captura: animação termina, imagem carrega, dado da rede chega. O que for
     * capturado antes disso é uma tela em construção, não a tela.
     *
     * Qualquer toque ou nova navegação cancela via [cancelScreenshot] — se o
     * usuário interagiu, a tela mudou, e capturar depois disso registraria um
     * estado que não é o de chegada.
     *
     * Governado por `LightSessionConfig.captureRealScreens`, e vale reler o kdoc
     * de lá: com isso ligado o bucket guarda um print sem máscara de cada tela do
     * app, permanentemente, e não há masking no device.
     */
    private fun scheduleScreenshot() {
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

    private fun takeScreenshot(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            Log.d("ScreenMapper", "Invalid activity for screenshot. Finishing or destroyed.")
            return
        }

        try {
            val appVersionCode = getAppVersionCode()
            val appVersionName = getAppVersionName()
            val currentTheme = getCurrentTheme(activity)

            // Get dimensions from activity - USE THIS FOR CONSISTENCY
            val displayMetrics = activity.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val (bitmapBase64, _) = screenDrawing.captureScreenAsBase64()

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