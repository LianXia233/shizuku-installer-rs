plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.lianxia233.shizukuinst"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lianxia233.shizukuinst"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Rust 产物由 scripts/build.sh 通过 cargo-ndk 生成后放入 src/main/jniLibs。
    // 这里不注册 NDK 构建任务，保证 Gradle 与 Rust 工具链解耦。
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
