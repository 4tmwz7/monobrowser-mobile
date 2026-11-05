import java.util.Properties

// AGP 9 ships built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- version: single source of truth is `monobrowser.version` in gradle.properties ----
val appVersionName: String = providers.gradleProperty("monobrowser.version").get().trim()
val appVersionCode: Int = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(appVersionName)
    ?.destructured?.let { (major, minor, patch) ->
        require(minor.toInt() < 100 && patch.toInt() < 100) { "minor/patch must be < 100 (versionCode scheme)" }
        major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    } ?: error("monobrowser.version must look like X.Y.Z, got '$appVersionName'")

// ---- release signing: keystore.properties (local) or MONO_KEYSTORE_* env vars (CI); neither -> unsigned release ----
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("storeFile", "MONO_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.isFile }
val releaseStorePassword = signingValue("storePassword", "MONO_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "MONO_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MONO_KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

// ---- updater: GitHub API base (override for tests: -Pupdate.baseUrl=http://10.0.2.2:8000/repos/o/r), checks off in debug ----
val updateBaseUrl: String = providers.gradleProperty("update.baseUrl").orNull?.trim()?.trimEnd('/')
    ?: "https://api.github.com/repos/4tmwz7/monobrowser-mobile"
val updateReleasesPage = "https://github.com/4tmwz7/monobrowser-mobile/releases"
val debugUpdates: Boolean = providers.gradleProperty("update.enabled").orNull == "true"

android {
    namespace = "com.voluene.monobrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voluene.monobrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        buildConfigField("String", "RELEASES_PAGE_URL", "\"$updateReleasesPage\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Separate package: installs next to the release app instead of replacing it.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "UPDATE_CHECKS", debugUpdates.toString())
        }
        release {
            buildConfigField("boolean", "UPDATE_CHECKS", "true")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // lintVitalAnalyzeRelease crashes on this project (AGP 9 lint bug); `./gradlew lint` still works on demand.
    lint {
        checkReleaseBuilds = false
    }
}

// Release output: MonoBrowser-<version>.apk (the updater and the release workflow depend on this name).
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { it.outputFileName.set("MonoBrowser-$appVersionName.apk") }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// adblock: Rust engine (native/adblock) via cargo-ndk -> jniLibs
apply(from = "adblock-native.gradle.kts")
android.sourceSets.getByName("main").jniLibs.directories.add("build/rustJniLibs")

// ---- UI tests (opt-in): Robolectric renders the real Compose UI on the JVM, without a device ----
// ./gradlew testDebugUnitTest -Pui.tests=true -Padblock.skipNative=true   (screenshots land in build/screenshots)
// Off by default: Robolectric downloads a ~200 MB Android image on first use, which CI does not need for every push.
if (providers.gradleProperty("ui.tests").orNull == "true") {
    android.sourceSets.getByName("test").kotlin.directories.add("src/uiTest/kotlin")
    android.testOptions.unitTests.isIncludeAndroidResources = true
    dependencies {
        testImplementation(libs.robolectric)
        testImplementation(platform(libs.androidx.compose.bom))
        testImplementation(libs.androidx.compose.ui.test.junit4)
        debugImplementation(libs.androidx.compose.ui.test.manifest)
    }
    tasks.withType<Test>().configureEach {
        systemProperty("screenshots.dir", rootProject.file("build/screenshots").absolutePath)
        maxHeapSize = "3g"
    }
}
