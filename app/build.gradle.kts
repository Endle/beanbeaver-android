import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload-signing config for Play. Kept out of git: create keystore.properties
// (see keystore.properties.example) pointing at your upload keystore. When it's
// absent — CI, contributors, plain debug builds — the release variant simply
// stays unsigned and you sign/upload manually. Play App Signing holds the real
// app-signing key; this is only the resettable upload key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasUploadKeystore = keystorePropsFile.isFile &&
    keystoreProps.getProperty("storeFile") != null

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

// The beanbeaver-core (on-device scan engine) version this app is built
// against, shown in the About footer. Read from the resolved Cargo.lock pin —
// the git tag + short commit — so it can't drift from the .so actually linked.
// This is the Android twin of iOS build-xcframework.sh emitting CoreVersion.swift;
// we resolve it in Gradle instead of build-android.sh to keep that script's
// touchy host-PATH handling alone.
val coreVersion: String = run {
    val lock = rootProject.projectDir.resolve("Cargo.lock")
    val fallback = "unknown"
    if (!lock.isFile) return@run fallback
    // e.g. source = "git+https://github.com/Endle/beanbeaver-core?tag=v0.3.3#045203a…"
    val source = lock.readLines()
        .dropWhile { it.trim() != "name = \"bb-receipt-ffi\"" }
        .firstOrNull { it.trimStart().startsWith("source = ") }
        ?: return@run fallback
    val tag = Regex("""[?&]tag=([^#"&]+)""").find(source)?.groupValues?.get(1)
    val shortSha = Regex("""#([0-9a-f]{7,40})""").find(source)?.groupValues?.get(1)?.take(7)
    when {
        tag != null && shortSha != null -> "$tag ($shortSha)"
        tag != null -> tag
        shortSha != null -> shortSha
        else -> fallback
    }
}

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

/**
 * Ship the legal documents inside the app so they read with no network — which
 * matters for an app whose whole pitch is that it works offline. Copied from the
 * repo root rather than duplicated under assets/, so the file a reader sees in
 * the repo and the file the app displays can't drift (the Android analog of the
 * iOS target referencing `../PRIVACY.md` directly).
 */
val syncLegalDocs by tasks.registering(Copy::class) {
    description = "Copy PRIVACY.md and THIRD_PARTY_NOTICES.md into app assets"
    from(rootProject.projectDir) {
        include("PRIVACY.md", "THIRD_PARTY_NOTICES.md")
    }
    into(layout.projectDirectory.dir("src/main/assets/legal"))
}

