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
    implementation("com.squareup.radiography:radiography:2.7")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.lightsession"
            artifactId = "lightsession-android"
            // Distinct from the published 0.1.11-alpha on purpose: that artifact is
            // the one hardcoded to a laptop's LAN address, and a consumer resolving
            // it by accident would look like the SDK silently not working.
            version = "0.2.0-local"

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
