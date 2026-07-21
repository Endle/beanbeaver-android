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
  Cargo.toml                     FFI bindgen + batch_e2e
  models/                        PP-OCRv5 .onnx (or ../models fallback)
  scripts/
    android-e2e.sh               adb batch harness
    compare-e2e.py
    fetch-models.sh
  shell.nix                      JDK 17 + Android SDK/NDK
  gradlew …
```

Generated (git-ignored): `bbreceiptkit/.../jniLibs/`, `uniffi/` Kotlin, `app/.../assets/models/`, `target/`.

## Build

```bash
cd android

# Optional: Nix for SDK/NDK/JDK
nix-shell

# Models (skip if ../models already has the three .onnx files)
./scripts/fetch-models.sh

# Native + UniFFI
./build-android.sh                 # PROFILE=debug for faster iteration

# APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
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
| Core | `bb-receipt-ffi` from [beanbeaver-core](https://github.com/Endle/beanbeaver-core) (git tag in `Cargo.toml`) |
| Bindings | UniFFI → Kotlin (JNA) |
| Models | Assets → `filesDir/models` (`ModelStore`) |
| Session | One process-wide `OcrSession` |

When bumping the core tag, update **this** `Cargo.toml` and the iOS root `Cargo.toml` together, then re-run `./build-android.sh` and iOS `./build-xcframework.sh`.

## iOS twin

If checked out beside the iOS app: parent [README.md](../README.md), `BeanBeaver/`, `build-xcframework.sh`.
