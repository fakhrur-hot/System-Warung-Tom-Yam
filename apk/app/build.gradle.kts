import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.oss.licenses)
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
val websiteUrl = localProps.getProperty("WEBSITE_URL") ?: ""
val deepLinkHost = localProps.getProperty("DEEP_LINK_HOST") ?: "your-cafe.pages.dev"

// The source package (namespace) is the constant vendor base `com.razstudio.pos` on every branch —
// this keeps git merges between the template (main) and café builds (e.g. tani-tom-yam) conflict-free.
// The APPLICATION ID, which is what Android uses as the installed-app identity (and what the signing
// cert + Play/update path bind to), is overridable per café via local.properties `APPLICATION_ID`.
// Template default = the vendor base; a café build sets its own (e.g. com.warungtomyam.pos) to keep
// its existing installed identity and update path.
val cafeApplicationId = localProps.getProperty("APPLICATION_ID") ?: "com.razstudio.pos"

// CAFE_NAME: the display name shown in the app launcher. Overridable per café via local.properties
// `CAFE_NAME`. Template default = "RAZ POS" (the original app_name value from strings.xml).
val cafeName = localProps.getProperty("CAFE_NAME") ?: "RAZ POS"

// CAFE_PROFILE_DIR: optional path to a per-café resource directory. When set and the directory
// exists, its res/ sub-directory is added as an extra Android resource source set, and Android's
// resource merger overlays those resources over the template's. A café supplying
// res/raw/qr_default_logo.jpg, launcher icons (mipmap-*) and/or a colors.xml override will have
// them automatically merged at build time; a café supplying nothing (or with no profile at all)
// gets the template's resources unchanged. A missing or invalid path NEVER fails the build.
//
// Expected profile directory layout (relative to the path given in CAFE_PROFILE_DIR):
//   res/
//     raw/
//       qr_default_logo.jpg          ← café QR logo (overrides template logo)
//     mipmap-mdpi/
//       ic_launcher.png              ← launcher icon at each density bucket
//     mipmap-hdpi/
//       ic_launcher.png
//     mipmap-xhdpi/
//       ic_launcher.png
//     mipmap-xxhdpi/
//       ic_launcher.png
//     mipmap-xxxhdpi/
//       ic_launcher.png
//     values/
//       colors.xml                   ← theme palette override (TomYam50…TomYam900, etc.)
//
// Only files the café actually wants to override need to be present — any file absent in the
// profile directory is inherited from the template. (Requirement 4.1, 4.3, 4.4)
val cafeProfileDir: File? = localProps.getProperty("CAFE_PROFILE_DIR")
    ?.let { file(it) }
    ?.takeIf { it.exists() && it.isDirectory }

