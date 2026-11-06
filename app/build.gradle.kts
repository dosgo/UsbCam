import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.dosgo.usbcam"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dosgo.usbcam"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // 只打 arm64，机器是 arm64
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // UVC 库本地化：从 jitpack AAR 中抽取的 classes.jar + arm64 so（jniLibs）
    implementation(files("libs/usbcamera.jar"))
}
