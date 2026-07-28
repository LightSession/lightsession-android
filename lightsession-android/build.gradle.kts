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
            version = "0.4.0"

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