android {
    namespace = "com.razstudio.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = cafeApplicationId
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
        // DEEP_LINK_HOST: manifest placeholder for the /join deep link host (Requirement 4.1, 4.2).
        manifestPlaceholders["deepLinkHost"] = deepLinkHost
        // CAFE_NAME: generated string resource for the app launcher name. Overrides the value
        // that would otherwise be in res/values/strings.xml (Requirement 4.1, 4.2). Must be deleted
        // from strings.xml to avoid a duplicate resource error (resValue generates into a separate
        // generated/res/ directory that collides with res/values entries of the same name).
        resValue("string", "app_name", cafeName)
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
            // (com.razstudio.pos) so they update the installed app in place rather than
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

    // Room schema export: KSP writes schema JSON files here so MigrationTestHelper can validate
    // post-migration schema structures in instrumented migration tests (Requirement 8.1, 12.6).
    // The schemas/ directory is committed to source control as a migration audit trail.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // MigrationTestHelper reads the exported schema JSON (app/schemas/<db>/<version>.json) through
    // the instrumentation context's assets. Under Robolectric that resolves to the assets of the
    // VARIANT being tested, not the `test` source set — pointing it at `test` still fails with
    // "Cannot find the schema file in the assets folder. Missing file: ...AppDatabase/11.json".
    // Attaching to `debug` works (unit tests run against the debug variant) and keeps the schema
    // JSON out of the release APK, which would otherwise ship a few KB it has no use for.
    sourceSets {
        getByName("debug") { assets.srcDirs("$projectDir/schemas") }

        // CAFE_PROFILE_DIR: when present, add the café profile's res/ directory as an extra
        // resource source set. Android's resource merger automatically overlays profile resources
        // over the template's — no conditional logic needed in any Kotlin/Java consumer.
        // When CAFE_PROFILE_DIR is absent or the directory doesn't exist, this block is skipped
        // entirely, so the template resources are used as-is (Requirement 4.4).
        if (cafeProfileDir != null) {
            val profileResDir = File(cafeProfileDir, "res")
            if (profileResDir.exists()) {
                getByName("main") { res.srcDirs(profileResDir) }
                logger.lifecycle("CAFE_PROFILE_DIR: merging resources from ${profileResDir.absolutePath}")
            } else {
                logger.lifecycle("CAFE_PROFILE_DIR set to '${cafeProfileDir.absolutePath}' but no res/ sub-directory found — template resources used")
            }
        }
    }

    lint {
        // Fails the build on any lint ERROR (not warning) — safe to enable now that the 5
        // pre-existing errors are cleared (2 fixed real gaps in OrderingForegroundService's
        // broadcasts, 2 confirmed lint false positives isolated into @RequiresApi/@SuppressLint
        // helpers, see git history 2026-07-30). ~179 pre-existing WARNINGS remain untriaged —
        // warningsAsErrors stays false so this doesn't retroactively fail the build on those;
        // only NEW errors introduced going forward will fail.
        abortOnError = true
        warningsAsErrors = false
        // Also lints library/module dependencies, not just this module's own source.
        checkDependencies = true
        // ProtectedPermissions is a real AGP lint check (flags declaring a signature/system
        // permission this app can't hold) but this app only uses normal-protection permissions,
        // so it's inert here — enabled for defense-in-depth against a future permission add.
        enable += setOf("ProtectedPermissions", "UnusedResources")
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

    // JSON parsing — test-only ON PURPOSE. Android's framework already provides org.json.*, and
    // bundling the Maven artifact into the APK is actively dangerous: in json-20231013,
    // JSONException extends RuntimeException, whereas Android's built-in JSONException extends
    // Exception. The framework class wins at runtime (boot classpath), so any library code R8
    // compiled against the bundled copy fails verification on-device. That is not theoretical —
    // adding play-services-ads pulled in androidx.webkit's onPostMessage, and the release build
    // crashed on launch with:
    //   java.lang.VerifyError: Verifier rejected class ...onPostMessage...
    //   register v5 has type Reference: org.json.JSONException
    //   but expected Reference: java.lang.RuntimeException
    // The JVM parser tests still need it on the classpath, hence testImplementation.
    testImplementation("org.json:json:20231013")

    // Image loading for compose
    implementation("io.coil-kt:coil-compose:2.4.0")

    // Third-party licence notices. MIT and Apache-2.0 require their copyright notices and
    // licence texts to be reproduced in a distributed binary — including a proprietary one, which
    // this is (see LICENSE / THIRD-PARTY-NOTICES.md). The companion Gradle plugin enumerates the
    // real dependency set at build time, so the screen cannot silently go stale when a dependency
    // is added. This is the project's only hard open-source obligation; do not remove it.
    // Pinned to 17.1.0 deliberately: 17.2.2+ pulls androidx.navigation3, which requires AGP 8.9.1
    // while this project is on 8.7.2 (verified — checkDebugAarMetadata fails on 7 transitive deps).
    // Revisit when AGP is upgraded; the licence screen needs nothing newer.
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")

    // Google Mobile Ads SDK — for native in-app banner ads (Table View)
    implementation("com.google.android.gms:play-services-ads:23.2.0")

    // Guava — required for CameraX and Google Mobile Ads SDK
    implementation("com.google.guava:guava:33.0.0-android")

    // WorkManager: periodic backup reminders
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Ktor server: embedded HTTP listener for LanServer (Requirement 4.2)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Unit tests (JVM) — org.json is already on the classpath for the parser tests
    testImplementation("junit:junit:4.13.2")

    // Room in-memory database testing (JVM via Robolectric)
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

