plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spektra.android"
    compileSdk = 34
    // 用本地 NDK r27c（sdk/ndk/27.2.12479018 是 D://xm//toolchain//ndk 的目录联接），
    // 否则 AGP 会按自己的默认版本再下载一个 NDK 到 SDK 目录里。
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.spektra.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 933
        versionName = "0.8.93-r33"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                // 引擎与 JNI 桥接均静态链接 C++ 运行时，避免 APK 缺 libc++_shared.so 导致真机闪退
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // [r24] HEIF 编码器：androidx.heifwriter:heifwriter:1.1.0（AOSP HeifWriter 的 Jetpack 版）。
    // 系统里的 android.media.HeifWriter 是 @SystemApi（android.jar 里没有、api-versions 里也没有），
    // 在 Android 15/16 上被 hidden-API 拦截 —— 真机上 Class.forName 取不到，HEIF 因此被判成
    // "本机不支持"。Jetpack 版同一套实现，但只用公开的 MediaMuxer/MediaCodec。
    // 以本地 jar 引入（AAR 的 minSdk=28 会污染 manifest merger，jar 不会）。
    implementation(files("libs/heifwriter.jar"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
