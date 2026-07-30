import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signingReady = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl = localProps.getProperty("SUPABASE_URL") ?: ""
val supabaseAnonKey = localProps.getProperty("SUPABASE_ANON_KEY") ?: ""
val websiteUrl = localProps.getProperty("WEBSITE_URL") ?: "https://tani-tom-yam.pages.dev"

android {
    namespace = "com.warungtomyam.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.warungtomyam.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
        vectorDrawables.useSupportLibrary = true
        // English is the base locale; Malay is dictionary-generated (values-ms).
        resourceConfigurations += listOf("en", "ms")

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "WEBSITE_URL", "\"$websiteUrl\"")
    }

    if (signingReady) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            // NOTE: no applicationIdSuffix — debug builds share the applicationId
            // (com.warungtomyam.pos) so they update the installed app in place rather than
            // creating a second ".debug" package. Re-add a suffix only if you deliberately
            // want debug + release installed side-by-side.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Encrypted storage
    implementation(libs.androidx.security.crypto)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ESC/POS Bluetooth thermal printer
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")

    // ZXing: QR code generation (PDF cards, staff invite) + decoding scanned frames
    implementation("com.google.zxing:core:3.5.3")

    // CameraX: in-app QR scanner for ordering-device onboarding
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // OkHttp: WebSocket client for Supabase Realtime (Spike 02)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20231013")

    // Image loading for compose
    implementation("io.coil-kt:coil-compose:2.4.0")

    // WorkManager: periodic backup reminders
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Unit tests (JVM) — org.json is already on the classpath for the parser tests
    testImplementation("junit:junit:4.13.2")
}
