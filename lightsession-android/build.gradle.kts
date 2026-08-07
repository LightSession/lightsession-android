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
            version = "0.14.0"

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
