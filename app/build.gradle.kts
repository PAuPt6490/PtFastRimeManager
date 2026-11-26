plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pt.fastrime.manager"
    compileSdk = 35

    // 只有在显式传入 -PenableNativeRime=true（本机已装好 NDK）时才编译 cpp/JNI，
    // 否则保持纯 Kotlin 构建，避免因缺少 NDK 直接构建失败。详见 README「关于 JNI」。
    val enableNativeRime = (findProperty("enableNativeRime") as String?)?.toBoolean() == true
    if (enableNativeRime) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        ndkVersion = (findProperty("ndkVersion") as String?) ?: "26.1.10909125"
    }

    defaultConfig {
        applicationId = "com.pt.fastrime.manager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // 仓库里**不附带**签名文件（私钥不入库）。存在 keystore/debug.keystore 时使用它，
    // 否则回退到 Android 默认调试签名（~/.android/debug.keystore）。
    val localKeystore = rootProject.file("keystore/debug.keystore")
    signingConfigs {
        getByName("debug") {
            if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    sourceSets { getByName("main") { jniLibs.srcDirs("src/main/jniLibs") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 轻量设置项（当前激活方案、上次浏览目录）用 DataStore；方案清单用 Room
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Shizuku 服务端 AIDL（IShizukuService / IRemoteProcess），用于通过 binder 执行 shell
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
