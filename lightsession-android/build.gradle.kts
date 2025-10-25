plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lightsession"
    compileSdk = 36

    defaultConfig {
        minSdk = 28

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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("com.squareup.curtains:curtains:1.2.5")

    // Use as versões do TOMl para garantir consistência
    // Certifique-se de que estas versões correspondem às do aplicativo principal!
    implementation("androidx.navigation:navigation-fragment-ktx:${libs.versions.androidx.compose.navigation.get()}")
    implementation("androidx.navigation:navigation-ui-ktx:${libs.versions.androidx.compose.navigation.get()}")
    implementation("androidx.navigation:navigation-compose:${libs.versions.androidx.compose.navigation.get()}")

    // Adicione explicitamente as dependências comuns do Compose que estão no seu aplicativo
    // Use 'api' para que elas sejam transitivamente disponíveis para o módulo principal
    api("androidx.compose.ui:ui:${libs.versions.androidx.compose.common.get()}")
    api("androidx.compose.runtime:runtime-livedata:${libs.versions.androidx.compose.common.get()}")
    api("androidx.compose.material:material:${libs.versions.androidx.compose.common.get()}")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}