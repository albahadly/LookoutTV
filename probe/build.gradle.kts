import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// NVR details live in local.properties (never committed):
//   nvr.host=192.168.1.100
//   nvr.port=554
//   nvr.user=admin
//   nvr.password=yourPassword
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun nvr(key: String, default: String): String {
    val raw = localProps.getProperty("nvr.$key") ?: default
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.albahadly.swanntv.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.albahadly.swanntv.probe"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-probe"

        buildConfigField("String", "NVR_HOST", nvr("host", "192.168.1.100"))
        buildConfigField("String", "NVR_PORT", nvr("port", "554"))
        buildConfigField("String", "NVR_USER", nvr("user", "admin"))
        buildConfigField("String", "NVR_PASSWORD", nvr("password", "PASSWORD"))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.annotation)
    implementation(libs.activity)
}
