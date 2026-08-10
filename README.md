# BeanBeaver Android

Standalone Android client for the shared Rust receipt core: photo → PP-OCRv5
OCR + parse + categorize → Beancount, on-device via UniFFI.

**Requirements:** minSdk **34** (Android 14+), arm64-v8a, JDK 17, Rust 1.80+, NDK.

MVP: system photo picker or bundled sample → scan → merchant / items / Beancount.  
Not yet: document camera, GitHub sync, SAF ledger, batch-import UI.

## Layout

```
android/                         ← this directory is self-contained
  app/                           Compose app
  bbreceiptkit/                  UniFFI Kotlin + jniLibs
  build-android.sh               Rust → .so + Kotlin glue
  Cargo.toml                     FFI bindgen + batch_e2e ([[bin]] → shared/)
  models/                        PP-OCRv5 .onnx (or ../models fallback)
  scripts/
    android-e2e.sh               adb batch harness
    build-ort-android.sh         ONNX Runtime from source (FOSS/F-Droid build)
  shared/                        submodule: Endle/beanbeaver-mobile-util
    scripts/compare-e2e.py       ← shared with beanbeaver-ios
    scripts/fetch-models.sh
    src/bin/batch_e2e.rs
    src/bin/uniffi-bindgen.rs
  gradlew …
```

Clone with `--recurse-submodules` (or run `git submodule update --init`);
without `shared/` the cargo build fails on a missing bin path.

Generated (git-ignored): `bbreceiptkit/.../jniLibs/`, `uniffi/` Kotlin, `app/.../assets/models/`, `target/`.

## Build

Prereqs: install **Android Studio** (bundles JDK 17 + SDK), then use its SDK
Manager to add the **NDK** and an **arm64-v8a** system image. `build-android.sh`
auto-finds the NDK under `$ANDROID_HOME/ndk/`.

```bash
cd android

# Models (skip if ../models already has the three .onnx files)
./shared/scripts/fetch-models.sh

# Native + UniFFI
./build-android.sh                 # PROFILE=debug for faster iteration

# APK
./gradlew :app:assembleFullDebug
# → app/build/outputs/apk/full/debug/app-full-debug.apk
# (:app:assembleFossDebug for the Play-services-free F-Droid build)
```

Or open **this** `android/` folder in Android Studio.

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

