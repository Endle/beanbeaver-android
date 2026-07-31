# BeanBeaver Android

Standalone Android client for the shared Rust receipt core: photo → PP-OCRv5 OCR +
parse + categorize → **Beancount**, entirely on-device via a UniFFI→Kotlin seam.
The **Android twin of `beanbeaver-ios`** — it consumes `bb-receipt-ffi` from
[`beanbeaver-core`](https://github.com/Endle/beanbeaver-core), pinned by a **git tag**
in `Cargo.toml` (same model as iOS). Compose UI + `ReceiptPipeline` ViewModel.

The umbrella `~/src/bb/CLAUDE.md` owns the cross-repo **license firewall** and
core-tag pinning rules (this repo is newer and not listed there yet — treat it as the
"desktop/consumer" side that pins `core`). Don't repeat license detail here.

**Scope:** document camera (ML Kit) or system photo picker or bundled sample →
scan → merchant / items / Beancount → export (GitHub PR, or a Money Manager
`.xlsx` via the share sheet), single or batch. Every scan is also **recorded**,
so the app is a spend tracker over its own history: Spending (per-month category
breakdown, drilling into the items behind any total) and Receipts (everything
scanned, kept until deleted). Settings can browse and extend the classification
ruleset. Not yet: a Storage Access Framework ledger destination — iOS's
equivalent Files-inbox backend is written but commented out ("disabled for now"),
so this side deliberately has no twin until that comes back.

## Layout

| Path | Role |
|---|---|
| `app/` | The Compose app (`com.zhenbo.beanbeaver`). Kotlin under `app/src/main/java/…` (a `java/` dir holding `.kt`). `MainActivity`, `ui/BeanBeaverApp.kt` (whole screen), `receipt/` (`ReceiptPipeline` VM, `ModelStore`, `BatchRunner`). |
| `bbreceiptkit/` | Local Gradle library wrapping the core. Hand-written `ReceiptScanner.kt`; the UniFFI-generated `uniffi/…` Kotlin and `jniLibs/` are **git-ignored**, produced by `build-android.sh`. |
| `src/` + `Cargo.toml` | Root Rust crate `beanbeaver-android-ffi-build`: build-only. Bins `uniffi-bindgen` (Kotlin codegen) and `batch_e2e` (host harness). Pins the `bb-receipt-ffi` tag. |
| `build-android.sh` | Builds core → `.so` + regenerates the Kotlin glue. Rerun after bumping the tag. |
| `models/` | PP-OCRv5 ONNX (det/rec + textline-orientation). Fetched, **not committed** — `./scripts/fetch-models.sh`. Gradle also falls back to `../models/` when co-located with iOS. |
| `scripts/` | `fetch-models.sh`, `android-e2e.sh` (adb batch harness), `compare-e2e.py`, `e2e-fixtures.sh` (stitch image + ground truth into one dir), `launch-timing.sh` (cold-launch latency on a real device). |
| `app/src/test/` | Plain JVM unit tests (`./gradlew :app:testDebugUnitTest`) — no emulator, no native lib. Covers the deliberately Context-free logic: the `.xlsx` writer, amount/price normalization, display formatting, and `SpendSummary`'s arithmetic. |
| `tests/receipts_e2e/` | E2E **ground truth only** (`<stem>.expected.json`, same schema/grader as iOS). The images aren't duplicated here — the one public fixture is the app's bundled sample under `app/src/main/assets/samples/`. |
| `.github/workflows/` | `android-build.yml` — the CI below. |

**Generated / git-ignored** (rebuilt by `build-android.sh` / Gradle): `bbreceiptkit/src/main/kotlin/uniffi/`, `bbreceiptkit/src/main/jniLibs/`, `app/src/main/assets/models/`, `app/src/main/assets/legal/`, `target/`, `local.properties`.

`assets/legal/` is copied from the repo-root `PRIVACY.md` and
`THIRD_PARTY_NOTICES.md` by the `syncLegalDocs` Gradle task, so the file a reader
sees in the repo and the one the app shows can't drift — edit the root copies.
Regenerate the notices' crate inventory whenever the core tag moves.

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

### Release build for Play (AAB)

Play needs a **signed `.aab`**, not an APK:

```bash
cp keystore.properties.example keystore.properties   # then fill in real values
./build-android.sh                                    # PROFILE=release (default)
./gradlew :app:bundleRelease                          # → app/build/outputs/bundle/release/app-release.aab
```

- Signing reads `keystore.properties` (git-ignored; see `keystore.properties.example`).
  Without it the release variant builds **unsigned**. Enroll in **Play App Signing** so
  the upload key is resettable.
- Every upload needs a unique, higher `versionCode` (`app/build.gradle.kts`).
- 16 KB page-size support is **mandatory** for Android 15+ targets: `useLegacyPackaging =
  false`, `extractNativeLibs="false"`, JNA ≥5.17, and the `max-page-size=16384` link arg
  in `build-android.sh` — all already wired. Verify with
  `zipalign -c -P 16 -v 4 app-release.aab` or Android Studio's APK Analyzer.
- Console-side (not in this repo): privacy-policy URL (host `PRIVACY.md`), Data safety
  form, content rating, target-audience + financial-app declarations, and store-listing
  assets (512² icon, 1024×500 feature graphic, ≥2 screenshots).

### CI (`.github/workflows/android-build.yml`)

One `ubuntu-latest` job, the Android twin of iOS's `ios-build.yml`: NDK
cross-build of `bb-receipt-ffi` + UniFFI codegen (`PROFILE=debug
./build-android.sh`) → `:app:assembleDebug` (APK uploaded as an artifact) →
`:app:testDebugUnitTest` → `:app:lintDebug` → **host E2E**: `batch_e2e` scans
the bundled fixture and `compare-e2e.py` grades merchant/date/total/items
against `tests/receipts_e2e/`. Cargo, the `ort` prebuilt, the ONNX weights and
Gradle are all cached; a warm run is a few minutes.

**There is no emulator job, and adding one is not a matter of writing YAML.**
The app is arm64-v8a only, and no GitHub-hosted runner can run an arm64 AVD:
the x86_64 Linux runners are the only ones with KVM but can't install an
arm64-only APK; the Apple-Silicon macOS runners have no HVF, because Apple's
Virtualization Framework doesn't nest (an arm64 AVD dies `HV_UNSUPPORTED` /
SIGSEGV); Linux arm64 runners have no KVM either. So the **JNI seam is the one
thing CI cannot prove** — the host E2E covers the same core, models, fixture and
grader, but not `.so` loading, the UniFFI checksum or JNA. Prove that on real
arm64 hardware: locally with

