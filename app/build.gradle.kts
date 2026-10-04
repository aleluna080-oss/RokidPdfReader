plugins {
    id("com.android.application")
}

android {
    namespace = "com.alexluna.rokidpdfreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alexluna.rokidpdfreader"
        minSdk = 29
        targetSdk = 32
        versionCode = 3
        versionName = "0.3.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        disable += "AppLinkUrlError"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")

    // QR / Wi-Fi transfer
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // CameraX. 1.4.2 is already compatible with compileSdk 35 in this project.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")

    // MediaPipe Tasks Vision for on-device hand landmarks + canned gestures.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
}
