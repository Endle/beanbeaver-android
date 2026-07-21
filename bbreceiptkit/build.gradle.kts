plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.beanbeaver.bbreceiptkit"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // UniFFI-generated Kotlin (git-ignored until first ./build-android.sh)
            java.srcDirs("src/main/kotlin")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // UniFFI 0.28 Kotlin bindings use JNA. The Android AAR ships the right
    // native loader pieces; plain jar is not enough on-device.
    api("net.java.dev.jna:jna:5.15.0@aar")
}
