plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Prefer android/models/ (standalone); fall back to sibling iOS models/ when
// this tree still lives next to beanbeaver-ios.
val modelCandidates = listOf(
    rootProject.projectDir.resolve("models"),
    rootProject.projectDir.parentFile.resolve("models"),
)
val modelsDir = modelCandidates.firstOrNull {
    it.resolve("PP-OCRv5_mobile_det.onnx").isFile
} ?: modelCandidates.first()
val modelAssetDir = layout.projectDirectory.dir("src/main/assets/models")

val syncOcrModels by tasks.registering(Copy::class) {
    description = "Copy PP-OCRv5 ONNX models into app assets"
    from(modelsDir) {
        include("*.onnx")
    }
    into(modelAssetDir)
    doFirst {
        if (!modelsDir.resolve("PP-OCRv5_mobile_det.onnx").isFile) {
            throw GradleException(
                "Missing OCR models in ${modelsDir.absolutePath}. " +
                    "Run ./scripts/fetch-models.sh or place the three .onnx files " +
                    "under android/models/ (or ../models/ when co-located with iOS).",
            )
        }
    }
}

android {
    namespace = "com.beanbeaver.app"
    compileSdk = 35
    // Pin build-tools for reproducible builds; install this version via the
    // Android Studio SDK Manager (or `sdkmanager "build-tools;35.0.0"`).
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.beanbeaver.app"
        // Android 14+ — heavy on-device ONNX; older phones aren't a realistic target.
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MVP ships arm64-v8a only (ort has no x86_64-linux-android prebuild).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    packaging {
        jniLibs {
            // ORT + UniFFI both ship .so; keep debug symbols out of release later if needed.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(syncOcrModels) }

dependencies {
    implementation(project(":bbreceiptkit"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Photo picker (Android 13+ system picker; backport via activity)
    implementation("androidx.activity:activity-ktx:1.9.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
