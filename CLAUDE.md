# BeanBeaver Android

Standalone Android client for the shared Rust receipt core: photo → PP-OCRv5 OCR +
parse + categorize → **Beancount**, entirely on-device via a UniFFI→Kotlin seam.
The **Android twin of `beanbeaver-ios`** — it consumes `bb-receipt-ffi` from
[`beanbeaver-core`](https://github.com/Endle/beanbeaver-core), pinned by a **git tag**
in `Cargo.toml` (same model as iOS). Compose UI + `ReceiptPipeline` ViewModel.

The umbrella `~/src/bb/CLAUDE.md` owns the cross-repo **license firewall** and
core-tag pinning rules (this repo is newer and not listed there yet — treat it as the
"desktop/consumer" side that pins `core`). Don't repeat license detail here.

**MVP scope:** system photo picker or bundled sample → scan → merchant / items /
Beancount. Not yet: document camera, GitHub sync, SAF ledger, batch-import UI.

## Layout

| Path | Role |
|---|---|
| `app/` | The Compose app (`com.beanbeaver.app`). Kotlin under `app/src/main/java/…` (a `java/` dir holding `.kt`). `MainActivity`, `ui/BeanBeaverApp.kt` (whole screen), `receipt/` (`ReceiptPipeline` VM, `ModelStore`, `BatchRunner`). |
| `bbreceiptkit/` | Local Gradle library wrapping the core. Hand-written `ReceiptScanner.kt`; the UniFFI-generated `uniffi/…` Kotlin and `jniLibs/` are **git-ignored**, produced by `build-android.sh`. |
| `src/` + `Cargo.toml` | Root Rust crate `beanbeaver-android-ffi-build`: build-only. Bins `uniffi-bindgen` (Kotlin codegen) and `batch_e2e` (host harness). Pins the `bb-receipt-ffi` tag. |
| `build-android.sh` | Builds core → `.so` + regenerates the Kotlin glue. Rerun after bumping the tag. |
| `models/` | PP-OCRv5 ONNX (det/rec + textline-orientation). Fetched, **not committed** — `./scripts/fetch-models.sh`. Gradle also falls back to `../models/` when co-located with iOS. |
| `scripts/` | `fetch-models.sh`, `android-e2e.sh` (adb batch harness), `compare-e2e.py`. |

**Generated / git-ignored** (rebuilt by `build-android.sh` / Gradle): `bbreceiptkit/src/main/kotlin/uniffi/`, `bbreceiptkit/src/main/jniLibs/`, `app/src/main/assets/models/`, `target/`, `local.properties`.

## Build & run on macOS (Apple Silicon)

We target **Apple Silicon**; the app is **arm64-v8a only** (`ort` has no
`x86_64-linux-android` prebuilt), so the emulator **must be an arm64 system image**.
Use the **official Android Studio** (SDK at `~/Library/Android/sdk`, bundled JBR =
JDK 21). No nix — it was removed on purpose; don't reintroduce it.

The shell has none of these exported by default. Set them per-invocation:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"   # build-android.sh's fallback path is wrong
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # for ./gradlew
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

./scripts/fetch-models.sh          # if models/ lacks the 3 .onnx files
./build-android.sh                 # PROFILE=debug for faster iteration
./gradlew :app:assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

One-time SDK setup (Android Studio SDK Manager / Device Manager): install the **NDK**,
an **arm64-v8a** system image, and create an AVD. `onnxruntime` is **statically linked**
into `libbb_receipt_ffi.so` — there's no separate `libonnxruntime.so`.

### Gotchas (already cost time — don't relearn)

- **`build-android.sh` host-PATH split (keep it).** The script prepends the NDK's
  `llvm/bin` to `PATH`. NDK r27+ ships only a `darwin-x86_64` host toolchain with no
  macOS compiler-rt, so the **host** uniffi-bindgen build must run with a clean `PATH`
  (`HOST_PATH`, Apple `/usr/bin/clang`) or it fails with `library 'clang_rt.osx' not found`.
  Android target builds keep the NDK on `PATH`. If a stale build cached the bad
  `ort-sys` host output, bust just it: `cargo clean -p ort-sys`.
- **16 KB page-size images** (e.g. API 35+ `ps16k`): a launch dialog warns that
  `libbb_receipt_ffi.so`, `libc++_shared.so`, JNA `libjnidispatch.so`, and an AndroidX
  lib aren't 16 KB-aligned. It **runs anyway** in compat mode. Real fix for Play Store /
  16 KB devices: JNA ≥5.17, `-Wl,-z,max-page-size=16384`, refresh AndroidX. Not a blocker.

## Conventions & open items

- **Core tag lag:** this repo pins `bb-receipt-ffi` behind iOS (was v0.3.3 vs iOS
  v0.5.0). When bumping, update **this** `Cargo.toml` and the iOS root together, rerun
  `./build-android.sh` here and `./build-xcframework.sh` in iOS. v0.5.0 is a breaking
  FFI change (adds `currency` + `tax_account` to `scan`) — adapt `ReceiptScanner.kt`.
- The `bb-receipt-ffi` git dep can't be run via `cargo run -p bb-receipt-ffi`; codegen
  is hosted by the local `uniffi-bindgen` bin (see `src/bin/uniffi-bindgen.rs`).
- Keep the app teachable and small; prefer straightforward Kotlin over cleverness.
