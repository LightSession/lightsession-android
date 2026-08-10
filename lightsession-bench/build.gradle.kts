plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose)
}

android {
    namespace = "com.lightsession.bench"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lightsession.bench"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // No shrinking, in either build type. R8 would strip and inline the SDK differently from
        // the way a host app's own configuration does, so a "code footprint" measured here would
        // be a number about this module's proguard rules rather than about the library. The
        // install cost is measured by comparing two APKs, not by reading it off a running process.
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(project(":lightsession-android"))

    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.runtime:runtime:1.7.0")
    implementation("androidx.compose.foundation:foundation:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // `implementation`, not the usual `debugImplementation`.
    //
    // Two reasons. The watch calls live in main source — the point is to name the objects that
    // should die when recording stops, not to wait for LeakCanary to notice a retained Activity on
    // its own — and this module is an instrument that is never published, so there is no release
    // build to keep it out of.
    //
    // It stays *quiet* during a measurement: dumping the heap costs hundreds of milliseconds and
    // tens of megabytes in this same process, which would land in the middle of the numbers being
    // collected. `LeakHunt` turns dumping on and off around that.
    implementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
