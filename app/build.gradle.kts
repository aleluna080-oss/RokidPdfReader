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
        versionCode = 2
        versionName = "0.2.0"
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

    // Servidor HTTP local para recibir PDFs desde el teléfono.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Generación del código QR dentro de las Rokid.
    implementation("com.google.zxing:core:3.5.3")
}
