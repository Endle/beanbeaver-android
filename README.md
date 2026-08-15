# BeanBeaver Android

Standalone Android client for the shared Rust receipt core: photo → PP-OCRv5
OCR + parse + categorize → Beancount, on-device via UniFFI.

**Requirements:** minSdk **34** (Android 14+), arm64-v8a, JDK 21, Rust 1.80+, NDK.

MVP: system photo picker or bundled sample → scan → merchant / items / Beancount.  

## Build

Prereqs: install **Android Studio** (bundles JDK 21 + SDK), then use its SDK
Manager to add the **NDK** and, if you want an emulator, an **arm64-v8a** system
image (the app is arm64-only, so an x86_64 AVD cannot install it).

Building is **two steps**: `build-android.sh` compiles the Rust core into a `.so`
and generates the UniFFI Kotlin, then Gradle builds an APK out of them. The first
produces *inputs* to the second — nothing in the app compiles until it has run at
least once, because `bbreceiptkit/src/main/kotlin/uniffi/` is git-ignored.

```bash
cd beanbeaver-android

export ANDROID_HOME="$HOME/Library/Android/sdk"          # macOS; Linux: ~/Android/Sdk
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./shared/scripts/fetch-models.sh   # skip if models/ already has the three .onnx
./build-android.sh                 # Rust → .so + Kotlin glue  (step 1)
./gradlew :app:assemblePlayDebug   # APK                       (step 2)
# → app/build/outputs/apk/play/debug/app-play-debug.apk
```

**`ANDROID_HOME` must be exported**, even though the script never reads the SDK
itself: with `ANDROID_NDK_HOME` unset it looks for the pinned NDK under
`$ANDROID_HOME/ndk/`, and its last-resort fallback is `~/Android/Sdk`, which does
not exist on a macOS install. Without it you get `error: Android NDK not found`.
`JAVA_HOME` is only for `gradlew`; the native step needs no JDK.

Android Studio users: you need to run `build-android.sh` in a terminal first.

### `build-android.sh`

Installs into `bbreceiptkit/` (all git-ignored, all regenerated on each run):
`src/main/jniLibs/<abi>/libbb_mobile_ffi.so` + `libc++_shared.so`, the
`src/main/kotlin/uniffi/` glue for **both** UniFFI namespaces, and a generated
`BuildInfo.kt` recording the profile the `.so` was built at.

**Re-run it after** bumping the `bb-mobile-ffi` / `bb-receipt-ffi` tag in
`Cargo.toml`, moving the `shared/` submodule, or any `PROFILE=debug` build you
want to undo. Editing Kotlin only needs step 2.

ONNX Runtime is **built from source** as part of this, for every flavour and
channel — `scripts/build-ort-android.sh` runs automatically. The first build is
~4-6 min (clone + compile); afterwards `target/ort/` makes it a no-op.

| Variable | Default | Effect |
|---|---|---|
| `PROFILE` | `release` | `debug` for faster iteration — see the warning below |
| `ABIS` | `arm64-v8a` | the only ABI the app ships |
| `ANDROID_API` | `34` | matches minSdk |
| `ANDROID_NDK_HOME` | auto-discovered | override; must equal `bb.ndkVersion` in `gradle.properties` or the script refuses to run |
| `ORT_ANDROID_LIB_LOCATION` | — | link an already-built ORT tree instead of compiling (this is what CI's cache restores) |
| `BB_ORT` | `source` | `prebuilt` falls back to pyke's CDN. Emergencies only — it makes your build link a different OCR engine than ships |

> **A `PROFILE=debug` `.so` will ship if you let it.** The script copies whatever
> cargo last built into `jniLibs/`, so a debug run left over from emulator testing
> becomes the native library in your next release bundle (224 MB instead of 37 MB —
> this reached Play once as v0.4.0). Re-run `./build-android.sh` with no `PROFILE`
> before bundling; `:bbreceiptkit:verifyReleaseNativeProfile` will otherwise fail
> the build by reading the profile out of `BuildInfo.kt`.

**Why Gradle is a separate step, not called from the script.** The two have very
different cadences — the native build changes only when the core tag does, while
`gradlew` runs on every Kotlin edit — and the script produces one flavour-agnostic
`.so` that all six variants share, so it has no business choosing between
`assemblePlayDebug`, `bundlePlayRelease`, `testPlayDebugUnitTest` and the rest.
Keeping them apart is also what lets Android Studio build the app at all (it
invokes Gradle directly and knows nothing about the script), and what lets the
script run on a machine with no JDK. The seam is checked rather than trusted:
`BuildInfo.kt` is how Gradle verifies the `.so` beside it was built the way the
release gates require.

Before opening a PR, build all three flavours — a symbol that exists in only one
source set compiles for you and not for the others:

```bash
./build-android.sh
./gradlew :app:assemblePlayRelease :app:assembleSafehavenRelease \
          :app:assembleFdroidRelease
./gradlew :app:testPlayDebugUnitTest :app:lintPlayDebug
cargo check --bin batch_e2e        # CI builds this bin; it has rotted before
```

## E2E

```bash
# Host (same Rust core, no device):
cargo run --release --bin batch_e2e -- \
  --models models --in-dir /path/to/batch_in --out /tmp/batch_out.json

# Device / arm64 emulator:
./scripts/android-e2e.sh /path/to/receipts_e2e --all
# BUILD=1 ./scripts/android-e2e.sh …   # rebuild/install first
```

## Architecture

| Concern | Approach |
|---------|----------|
| Core | `bb-mobile-ffi` from [beanbeaver-mobile-util](https://github.com/Endle/beanbeaver-mobile-util) (git tag in `Cargo.toml`), which carries `bb-receipt-ffi` from [beanbeaver-core](https://github.com/Endle/beanbeaver-core) inside it — one `libbb_mobile_ffi.so`, two UniFFI namespaces |
| Spend arithmetic | `spend-core` in the same repo — shared with beanbeaver-ios, not reimplemented here |
| Bindings | UniFFI → Kotlin (JNA) |
| Models | Assets → `filesDir/models` (`ModelStore`) |
| Session | One process-wide `OcrSession` |

When bumping the core tag, update **this** `Cargo.toml` and the iOS root `Cargo.toml` together, then re-run `./build-android.sh` and iOS `./build-xcframework.sh`.

