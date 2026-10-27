import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Cameras are set up in the app's Settings screen. Optionally, local.properties (never
// committed) pre-fills the camera list on a fresh install, without the password, which is
// never compiled into the app (enter it in Settings):
//   nvr.host=192.168.1.100
//   nvr.port=554
//   nvr.user=admin
//   nvr.names=Front door,Driveway,Back yard,Garage          (one per channel, up to 16)
//   nvr.mainPath=/rtsp/streaming?channel={ch}&subtype=0
//   nvr.subPath=/rtsp/streaming?channel={ch}&subtype=1
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun nvr(key: String, default: String): String {
    val raw = localProps.getProperty("nvr.$key") ?: default
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.albahadly.swanntv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.albahadly.swanntv"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        buildConfigField("String", "NVR_HOST", nvr("host", ""))
        buildConfigField("String", "NVR_PORT", nvr("port", "554"))
        buildConfigField("String", "NVR_USER", nvr("user", "admin"))
        buildConfigField("String", "NVR_NAMES", nvr("names", ""))
        buildConfigField("String", "NVR_MAIN_PATH", nvr("mainPath", "/rtsp/streaming?channel={ch}&subtype=0"))
        buildConfigField("String", "NVR_SUB_PATH", nvr("subPath", "/rtsp/streaming?channel={ch}&subtype=1"))
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded with ADB for now; replace with a real signing config before sharing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.annotation)
    implementation(libs.zxing.core) // QR code for phone setup
}
