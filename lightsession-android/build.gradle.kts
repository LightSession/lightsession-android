plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    id("maven-publish")
}

android {
    namespace = "com.lightsession"
    compileSdk = 36

    defaultConfig {
        // 26, not 28. Nothing here needs 28 as a floor — every version check in the
        // SDK guards *upward* (>= P, >= TIRAMISU) and some guard as low as
        // LOLLIPOP — and a library's minSdk is a ceiling on who can consume it. At
        // 28 an app on minSdk 26 cannot even merge the manifest, which is how this
        // came up: Phoenix is minSdk 26.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Lint gates the build in CI, so it may only fail on something that changed here.
        //
        // These two fail on the calendar instead. Both report that a newer version of something
        // exists — AGP, or a dependency — which becomes true the day it is published and has
        // nothing to do with the commit under test. Between them they were the *only* errors in
        // the project (1 error and 55 warnings, then 1 and 46), so leaving them on would mean
        // either a permanently red pipeline or a lint step nobody gates on.
        //
        // Losing them costs the nudge that a dependency has moved on. That is a job for a bot
        // that opens a pull request you can read and test, not for a check that reddens an
        // unrelated commit — and upgrading either is a deliberate change with its own testing.
        //
        // Errors still abort the build. Nothing else is silenced.
        disable += "AndroidGradlePluginVersion"
        disable += "GradleDependency"
    }

    testOptions {
        unitTests {
            // BatchSpool touches android.util.Log, which is not implemented in the
            // JVM stub jar and throws by default. The spool is otherwise plain file
            // IO, so returning defaults is enough — no Robolectric needed.
            isReturnDefaultValues = true
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.1"
    }
}

// Public is a decision here, never an omission. The surface audit that introduced this found
// seven classes public by accident — SessionDataManager among them — which any client could
// have coupled to, turning every refactor into a breaking change. Strict explicit-API mode makes
// the compiler refuse a declaration that does not state its visibility, so the surface can only
// grow on purpose; it is deliberately six symbols — LightSession, LightSessionConfig, Masking,
// Recording's start/stop on LightSession, LightSessionSubScreen, withNavigationTracking — plus
// their members. Scoped by task name because the raw flag knows no source sets: the DSL's
// test exemption is exactly the behaviour wanted, but only this form reaches Android
// compilations on this KGP, and tests declaring `internal` everywhere would be noise, not API.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.squareup.curtains:curtains:1.2.5")

    // ProcessLifecycleOwner: tells the SDK when the *app* goes to background, which
    // is when most sessions end and the last moment the process is reliably alive.
    implementation("androidx.lifecycle:lifecycle-process:${libs.versions.androidx.lifecycle.get()}")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:${libs.versions.androidx.compose.navigation.get()}")
    implementation("androidx.navigation:navigation-ui-ktx:${libs.versions.androidx.compose.navigation.get()}")
    implementation("androidx.navigation:navigation-compose:${libs.versions.androidx.compose.navigation.get()}")

    // Compose - use BOM
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.runtime:runtime")
    api("androidx.compose.runtime:runtime-livedata")
    api("androidx.compose.material:material")
    api("androidx.compose.material3:material3")

    // Compose UI Tooling Data for skeleton generation (required for accessing Compose hierarchy)
    implementation("androidx.compose.ui:ui-tooling-data")

    // Radiography was here. Its only call site was `Radiography.scan()` assigned to an
    // unused local inside the capture path — a full view-hierarchy walk whose result
    // was discarded, on every screen, in a library that competes on being cheap. The
    // parts of it this SDK actually uses (`ComposeLayoutInfo`, `CompositionContexts`)
    // are adapted source with attribution, not calls into the artifact.
    testImplementation(libs.junit)
    // A real org.json on the JVM test classpath: android.jar's is a stub that throws,
    // so without this the wire format could only be tested on a device.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // Test-only, for the Compose overlay/tab investigation: these let a test compose a
    // real Dialog, ModalBottomSheet or TabRow on device and read back what the framework
    // actually publishes, instead of trusting a reading of the Compose source.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))

    // The Compose above is a floor, not what this SDK runs against. A consumer brings its
    // own BOM, and `ui-tooling-data` is declared without a version precisely so it follows
    // the consumer's — which means the skeleton scan, built on tooling-data, has to keep
    // working on Compose newer than this module has ever compiled against. It did not: an
    // app on the 2026.02.01 BOM stored wireframes containing one rect, and every test here
    // passed the whole time because they all run on the version above.
    //
    // -Pls.composeBomUnderTest=<bom> raises only the androidTest *runtime* classpath. Main
    // stays compiled against the declared BOM, and that asymmetry is the point: it is the
    // exact shape of a published AAR meeting a newer app.
    //
    // `default` and blank both mean "leave the declared BOM alone", so that a caller always has
    // something to pass. The alternative is a caller that has to *omit* the flag for one case,
    // which in the emulator workflow meant a shell conditional — and that action runs its script
    // one line per `sh -c`, so the `if` died on its own first line and took every instrumented
    // run with it. A sentinel here costs one `takeIf`; a branch in the caller cost the pipeline.
    providers.gradleProperty("ls.composeBomUnderTest").orNull
        ?.takeIf { it.isNotBlank() && it != "default" }
        ?.let { bom ->
            androidTestRuntimeOnly(platform("androidx.compose:compose-bom:$bom"))
        }
    // Espresso 3.6.1 reflects on `InputManager.getInstance()`, removed in Android 16, so
    // every instrumented test errors out on an API 36 device before reaching its body.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.lightsession"
            artifactId = "lightsession-android"
            // 0.1.11-alpha is the artifact with a laptop's LAN address hardcoded into
            // it, so a consumer resolving that one by accident sees an SDK that
            // silently reports nowhere. 0.2.1 was the first that takes its endpoints
            // from `LightSessionConfig`, with no defaults to fall back to.
            //
            // 0.3.0 added sub-screens: a tab or a modal becomes a screen of its own,
            // named `destination › part`. Minor rather than patch because it changes
            // what an existing consumer reports without any code change on their side —
            // screens appear in the map that were never there before.
            //
            // 0.4.0 is the review pass — two crashes that took the host app down, three
            // leaks, the work that ran on every touch and was thrown away, the data that
            // was not true — plus what testing it against a second app turned up: tabs
            // read without depending on composition order, and a capture that contains the
            // dialog on top of the screen instead of the screen behind it.
            //
            // Three of those force a minor rather than a patch. `InteractionAwareCallback`
            // no longer extends `GestureDetector.SimpleOnGestureListener`, a supertype gone
            // from a public class. `captureToBitmapAsync` takes a base window. And route
            // names are lower-cased consistently, so a consumer upgrading reports
            // `home/manager` where it used to report `Home/Manager` — the server keys on
            // the name, so both rows exist until the old one is deleted.
            //
            // 0.7.1 changes where the screen size comes from, and makes it one read.
            //
            // 0.7.0 asked `Resources.getSystem()`, which answers correctly but hands back the
            // framework's live `DisplayMetrics` — an object `getRealMetrics` and `getMetrics` write
            // into, so anything in the host process can rewrite it for every reader. The display is
            // now asked directly, into metrics this SDK owns. `size()` also returns both sides at
            // once, so a rotation between two reads cannot yield a pair that never existed.
            //
            // Hardening rather than a fix: 0.7.0 produced correct captures, including the landscape
            // ones that prompted the look. A patch, and nothing on 0.7.0 is wrong.
            // 0.8.0 stops dropping the wireframe once a screenshot exists.
            //
            // The two used to share one slot on the server, and they finish in an order nobody
            // controls — one waits a fixed delay, the other waits for a composition to settle — so
            // a late wireframe overwrote the real screen and the server deleted the screenshot. The
            // guard here dropped it to protect the better image, at the cost of the wireframe
            // existing only for screens nobody stayed on. The server keeps a slot for each now, so
            // neither displaces the other and both are worth sending.
            //
            // Minor rather than patch: an existing consumer starts reporting a layer it did not
            // before, with no code change on their side, and the dashboard gains a wireframe view
            // of screens that previously had none.
            // 0.8.1 makes the config constructible from Java. Every parameter after `apiUrl` has a
            // default and Java cannot use one, so a Java caller had to pass all twenty-one in order
            // — and each field added since moved every argument after it onto a different
            // parameter. `@JvmOverloads` generates the overloads that drop trailing parameters.
            //
            // Additive: existing constructors keep their signatures, and Kotlin callers using named
            // arguments see no difference.
            // 0.9.0 records a Compose Activity that has no NavController.
            //
            // `isActivityUsingCompose` being true skipped the branch that reports an Activity, on
            // the assumption that a Compose host always has a NavController for the app to hand
            // over. One Activity holding one Compose screen has none, and was recorded as nothing:
            // the screen did not exist and the flow through it read as one navigation straight past.
            //
            // Minor rather than patch: an existing consumer starts reporting screens it never did,
            // with no code change on their side, and its map gains nodes and edges.
            // 0.10.0 closes the last two shapes that reported nothing.
            //
            // A hybrid Activity — a `NavHostFragment` plus a `ComposeView` anywhere under it —
            // registered neither: the Compose test ran first and short-circuited the fragment
            // registration. Since a fragment's views hang under the Activity's content view, one
            // Compose screen in a migrating app was enough, and because the test runs once at resume
            // it depended on which fragment was showing then. The same build behaved two ways.
            //
            // An Activity predating AndroidX reported nothing either, twice over: an
            // `is ComponentActivity` gate wrapped the whole decision, and every send read
            // `lifecycleScope ?: return`, so even past the gate its screen went nowhere.
            //
            // The decision is now a pure function with the whole space under unit test.
            // 0.11.0 removes what was never called.
            //
            // `MainLooperHandler` in full: nine methods, one of them ever used, and the two other
            // files needing a main-thread handler already built a plain one. It also cost a frame
            // occasionally — being created inside `capture()` made the field a nullable `var`, so
            // `captureFrame` opened with `?: return` and dropped a frame for no reason but the
            // wrapper not existing yet.
            //
            // `LightSessionThreadFactory` keeps what it exists for — a thread name and `isDaemon`,
            // neither of which `Executors.defaultThreadFactory()` gives — and loses an unused
            // counter, an unused priority and four builders with no callers. Its uncaught-exception
            // handler is gone too: it was the only one in the SDK, so it complemented nothing and
            // instead replaced the default on those threads, keeping an encoder crash out of the
            // host app's own reporting.
            //
            // `SkeletonGenerator` loses three members with no callers, including reflection into
            // `android.app.ActivityThread` to find the foreground Activity — something the mapper
            // already tracks from the lifecycle.
            //
            // Minor, not patch: three public methods leave a public class, and the thread factory
            // narrows to `internal`. Nothing outside this SDK is known to call them, but the
            // signatures are gone.
            // 0.12.0 waits for a screen to have content before drawing its wireframe, whatever the
            // screen is rendered by.
            //
            // Compose was treated as the only host that fills its tree late. Everything else took a
            // fast path asking whether the *window* had a size — which is true immediately and says
            // nothing about whether anything has been drawn into it. Measured on a stock React Native
            // app: the wireframe went out 238ms before the JS bundle logged `Running "example"`, so
            // the scan walked an empty `ReactRootView` and stored a blank page. The real screenshot,
            // which waits, came out perfect — so this was never a capability problem, only a timing
            // one.
            //
            // One path now, asking "is there anything here yet". An Activity already laid out answers
            // yes on the first frame and is captured with no added delay.
            //
            // Minor: an app whose screens render asynchronously starts getting wireframes it never
            // got, with no code change on its side.
            // 0.13.0 lets a host report the screen it is on, for a UI toolkit the SDK cannot see into.
            //
            // Every other screen in the map is discovered — an Activity resumes, a fragment
            // destination changes, a Compose NavController reports one. React Native defeats all
            // three: the whole app is one Activity and its screens are a JavaScript concern the
            // platform never hears about, so such an app recorded exactly one screen forever.
            //
            // `LightSession.setScreen(name)` is the same bargain `withNavigationTracking()` strikes
            // for Compose — the SDK cannot find the navigator, so the host hands the answer over — and
            // it routes through the same flow tracking as everything else, so a screen named from
            // JavaScript gets a wireframe and a heatmap like any other. `ScreenType.REACT_NATIVE`
            // records where the name came from, kept distinct from "unknown": such a screen is
            // precisely known, just known from somewhere else.
            //
            // Minor: additive public API and a new enum case.
            // 0.13.1 puts the masks where the text is.
            //
            // Reported from an app as "the rectangle sits a few pixels above the text in a dialog,
            // and the masks from the screen behind get mixed into it". Both were real, they had
            // different causes, and looking for them turned up two more. Measured in
            // `DialogMaskingTest` on a 1080×2400 device with three-button navigation:
            //
            //  * The software capture path placed a dialog window by re-deriving its position from
            //    `LayoutParams.gravity`, centring in the whole display. The window manager centres a
            //    dialog in the display *minus the system bars*, so the two differ by
            //    `(statusBar - navBar) / 2` — here 31 pixels. The dialog was drawn 31 low while its
            //    mask stayed where the dialog really was, leaving the bottoms of the glyphs showing
            //    under a rectangle that looked like it had covered them. Windows are now placed by
            //    `getLocationOnScreen`, which is what the mask, `PixelCopy` and the surface path
            //    already agreed on. Under gesture navigation both bars are 63 and the error is
            //    exactly zero, which is how it survived: it is invisible on the devices most people
            //    test on.
            //
            //  * Masks were unioned across every window and painted in one pass after the whole
            //    screen was composited, so a rectangle belonging to a window *underneath* landed on
            //    top of the window above it. Measured: the centre of a plain white dialog came back
            //    `#9E9E9E`, the mask fill. Each window's masks are now drawn while that window is
            //    the top of the picture and the next is composited over them. Paint order only —
            //    nothing is clipped and nothing is dropped, so this cannot under-mask. Clipping the
            //    lower window's rectangles against the upper window's bounds is the obvious repair
            //    and it is a trap: a Compose `Dialog` with `usePlatformDefaultWidth = false` has a
            //    full-screen, almost entirely transparent decor view, and clipping against it would
            //    unmask the screen behind.
            //
            //  * `MaskScanner` built a `TextView`'s rectangle from `paddingTop`, while
            //    `TextView.onDraw` translates by `extendedPaddingTop + getVerticalOffset()`. Any
            //    vertical gravity other than TOP shifts the text down inside its box — the default
            //    for a button, a list row with a `minHeight`, and the title and message of an
            //    AppCompat `AlertDialog`. Measured on a 900×300 view with `center_vertical`: ink at
            //    132..169, mask at 0..71. The two did not overlap at all, so the frame carried a
            //    grey block and perfectly legible text. `compoundPaddingLeft` and the scroll offset
            //    were missing for the same reason, and the rectangles are now clipped to the view.
            //
            //  * `findDialogWindow` recognised a window only through `DialogWindowProvider`, which
            //    is Compose's interface, so a platform `AlertDialog` was masked and never
            //    composited — four grey rectangles on a frame the dialog was absent from. It now
            //    falls back to Curtains. A `Popup` still has no `Window` and still cannot be copied;
            //    `maskUncomposited` is what keeps that an eyesore rather than a leak.
            //
            // Patch rather than minor. Nothing new is reported and no signature changes: the same
            // screens, with the covering landing where it was always supposed to. The one judgement
            // call is that a platform dialog now appears in captures it used to be missing from,
            // which is a correction rather than a new layer.

            // 0.14.0 stops believing a screen's tabs past the eighth distinct label. The tab
            // reader's premise — that a tab label is a fixed string in the source and so cannot
            // mint a screen per record — is false wherever rows of data wear a tab role, which
            // Twitter's home does with one tab per joined community. Minor rather than patch
            // because it changes what an existing consumer reports without any code change on
            // their side: on a screen with many dynamic tabs, names stop appearing that used to.
            // See `TabCardinality`.

            // 0.14.1 makes the wireframe see subcompositions again on current Compose. The scan
            // found them by looking for the `ComposerImpl$CompositionContextHolder` wrapper, which
            // Compose has since stopped creating — so on the 2026.02.01 BOM every `LazyColumn` and
            // `Scaffold` screen stored a wireframe of one rect, the outline of a blank page, while
            // the screenshot layer beside it was correct. This module compiles against Compose
            // 1.7.0 and `ui-tooling-data` follows the consumer's version, so the break belonged to
            // every consumer newer than this module and to none of its own tests: all 61 passed.
            //
            // Patch. Nothing new is reported and no signature changes — screens that were storing
            // a blank wireframe store their real one. See `getCompositionContexts`, and the CI
            // matrix that now runs the suite on two Compose versions rather than one.
            // 0.15.0 stops reporting a destination whose whole content is another NavHost. An app
            // with a nested NavHost had a `home/manager` route rendering a scaffold whose own
            // NavHost starts at `dashboard`; both controllers are tracked, so the map grew a node
            // for the shell as well as for the screen inside it — with 20 interactions on
            // `dashboard` against 0 on `home/manager` across every session, and a wireframe that
            // was `dashboard` caught before its data arrived. Minor rather than patch: an existing
            // consumer with nested NavHosts stops reporting screens it used to report, and its
            // edges change shape, with no code change on their side. Screens are permanent, so a
            // shell already in a project's map stays until it is deleted.
            //
            // The report of a Compose destination is now held for two frames, which is what makes
            // the shell recognisable at all — see `PendingReport`.

            // 0.16.0 draws a modal, rather than the page behind it. `SkeletonGenerator` only ever
            // walked `activity.window.decorView`, and a dialog or a bottom sheet is its own window
            // with its own view tree — so a sub-screen like `doctor/detail/{id} › Página inferior`
            // was created correctly and then filled with a wireframe of the page underneath. Seen
            // on the screen map: the screenshot layer showed the sheet over a dimmed page, and the
            // wireframe beside it showed the page, with no sheet on it anywhere.
            //
            // The mapper already held the answer — `readModal` records the modal's root before the
            // report that leads to a capture — so this is mostly a matter of passing it. What had
            // to move with it: the settle detector watched the Activity's decor, which would settle
            // on the first quiet frame of the screen *behind* a sheet; and `frameFrom` reported the
            // scanned root's own size, which held only while that root was a full-screen Activity.
            // It reports the display now, the space `getLocationOnScreen` already puts every
            // rectangle in. That one never broke, because `ls-api` overwrites both fields with the
            // payload's dimensions before rendering — the SDK's answer was wrong and unread.
            //
            // Minor rather than patch: an existing consumer's modal sub-screens start showing a
            // different picture with no code change on their side. Screens are permanent and the
            // capture is once per screen per install, so a wireframe already stored for a modal
            // stays wrong until the app's version changes. Additive public API on
            // `SkeletonGenerator` for the overlay forms. See `ModalSkeletonTest`.
            // 0.16.1 drops a modal window's own furniture from its wireframe — above all the
            // scrim. 0.16.0 started scanning the modal's window, and a `ModalBottomSheet`'s window
            // is the whole display, so the scrim arrived as a full-screen *filled* node. `Recolour`
            // therefore sampled it, and what it sampled was the whole screen: the dimmed page
            // behind the sheet. The result was a flat mid-grey covering everything above the sheet,
            // painted before the sheet's own children because paint order is pre-order. Keeping the
            // palette instead would have been worse — `IMAGE` is `#2196F3`, a full-screen blue.
            //
            // Dropped rather than recoloured, because a scrim is not part of the modal: it is the
            // previous screen being dimmed, and a sub-screen's wireframe is the modal alone. Only
            // for overlay frames — on an ordinary screen a full-frame fill is usually the page's own
            // background, a real surface that is correct to sample. Zero-area rectangles go with
            // them; Compose bounds can be degenerate, and the server clips such a rectangle to
            // nothing, so it costs bytes to say nothing.
            //
            // Measured on a bottom sheet: 17 rectangles became 9, and every survivor lies inside
            // the sheet.
            //
            // Patch, on the reasoning of 0.14.1: nothing new is reported and no signature changes —
            // a screen that was storing a wireframe of the wrong thing stores the right one.
            // 0.17.0 reports the device's pixel density with a screen.
            //
            // The screen map draws each capture at a size proportional to its pixels, which reads
            // as a statement about how big the device is and is not one. Pixel count is resolution;
            // density is the factor that turns it into inches, and low density is exactly what a
            // large screen has. Measured on the two devices this was reported from: a 1080×2400
            // phone at 2.625 is 411×914 dp, a 2560×1600 tablet at 2.0 is 1280×800 dp — three times
            // the area, and yet its pixel *height* is the smaller number. The map drew the
            // physically larger device as the shorter card, which is what somebody noticed.
            //
            // Nothing here could fix that alone: `width` and `height` were faithful, and no third
            // number existed anywhere in the capture path to make them mean size. `density` now
            // travels with them, `ls-api` stores it — backfilling what it can from the densities
            // already in `ls_sessions.device_info` — and the flow divides by it.
            //
            // Minor. `DataSender.sendScreenData` takes a parameter it did not, so anything
            // implementing that public interface has to be recompiled; and the payload gains a
            // field, which an older server ignores rather than rejecting.
            // 0.18.0 records a scroll again.
            //
            // The recorder does not capture during a screen transition, because a frame taken
            // mid-crossfade shows both screens and its masks can be right for neither. Correct, and
            // unchanged. What was wrong is how a transition was recognised: it asked
            // `CompositionActivity` whether the composition had changed in the last 120 ms and read
            // any yes as a transition. That observer fires on every Compose state application, and a
            // scrolling `LazyColumn` applies state on every frame — so an ordinary scroll was
            // indistinguishable from a crossfade and the whole scroll was discarded.
            //
            // Measured on a Galaxy Tab A7, twenty-second stretches of a list being dragged with
            // recording on, before and after:
            //
            //     pause between gestures    real frames        repeat markers
            //                  0 ms         1  ->  190         196  ->    6
            //                800 ms        18  ->  104         148  ->   35
            //
            // The 18 before were the quiet seconds between drags, captured on the idle interval —
            // which is why nobody watching replays had noticed. Every resting state was recorded and
            // only the motion between them was missing. What was lost was also the worst thing to
            // lose: `interactionCaptureIntervalMs` accelerates the loop tenfold while a finger is
            // down, and that acceleration was producing nothing but repeat markers.
            //
            // `ScreenTransition` now takes the signal from the screen mapper, which is already told
            // when a destination changes. Scrolling never announces one. `CompositionActivity` still
            // closes the window, which is the half it was always good at. There is a two-second cap
            // for the screen whose composition never settles at all — a loading spinner — which the
            // old logic suppressed for as long as the user stayed on it.
            //
            // Two observability additions came out of finding this. `ReplayStats` makes the unique
            // and repeated frame counts readable; they had always been collected and only ever
            // printed from `ReplayIntegration.onTerminate()`, which Android does not call on a real
            // device, and their ratio is the only thing that distinguishes a working recorder from
            // one emitting nothing but repeats. And the capture, mask, encode and spool paths now
            // open `Trace` sections, so the SDK's work is identifiable in a host app's system trace
            // rather than being unlabelled time inside their main thread.
            //
            // Minor. An existing consumer starts reporting frames it was not reporting, with no code
            // change, and `ReplayStats` is new public API. Nothing is removed and no signature
            // changes. Expect the recorded stretches to cost more than they did, because until now
            // they were mostly not being recorded: on that tablet, at a realistic cadence, +240 ms of
            // main thread across twenty seconds and 28% fewer frames delivered by the app. If that
            // is too much for a given audience, `interactionCaptureIntervalMs` is the knob — it has
            // simply never been in effect before.
            // 0.19.0 recaptures a screen when its content arrives.
            //
            // A screen that loads is two screens: the spinner it shows on arrival and the content
            // it becomes when the data lands. The wireframe was taken from the first one — measured
            // on a production metrics screen, the settle detector declared it quiet 139 ms after
            // navigation, because an indeterminate spinner animates on the RenderThread and never
            // produces the snapshot apply or the draw pass the detector watches. The scan stored
            // the shell around a spinner: 54 rectangles where the loaded screen measures 98.
            //
            // No clock fixes that honestly — the screenshot path's flat 5.5 s wait is a guess about
            // every app's network — but the data landing is not silent: `isLoading = false` reaching
            // `collectAsStateWithLifecycle` is a `MutableState` write, and every state write is a
            // snapshot apply. `LateContent` wakes on exactly that event, the mapper recaptures (the
            // settle inside the capture absorbs the recomposition), and a skeleton whose geometry
            // changed is sent again. `ls-api` upserts by slot, so the resend replaces the wireframe
            // and cannot blank the screenshot beside it. Measured on the miniature of that screen
            // in `LateContentTest`: 14 rectangles before the flip, 50 after.
            //
            // The watch ends on events, never on time: a touch (state applied after it is the
            // person's edit, not the screen they arrived at), a navigation, the Activity pausing,
            // or a budget of three examinations — which is what bounds a screen that ticks by
            // itself, a carousel or a clock.
            //
            // Minor. An existing consumer starts resending wireframes it previously sent once, with
            // no code change on their side. No signature changes; `LateContent` is internal.
            // 0.20.0 gives a container the colour it actually has.
            //
            // A wireframe whose most striking element is a red card rendered it white, and the
            // page around it drifted mauve. Containers are stroked rects and stroked rects were
            // never sampled — the largest surfaces on every screen were the only ones never asked
            // about. Pixels no window painted were read as black, because the pooled capture
            // bitmaps are erased to transparent and transparent RGB is zero: that dragged every
            // mean toward black and put a black band under the navigation strip.
            //
            // Sampling containers is not enough by itself, and this is the half that had to be
            // measured. Filling every stroked rect with its sampled colour turned the screen
            // mauve: a full-screen shell contains white cards, one red card and undrawn black,
            // and the mean of that belongs to nothing. So a stroked rect now carries `surface`
            // only when one colour covers at least `Recolour.DOMINANCE` of its pixels — a card
            // that really is red, at 70% and up — and a mixed container keeps its bare outline.
            //
            // The frame carries `v: 2`, which also licenses the server to paint biggest-first.
            // The scan documents pre-order and that does not survive a subcomposition boundary:
            // two full-screen shells arrived after all of their content, which never mattered
            // while containers were hollow and erased the whole picture once they were not.
            //
            // Minor. The payload gains two fields, which an older server ignores rather than
            // rejecting — and ignoring them is exactly the old picture, not a wrong one. Nothing
            // is removed and no signature changes. Requires `ls-media` with `Node.surface` to see
            // any difference; without it the wireframe renders as it did in 0.19.0.
            // 0.21.0 lets a screen's wireframe get better on a later visit.
            //
            // Late content heals the first visit and dies on the first touch; everything after was
            // the cache's to decide, and `isScreenSent` was a boolean — the first wireframe out was
            // the screen's picture for the life of the install. A loading screen whose watch a
            // touch cancelled kept its spinner forever, and every install predating a scanner
            // improvement held wireframes a newer scan would beat.
            //
            // The boolean is now a bar: `CacheManager` remembers the rectangle count of the richest
            // wireframe ever sent for a screen, a later capture ships only when it carries strictly
            // more, and a successful send raises the bar. That converges — equal-or-poorer is
            // silence — and it retires the standing defect that this cache is keyed on *app*
            // version while wireframe quality moves with *SDK* version: an unknown screen reads
            // zero, so a legacy install heals each stale wireframe once on the next visit. A revisit
            // pays only a sub-millisecond hierarchy walk to decide, the recolour capture happening
            // only after a frame has cleared the bar.
            //
            // Measured on the sample across three launches: spinner stored at 37 rects, revisited
            // and upgraded to 81 when the data arrived, then quiet.
            //
            // Minor. An existing consumer starts resending a wireframe it previously sent once, with
            // no code change on their side. No signature changes; the ratchet is internal, and it
            // needs no server change — `ls-api` already upserts a capture by slot.
            // 0.21.1 stops the mask becoming a container's colour.
            //
            // A form screen came back as one grey slab. The grey is `Masking.MASK_COLOR`, and
            // 0.20.0 taught containers to adopt whatever colour dominates their pixels — which on
            // a form is the mask, because a text field is mostly masked text and so is everything
            // wrapping one. Measured on eight fields: `#9C9C9C` as the sampled surface of the
            // scroll column at 92% of its area, the Scaffold at 95%, sixty-odd containers filled.
            //
            // The mask is this SDK's paint, not the app's, so it never becomes a surface — except
            // on `TEXT`, `INPUT` and `IMAGE`, which *are* the masked thing and where grey is what
            // the screen honestly shows.
            //
            // Patch, on the reasoning of 0.14.1: nothing new is reported and no signature changes.
            // A screen that was storing a wireframe of the wrong colour stores the right one.
            // 0.22.0 halves the wire, once the server can inflate it.
            //
            // Measured off a real recording session: 2.29 MB of upstream in thirty-five seconds,
            // 91% of it frame batches — and all of it deflates, including the JPEG: frames of a
            // masked, flat UI shrink 37% because their entropy-coded stream repeats. Breadcrumbs
            // shrink 87%, a skeleton 63%. The same session re-run with compression put 1.38 MB on
            // the wire. The device pays 0.32 ms per 26 KB batch on the encoder thread, measured in
            // `GzipCostProbeTest` with the captured payloads; gzip level 6 via Okio, no new
            // dependency — brotli and zstd were measured and declined, both needing a native
            // encoder per ABI for a win that is absent on the dominant payload.
            //
            // Nothing compresses until the server advertises `X-LS-Accept-Encoding: gzip`: the
            // first send of every process is plain, reads the header, and latches. A gzipped body
            // at a server that cannot inflate is a hard parse failure, not degradation, so the SDK
            // never assumes — which removes every deploy-ordering constraint in both directions.
            //
            // Minor. A consumer starts compressing uploads with no code change on their side, and
            // only against a server that asked for them. Requires `ls-ingest`/`ls-api` with the
            // decompression layer to see any difference; without it, every byte ships as 0.21.1
            // shipped it.
            // 0.22.1 keeps a multiplatform app's Activity out of its own screen map.
            //
            // React Native, Flutter and Compose Multiplatform are all one Activity hosting
            // everything, and `screensReportedByHost` is how an app says so. An integrator who does
            // not know the flag exists gets a node named after that Activity at the top of every
            // session — permanently, since screens are permanent. Compose Multiplatform is what
            // made this worth fixing rather than documenting: a CMP composition is one this SDK can
            // read, so the wireframes and the heatmaps come out right and nothing looks broken
            // enough to send anyone looking for a flag.
            //
            // An Activity that `LightSession.setScreen` names while it is in front now stops
            // reporting itself. The same claim the flag makes, made after the fact instead of in
            // advance, so the map comes out right either way and the SDK logs what it did. Measured
            // on a Compose Multiplatform probe built against the published artifact, navigating
            // from shared code with no flag set: `home -> MainActivity` became `home -> details`.
            //
            // Compared by Activity identity and not by a process-wide "the host has spoken" flag,
            // which passes every obvious test and is wrong for the mixed app: a native app that
            // hand-names one WebView screen would have every other Activity silently unnamed.
            //
            // Patch. Nothing new is reported and no signature changes. The only app whose behaviour
            // moves is one already calling `setScreen` without the flag, and what it loses is a node
            // that should never have been there. No server change.
            // 0.23.0 knows when the app broke, and on which screen.
            //
            // The first pillar that is not replay: crashes and handled exceptions, as error
            // breadcrumbs. An uncaught exception is serialised and spooled to disk synchronously
            // on the crashing thread — measured live at 9ms between the system's FATAL log and
            // the batch on disk — then the previous handler runs with the original throwable, so
            // the crash dialog, logcat and any other reporter behave as if this SDK were absent.
            // Delivery is the next launch's spool drain, which already existed. Handled errors
            // arrive through LightSession.captureException and ride the ordinary flush.
            //
            // Every error carries the screen it happened on, which is the product: not "what
            // broke" but where in the app it broke. When the mapper has not named the screen yet
            // — a startup crash, inside the Compose grace period — the foreground Activity's name
            // stands in, verified live on a crash 500ms into a fresh process.
            //
            // The wire format is a breadcrumb of type "error", because the spool, the retry and
            // the ordering already exist there, and because the ingest preserves unknown crumb
            // types verbatim: a backend that has never heard of errors stores them queryable, so
            // this SDK ships first and the backend catches up with nothing lost.
            //
            // Minor. New public API (captureException), new config flag (captureErrors, on by
            // default — the crashes most worth having are the ones before anyone configured
            // anything). No signature changes, no server requirement.
            // 0.23.1 survives Compose 1.11, and stops running the host's code to do it.
            //
            // Two fixes, one lesson. Compose 1.11 renamed ComposerImpl to GapComposer and
            // turned its `composers` field into an androidx.collection.MutableScatterSet,
            // which is a set in every sense except implementing Iterable — so subcomposition
            // discovery silently found nothing and a LazyColumn screen stored the frame
            // around an empty page. Bracketed to ui-tooling-data 1.11.1 on the CI BOM axis,
            // which exists for precisely this class of break.
            //
            // The first fix read the collection through any zero-argument method returning
            // an Iterable, on the theory that validating the result made a wrong guess
            // harmless. Validating a result does not undo the invocation: the field walk
            // reaches the context's this$0 — a live GapComposer mid-composition — and one
            // such call left a real app's ComposeView measured 0x0 permanently, every screen
            // blank, no exception. Bisected on that app, one component per arm, each arm's
            // marker verified in the artifact and in logcat; reverting one file cured it.
            //
            // The shipped shape: candidate fields must already declare a collection type
            // (Iterable, or the ScatterSet family by name); the only invocation is asSet(),
            // Compose's own side-effect-free view, on values that are ScatterSets. Reading
            // a field is passive; invoking is not, and a walk over another library's
            // internals only gets to do the first.
            //
            // Patch. No API change, no server requirement. Consumers on Compose <= 1.10 see
            // identical behaviour; consumers on 1.11+ get their list screens back.
            // 0.24.0 names Compose destinations without being handed the controller.
            //
            // An app with five destinations had one node in its map called MainActivity, with real
            // wireframes under it — populated, plausible and wrong. The cause was a missing
            // `.withNavigationTracking()`, and the SDK's only defence was a line of advice in
            // logcat. This is the third answer to that shape and the first that does not depend on
            // the app remembering anything: it reported nothing at all before 0.18, then the
            // Activity's own name, which is right when the Activity is one screen and quietly
            // wrong when it is five.
            //
            // What justified leaving it to the host was that Compose keeps no global registry of
            // NavControllers. True, and not the same as unreachable: `rememberNavController()` is
            // remembered, so it sits in the slot table this SDK already walks for subcompositions.
            // `NavControllerReachProbeTest` measures both routes against a NavHost set up the way a
            // forgetful app sets one up — `NavHostController` is there with its route readable,
            // while `Navigation.findNavController` on the decor view returns null, since the
            // view-tree tag is a fragment-host convention NavHost does not follow.
            //
            // When the grace period ends with nothing registered, the composition is asked before
            // falling back to the Activity's name, and what it holds goes through the ordinary
            // `registerComposeNavController` — nested-NavHost shell handling included.
            // `withNavigationTracking()` is still worth calling, and the log now says why: it
            // registers at composition time, so the first three seconds get named too.
            //
            // Reads only. Nothing invokes a method on a live runtime object; 0.23.1 is what that
            // costs when it goes wrong, and a test asserts the window still has a size afterwards.
            //
            // Minor, on 0.18's reasoning: a consumer starts reporting screens it never did, with
            // no code change on their side, and its map gains nodes and edges. No signature
            // changes, no server requirement.
            // 0.25.0 is what the first real app taught. Two lessons, one release.
            //
            // 0.24.0's discovery looked once, when the grace period ended — which quietly assumed
            // the NavHost exists by then. The first app integrated that this SDK did not write
            // keeps its NavHost behind a StateFlow<Destination?> filled by an auth check, the
            // stock async-start shape; resolve slower than the grace period and the one-shot scans
            // a composition that honestly holds no controller, names the Activity, and never looks
            // again — which install gets the wrong map is decided by its network. NavControllerWatch
            // closes that: what mounts a NavHost is a state write, every state write commits as a
            // snapshot apply (LateContent's argument, now applied to discovery), so the watch
            // rescans when the composition announces it may have changed — debounced to under two
            // bounded read-only walks a second, armed only while a Compose Activity is in front.
            // Verified against a 5s async start: fallback at 3s, controller tracked at 5.7s,
            // alpha/beta/gamma named from there.
            //
            // The same app broke a naming assumption: type-safe navigation generates routes from
            // the @Serializable destination's serial name, so five screens arrived as
            // com.thisames...destination.dispatchdetail/{dispatchid} — a package path nobody
            // declared, lower-cased into unreadability by a rule that assumed routes are written
            // by hand. A route whose head holds a dot is now named by the segment after the last
            // one, case kept: DispatchDetail/{dispatchId}, the name the author actually wrote.
            //
            // Minor, and it renames screens for type-safe apps: the server keys on the name, so
            // FQN-named rows stop receiving data and can be deleted — cleanup, not migration.
            // No signature changes, no server requirement.
            // 0.26.0 sees a dialog that has no Compose in it.
            //
            // Modal detection read Compose semantics and nothing else, so a dialog built out of
            // Views answered `null` and never became a node — which is most dialogs outside a
            // Compose app. Measured on the React Native sample: RN's `Modal` is a plain `Dialog`
            // holding `ReactViewGroup`s, its window reached the reader exactly as a Compose dialog
            // does, and the read came back `result=null` for want of a `RootForTest` to find.
            // `Alert.alert` is an AppCompat `AlertDialog` and failed identically. Neither ever
            // appeared in the map, on any version. The iOS SDK has no such gap because it
            // recognises a modal by controller class, which is blind to the UI framework.
            //
            // The hard half was telling a dialog from a popup without semantics to ask. Compose
            // separates them with `IsPopup` versus `IsDialog`; there is no such flag on a View, and
            // treating "a window appeared" as "a screen appeared" mints a screen every time
            // somebody opens a combo box — measured, a dropdown adds a root view exactly like a
            // dialog does. The separator is the window's own type: `FIRST_SUB_WINDOW` divides
            // application windows, which is what a `Dialog` gets, from sub-windows, which is what
            // every `PopupWindow` gets. Focus is required too, because a window that cannot take
            // focus is chrome over what someone was already looking at rather than a place they
            // went.
            //
            // Minor, on 0.18's reasoning, and the same shape of consequence: a non-Compose consumer
            // starts reporting dialogs it never did, with no code change on their side, and its map
            // gains nodes and edges. Nothing existing is renamed. No signature changes, no server
            // requirement.
            // 0.27.0 records the requests a session made, and is the first artifact to carry
            // anything since 0.25.0.
            //
            // 0.26.0 was tagged in this file and never published — the registry goes straight from
            // 0.25.0 to here — so the gap is deliberate and this release carries everything the
            // 0.26.0 note above describes as well: the dialog with no Compose in it, the Compose
            // image that declares no semantics, the corners a modal actually has, the first frame
            // of a session being a real one, and a screenshot that honours cancellation.
            //
            // What is new beyond that note is the network pillar. One row per HTTP request, on the
            // screen that made it, which is the pairing nobody else can offer: a failing endpoint
            // is a backend fact, and a failing endpoint on the checkout screen right before the
            // session ends is a product one. Opt-in twice over — a config flag and an interceptor
            // the customer installs on the client they own — because this is the first thing the
            // SDK does that sits in the path of the app's own traffic, and the worst failure of
            // everything else is a wrong number on a page while the worst failure here is a
            // request that never completes.
            //
            // Sampling is by session rather than by request, derived from the session id rather
            // than rolled and remembered, and every failure is kept whatever the rate says. A coin
            // per request is the obvious design and it punches holes in the one view this product
            // has of value; a rare failure sampled at a tenth is seen once in ten occurrences,
            // which is the occurrence somebody phoned about.
            //
            // Minor. Two additive config fields, one new public class, and a consumer who asks for
            // none of it is unaffected: `captureNetwork` defaults off, so an app that upgrades and
            // changes nothing sends exactly what it sent before.
            // 0.28.0 stops double-counting a request, and prototypes installing the
            // interceptor without a line of Kotlin.
            //
            // Two copies of `LightSessionInterceptor` in one chain recorded the request twice —
            // silently, and every number the network pillar produces would have been double.
            // Reachable today by handing the interceptor to a client as both an application and a
            // network interceptor, or adding it twice across two builders; and reachable by
            // construction the moment a build-time transform inserts it into a call site the
            // source already covered, which is how it was found. The first instance now tags the
            // request and a later one steps aside, so the number is one per request whatever the
            // arrangement.
            //
            // The transform itself ships as source, not as an artifact. `lightsession-gradle-plugin`
            // rewrites `OkHttpClient$Builder.build()` to add the interceptor, which is one line in
            // a consumer's build file instead of one line per client — and reaches a client built
            // inside a dependency, which a hand-written line cannot. It is not published: a release
            // build under R8 and any AGP other than 8.7.3 are untested, and its scope default is a
            // product decision rather than an implementation one.
            //
            // Patch would be defensible for the fix alone. Minor because the module gains a second
            // buildable component, and because an app that was double-counting sees its numbers
            // halve on upgrade — right, and still a change in what the dashboard says.
            version = "0.28.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("LightSession Android")
                description.set("LightSession SDK for Android")
                url.set("https://github.com/LightSession/lightsession-android")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("lightsession")
                        name.set("LightSession Team")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/LightSession/lightsession-android")
            credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}
