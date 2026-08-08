import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Shopee Affiliate API credentials — stored in local.properties, never committed to git.
// When blank, the affiliate module gracefully degrades (no API calls, falls back to GitHub catalog).
val shopeeAppId = localProps.getProperty("SHOPEE_APP_ID") ?: ""
val shopeeAppSecret = localProps.getProperty("SHOPEE_APP_SECRET") ?: ""

// ── The RAZStudio template repo — read from the monorepo root ──────────────────────────────────
val templateRepoProps = Properties().apply {
    val f = rootProject.file("../template-repo.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val razstudioGithubOwner = templateRepoProps.getProperty("RAZSTUDIO_GITHUB_OWNER") ?: ""
val razstudioGithubRepo = templateRepoProps.getProperty("RAZSTUDIO_GITHUB_REPO") ?: ""
if (razstudioGithubOwner.isBlank() || razstudioGithubRepo.isBlank()) {
    throw GradleException(
        "template-repo.properties at the monorepo root must define RAZSTUDIO_GITHUB_OWNER and " +
            "RAZSTUDIO_GITHUB_REPO. It is committed on main — if it is missing, this is not a full " +
            "checkout of the repository."
    )
}

android {
    namespace = "com.razstudio.opsapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.razstudio.opsapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "RAZSTUDIO_GITHUB_OWNER", "\"$razstudioGithubOwner\"")
        buildConfigField("String", "RAZSTUDIO_GITHUB_REPO", "\"$razstudioGithubRepo\"")
        buildConfigField("String", "SHOPEE_APP_ID", "\"$shopeeAppId\"")
        buildConfigField("String", "SHOPEE_APP_SECRET", "\"$shopeeAppSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room (local Connected_Cafe store)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp (OperatorApiClient)
    implementation(libs.okhttp)

    // Encrypted storage (PromoTokenStore's GitHub PAT)
    implementation(libs.androidx.security.crypto)

    // Image loading
    implementation(libs.coil.compose)

    // ZXing: QR code generation only (table QR cards, owner QR share)
    implementation(libs.zxing.core)

    // WorkManager: provisioning pipeline
    implementation(libs.work.runtime.ktx)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")

    // Robolectric: LinkGenerator/AffiliateProductFilter tests need android.net.Uri
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
