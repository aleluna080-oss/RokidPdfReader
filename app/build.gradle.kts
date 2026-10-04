plugins {
    id("com.android.application")
}

android {
    namespace = "com.alexluna.rokidpdfreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alexluna.rokidpdfreader"
        minSdk = 29
        // Conservador para sideload en Rokid/YodaOS.
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
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
        // La APK se distribuirá por sideload, no Google Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")
}