android {
    namespace = "com.zhenbo.beanbeaver"
    compileSdk = 36
    // Pin build-tools for reproducible builds; install this version via the
    // Android Studio SDK Manager (or `sdkmanager "build-tools;36.0.0"`).
    buildToolsVersion = "36.0.0"
    // Must be pinned, and must match what build-android.sh compiled with — see
    // bb.ndkVersion in gradle.properties for why (AGP silently stops stripping
    // and stops producing Play symbols when it can't resolve an NDK).
    ndkVersion = providers.gradleProperty("bb.ndkVersion").get()

    defaultConfig {
        applicationId = "com.zhenbo.beanbeaver"
        // Android 14+ — heavy on-device ONNX; older phones aren't a realistic target.
        minSdk = 34
        // Android 16. Play requires new apps/updates to target within one year
        // of the latest release (API 36 required from 2026-08-30).
        targetSdk = 36
        // Every Play upload needs a unique, higher versionCode. 4 is burned:
        // 0.4.0 reached Play carrying a PROFILE=debug native library (the
        // 224 MB bundle that verifyReleaseNativeProfile now refuses), so the
        // fixed build has to go up rather than replace it.
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Surfaced in the About footer (see BeanBeaverApp HomePane).
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")

        // MVP ships arm64-v8a only (ort has no x86_64-linux-android prebuild).
        //
        // debugSymbolLevel FULL uploads native (ORT/Rust) symbols to Play so
        // native crashes in the on-device scan are symbolicated in the console.
        // FULL rather than SYMBOL_TABLE despite the release .so having no
        // .debug_* sections (cargo release is debug = false): FULL runs
        // --only-keep-debug, which emits a .dbg of essentially the symbol table
        // and gives function-name symbolication. SYMBOL_TABLE would run
        // --strip-debug, which on a file with no debug sections copies the whole
        // 37 MB instead. Requires ndkVersion above to resolve, or it does nothing.
        ndk {
            abiFilters += listOf("arm64-v8a")
            debugSymbolLevel = "FULL"
        }
    }

    // `full` is what ships to Play; `foss` is the same app with every Google Play
    // services dependency removed, which is a hard requirement for F-Droid — its
    // inclusion policy names GMS as not accepted, and asks for a flavour without
    // it when the app can work in some capacity without it. Here it can: the only
    // GMS user is the ML Kit document scanner, and foss replaces it with its own
    // CameraX capture (batch import's picker was never GMS-backed in either).
    //
    // Same applicationId on purpose. It is one app on two stores, not two apps,
    // and F-Droid only requires the id be unique to the project.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // So a bare `./gradlew :app:assembleDebug`-shaped habit, and the IDE's
            // default run configuration, keep meaning the Play build.
            isDefault = true
            // Shown in Settings > About under the version. Declared here rather
            // than switched on BuildConfig.FLAVOR in the UI so the flavour block
            // stays the only place that spells out what the two builds differ on
            // — the same reason DocumentScan.kt is per-source-set. Names the
            // capture engine, because that is the whole difference and it is
            // what a scan report needs to disambiguate.
            buildConfigField("String", "DISTRIBUTION", "\"ML Kit (Play services)\"")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"FOSS (in-app camera)\"")
        }
    }

    signingConfigs {
        if (hasUploadKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasUploadKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 43 MB of unminified dex was most of what this app ships that isn't
            // the ONNX models or the native library, and it is dominated by
            // material-icons-extended and play-services — of which the app uses a
            // sliver. R8 is also what produces the mapping file Play asks for: in
            // an .aab it is embedded automatically at
            // BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map,
            // so there is nothing to upload by hand.
            isMinifyEnabled = true
            isShrinkResources = true
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
        buildConfig = true
    }
    packaging {
        jniLibs {
            // 16 KB page-size support (required by Play for apps targeting
            // Android 15+): store .so uncompressed and page-aligned, extracted
            // by the installer rather than at runtime. Pairs with
            // extractNativeLibs="false" in the manifest.
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(syncOcrModels, syncLegalDocs) }

/**
 * The last gate before Play: read the finished `.aab` and refuse to leave behind
 * one that would earn any of the three warnings the v0.4.0 upload earned.
 *
 * The counterpart to bbreceiptkit's verifyReleaseNativeProfile, which checks
 * *provenance* before the build starts. This checks the *artifact* afterwards,
 * so each catches a class of mistake the other cannot. Every message names the
 * console text it prevents, so a failure here says which warning you avoided.
 *
 * Reads only the zip central directory — no decompression, no ELF parsing. The
 * debug-symbols check is exact rather than heuristic: AGP's
 * ExtractNativeDebugMetadataTask skips any library whose stripped output is the
 * same length as its input, so a debugsymbols entry exists if and only if
 * stripping genuinely happened. One entry proves both.
 */
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
        // One task per release variant, registered inside onVariants rather than
        // a single shared task configured from it. With the full/foss flavours
        // this selector matches twice, and two variants configuring one task
        // would have it read whichever bundle was wired last while claiming to
        // have checked both.
        val verify = tasks.register("verify${variant.name.replaceFirstChar(Char::uppercase)}Bundle") {
            group = "verification"
            description = "Fail if the ${variant.name} .aab would earn a Play size / mapping / native-symbol warning."
            // Also establishes the dependency on the bundle task.
            inputs.file(bundle).withPropertyName("bundle")
            doLast {
                val aab = bundle.get().asFile
                val problems = mutableListOf<String>()

                // Ceilings sit ~50% above the measured good build (38.4 MB bundle,
                // 25.3 MB core lib). Raising either should be a deliberate,
                // commented act, not a reflex when a build goes red.
                val maxBundleBytes = 60L * 1024 * 1024
                val maxCoreLibBytes = 40L * 1024 * 1024

                if (aab.length() > maxBundleBytes) {
                    problems += "the bundle is ${aab.length()} B, over the ${maxBundleBytes} B ceiling. " +
                        "Play: \"This artifact significantly increases the size of APK(s) downloaded by users.\""
                }

                ZipFile(aab).use { zip ->
                    val entries = zip.entries().toList()

                    val core = entries.firstOrNull { it.name == CORE_LIB_ENTRY }
                    if (core == null) {
                        problems += "no $CORE_LIB_ENTRY in the bundle."
                    } else if (core.size > maxCoreLibBytes) {
                        problems += "$CORE_LIB_ENTRY is ${core.size} B uncompressed, over the " +
                            "${maxCoreLibBytes} B ceiling. A stripped release build is ~25 MB; " +
                            "~37 MB means AGP did not strip (check bb.ndkVersion resolves to an " +
                            "installed NDK), and ~224 MB means a PROFILE=debug library."
                    }

                    if (entries.none { it.name.startsWith(DEBUG_SYMBOLS_PREFIX) }) {
                        problems += "no native debug symbols under $DEBUG_SYMBOLS_PREFIX. AGP only " +
                            "extracts them when stripping actually ran, so this means bb.ndkVersion " +
                            "does not resolve to an installed NDK. " +
                            "Play: \"you've not uploaded debug symbols\"."
                    }

                    if (entries.none { it.name == MAPPING_ENTRY }) {
                        problems += "no $MAPPING_ENTRY — R8 did not run. " +
                            "Play: \"There is no deobfuscation file associated with this App Bundle.\""
                    }
                }

                if (problems.isNotEmpty()) {
                    throw GradleException(
                        problems.joinToString(
                            prefix = "${aab.name} is not fit to upload:\n  - ",
                            separator = "\n  - ",
                        ),
                    )
                }
            }
        }
        // AGP registers variant tasks after this script body runs, so
        // bundleFullRelease doesn't exist yet; configureEach applies to it once
        // it appears.
        tasks.matching { it.name == "bundle${variant.name.replaceFirstChar(Char::uppercase)}" }
            .configureEach { finalizedBy(verify) }
    }
}

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

    // FileProvider, for handing the generated Money Manager .xlsx to a share
    // target as a content:// URI rather than a file:// path.
    implementation("androidx.core:core-ktx:1.15.0")

    // On-device document scanner (guided capture + edge-detect/deskew), the
    // Android analog of iOS VisionKit. Delivered via Play services; the capture
    // UI runs in a Play-services activity, so no CAMERA permission is needed here.
    //
    // `full` only — this single line is the entire reason the foss flavour exists.
    // The foss source set supplies its own rememberDocumentScanLauncher backed by
    // its own CameraX capture, so nothing else in the app knows the difference.
    "fullImplementation"("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

    // In-app capture, `foss` only — this flavour's replacement for the ML Kit
    // scanner above. Scoped to the flavour for the same reason that line is:
    // the Play build must not grow a second camera stack it never uses.
    val cameraX = "1.4.1"
    "fossImplementation"("androidx.camera:camera-core:$cameraX")
    "fossImplementation"("androidx.camera:camera-camera2:$cameraX")
    "fossImplementation"("androidx.camera:camera-lifecycle:$cameraX")
    "fossImplementation"("androidx.camera:camera-view:$cameraX")

    // ONNX Runtime's *Java* bindings, foss only, for the vendored DocQuad code.
    //
    // This is a second ONNX Runtime in the app, and deliberately so. The parse
    // core links ORT statically inside libbb_mobile_ffi.so, which exports 226
    // dynamic symbols, none of them ORT's — so the two cannot bind to each
    // other, and sharing one runtime between Rust and Java would trade that
    // encapsulation for a saving we chose not to take.
    //
    // Both come out of one compile: scripts/build-ort-android.sh with
    // --build_shared_lib --build_java emits the ten libonnxruntime_*.a the Rust
    // link consumes *and* the .so/.jar here. F-Droid therefore still builds ORT
    // from source exactly once.
    "fossImplementation"(files("src/foss/libs/onnxruntime.jar"))

    // Lombok, for the vendored MakeACopy DocQuad sources only — two of them are
    // annotated @UtilityClass. Adding the processor rather than deleting the
    // annotation is deliberate: it is what lets app/src/foss/java/de/schliweb/**
    // stay byte-identical to upstream (see that tree's BuildConfig shim).
    "fossCompileOnly"("org.projectlombok:lombok:1.18.34")
    "fossAnnotationProcessor"("org.projectlombok:lombok:1.18.34")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Plain JVM unit tests (./gradlew :app:testDebugUnitTest) — no emulator, no
    // native library. Everything under src/test covers logic that is deliberately
    // Context-free for exactly this reason.
    testImplementation("junit:junit:4.13.2")
}

// Entry paths Gate B looks for. AGP writes the two BUNDLE-METADATA paths as
// literal constants in PackageBundleTask; bundletool strips that directory, so
// neither reaches a device — they exist only so Play can symbolicate and retrace.
val CORE_LIB_ENTRY = "base/lib/arm64-v8a/libbb_mobile_ffi.so"
val DEBUG_SYMBOLS_PREFIX = "BUNDLE-METADATA/com.android.tools.build.debugsymbols/"
val MAPPING_ENTRY = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