```bash
./scripts/android-e2e.sh "$(./scripts/e2e-fixtures.sh)" --pilot
```

and, if it ever needs to be automatic, via a self-hosted arm64 Mac runner or a
device farm (Firebase Test Lab — which would want an instrumented `androidTest`
wrapper around `BatchRunner`; there is no `androidTest` source set today).

### Gotchas (already cost time — don't relearn)

- **`build-android.sh` host-PATH split (keep it).** The script prepends the NDK's
  `llvm/bin` to `PATH`. NDK r27+ ships only a `darwin-x86_64` host toolchain with no
  macOS compiler-rt, so the **host** uniffi-bindgen build must run with a clean `PATH`
  (`HOST_PATH`, Apple `/usr/bin/clang`) or it fails with `library 'clang_rt.osx' not found`.
  Android target builds keep the NDK on `PATH`. If a stale build cached the bad
  `ort-sys` host output, bust just it: `cargo clean -p ort-sys`.
- **Never pin the daemon JVM to a *vendor*.** `gradle/gradle-daemon-jvm.properties`
  keeps `toolchainVersion=21` and nothing else. `./gradlew updateDaemonJvm` writes
  `toolchainVendor=jetbrains` too, which makes the build impossible anywhere Android
  Studio isn't installed: JBR ships only inside JetBrains IDEs, so Gradle tries to
  download one and dies with `No defined toolchain download url for LINUX on x86_64
  architecture`. Locally any JDK 21 resolves to the Studio JBR anyway.
- **16 KB page-size alignment (now done — required, not optional).** Since Nov 2025 Play
  rejects new apps/updates targeting Android 15+ whose `.so`s aren't 16 KB-aligned. Fixed
  here via JNA ≥5.17, the `max-page-size=16384` link arg in `build-android.sh`,
  `useLegacyPackaging = false`, and `extractNativeLibs="false"`. If a `ps16k` image still
  warns at launch after a clean rebuild, re-check those four. Older AndroidX bumps may
  also be needed as the Compose BOM advances.

## Conventions & open items

- **Core tag:** in step with iOS at **v0.7.1**. When bumping, update **this**
  `Cargo.toml` and the iOS root together, rerun `./build-android.sh` here and
  `./build-xcframework.sh` in iOS. Check `crates/ffi/src/lib.rs` in the tag range
  first: a parser/rules-only bump needs no Kotlin change, but an FFI signature
  change means adapting `ReceiptScanner.kt` (as v0.5.0's `currency` +
  `tax_account` did) **and `src/bin/batch_e2e.rs`** — nothing built that bin, so
  it silently rotted against the v0.6.x `scan()` arity and `ScanTimings.spans`
  until CI started compiling it.
- **A field rename reaches further than its call sites.** v0.7.0's
  `ReceiptItem.category -> account` and `tags: [String] -> [ItemTag]` also moved
  three JSON writers (`LedgerEntry` sidecar, `DebugInfoStore`, `BatchRunner`),
  the batch's own persistence, `batch_e2e`'s output shape and the
  `compare-e2e.py` that grades it. `ReceiptResultJson.decode` reads **both**
  tag shapes on purpose: a batch saved by the previous build is still on disk
  when the app updates. Pre-0.7.0 `category` is deliberately *not* read into
  `account` — it held a classifier key (`grocery_dairy`), not an account.
- The `bb-receipt-ffi` git dep can't be run via `cargo run -p bb-receipt-ffi`; codegen
  is hosted by the local `uniffi-bindgen` bin (see `src/bin/uniffi-bindgen.rs`).
- Keep the app teachable and small; prefer straightforward Kotlin over cleverness.
- **`SpendSummary.kt` must stay free of Android imports** — no `Context`, no
  preferences, no Compose. That is what lets its arithmetic be pinned by JVM
  tests rather than eyeballed on a screen, and it keeps the one genuinely
  shareable part of spend tracking liftable if ios/android ever share logic.
  `BudgetPrefs` and `AmountPrivacy` hold the platform-bound halves; a target is
  an overlay drawn on top of the arithmetic and must never be an input to it.
- **Process-wide stores, not ViewModels**, for `SpendStore`, `ItemRuleStore` and
  `AmountPrivacy`: both scan paths write to them and several screens read them,
  and a stale copy in one owner is exactly the bug to avoid. Each is `ensureLoaded`
  + `StateFlow`, loaded off the main thread (`ItemRuleStore` compiles the whole
  TOML corpus on first read).
- Sub-screens are **boolean-gated early returns**, not a Nav back stack, so any
  new screen needs its own `BackHandler` — without one, system back leaves the
  app entirely instead of popping one rung. Every screen carries one now; a
  shared scaffold (`DocumentScaffold`, `DetailScaffold`) carries a single one on
  behalf of all its callers. Adding a screen without one is the easiest way to
  regress this, and it will not show up in a build or a unit test.
