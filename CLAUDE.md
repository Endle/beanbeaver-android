# BeanBeaver Android

Standalone Android client for the shared Rust receipt core: photo → PP-OCRv5 OCR +
parse + categorize → **Beancount**, entirely on-device via a UniFFI→Kotlin seam.
The **Android twin of `beanbeaver-ios`** — it links **one** Rust library,
`bb-mobile-ffi` from [`beanbeaver-mobile-util`](https://github.com/Endle/beanbeaver-mobile-util),
which carries the parse core (`bb-receipt-ffi` from
[`beanbeaver-core`](https://github.com/Endle/beanbeaver-core)) inside it. Both are
pinned by **git tag** in `Cargo.toml` (same model as iOS). Compose UI + `ReceiptPipeline` ViewModel.

The umbrella `~/src/bb/CLAUDE.md` owns the cross-repo **license firewall** and
core-tag pinning rules (this repo is newer and not listed there yet — treat it as the
"desktop/consumer" side that pins `core`). Don't repeat license detail here.

**Scope:** document camera (ML Kit) or system photo picker or bundled sample →
scan → merchant / items / Beancount → export (GitHub PR, or a Money Manager
`.xlsx` via the share sheet), single or batch. Every scan is also **recorded**,
so the app is a spend tracker over its own history: Spending (per-month category
breakdown, drilling into the items behind any total) and Receipts (everything
scanned, kept until deleted, each row carrying an export-status dot and
filterable by it). Settings can browse and extend the classification ruleset.
Not yet: a Storage Access Framework ledger destination — iOS's equivalent
Files-inbox backend is written but commented out ("disabled for now"), so this
side deliberately has no twin until that comes back.

## Layout

| Path | Role |
|---|---|
| `app/` | The Compose app (`com.zhenbo.beanbeaver`). Kotlin under `app/src/main/java/…` (a `java/` dir holding `.kt`). `MainActivity`, `ui/BeanBeaverApp.kt` (whole screen), `receipt/` (`ReceiptPipeline` VM, `ModelStore`, `BatchRunner`). |
| `app/src/{full,foss}/` | The two product flavours — see "Flavours" below. Each holds exactly one file: its own `ui/DocumentScan.kt`. |
| `bbreceiptkit/` | Local Gradle library wrapping the core. Hand-written `ReceiptScanner.kt`; the UniFFI-generated `uniffi/…` Kotlin and `jniLibs/` are **git-ignored**, produced by `build-android.sh`. |
| `src/` + `Cargo.toml` | Root Rust crate `beanbeaver-android-ffi-build`: build-only. Pins the **`bb-mobile-ffi`** tag (the library that ships) *and* the `bb-receipt-ffi` tag (for `batch_e2e.rs`) — **the two must agree on the core version**. Hosts two bins — `uniffi-bindgen` (Kotlin codegen) and `batch_e2e` (host harness) — whose **sources live in `shared/`**, compiled here via `[[bin]] path`. `src/lib.rs` is an empty lib target. |
| `build-android.sh` | Builds core → `.so` + regenerates the Kotlin glue. Rerun after bumping the tag. |
| `models/` | PP-OCRv5 ONNX (det/rec + textline-orientation). Fetched, **not committed** — `./shared/scripts/fetch-models.sh`. Gradle also falls back to `../models/` when co-located with iOS. |
| `scripts/` | `android-e2e.sh` (adb batch harness), `e2e-fixtures.sh` (stitch image + ground truth into one dir), `launch-timing.sh` (cold-launch latency on a real device), `build-ort-android.sh` (ONNX Runtime from source — the F-Droid path, see below). |
| `shared/` | **Git submodule** — [`beanbeaver-mobile-util`](https://github.com/Endle/beanbeaver-mobile-util), the assets iOS and Android genuinely share: `scripts/compare-e2e.py`, `scripts/fetch-models.sh`, `src/bin/uniffi-bindgen.rs`, `src/bin/batch_e2e.rs`. See "The `shared/` submodule" below. |
| `app/src/test/` | Plain JVM unit tests (`./gradlew :app:testFullDebugUnitTest`) — no emulator, no native lib. Covers the deliberately Context-free logic: the `.xlsx` writer, amount/price normalization, display formatting, and `SpendSummary`'s **projection / re-attachment** (the arithmetic itself moved to Rust — see below). |
| `tests/receipts_e2e/` | E2E **ground truth only** (`<stem>.expected.json`, same schema/grader as iOS). The images aren't duplicated here — the one public fixture is the app's bundled sample under `app/src/main/assets/samples/`. |
| `.github/workflows/` | `android-build.yml` (the CI below) and `fdroid.yml` (the F-Droid build, separate because it is slow and narrowly triggered). |

**Generated / git-ignored** (rebuilt by `build-android.sh` / Gradle): `bbreceiptkit/src/main/kotlin/uniffi/`, `bbreceiptkit/src/main/jniLibs/`, `app/src/main/assets/models/`, `app/src/main/assets/legal/`, `target/`, `local.properties`.

`assets/legal/` is copied from the repo-root `PRIVACY.md` and
`THIRD_PARTY_NOTICES.md` by the `syncLegalDocs` Gradle task, so the file a reader
sees in the repo and the one the app shows can't drift — edit the root copies.
Regenerate the notices' crate inventory whenever the core tag moves.

## The `shared/` submodule

Four files used to exist twice (or once, and should have existed twice). They now
live in [`beanbeaver-mobile-util`](https://github.com/Endle/beanbeaver-mobile-util)
and both phone apps consume them as a submodule at `shared/`.

**Clone with `--recurse-submodules`**, or run `git submodule update --init`. An
empty `shared/` is not a soft failure: the `[[bin]]` paths in `Cargo.toml` point
into it, so cargo dies on a missing manifest path and `build-android.sh` never
reaches codegen. Both CI workflows check out with `submodules: true`.

The two `.rs` files are **source assets compiled into this package**, not a crate
dependency — deliberately. `batch_e2e.rs` imports `OcrSession`, `Phase`,
`ScanTimings` and `ReceiptWarningKind` from `bb-receipt-ffi`, so it builds against
*this* repo's pinned core tag, and iOS can sit on a different one. That is exactly
the drift a cargo git-dep could not absorb. Same reasoning for `uniffi-bindgen.rs`
and the `uniffi` 0.28 pin.

So a breaking core FFI bump can require a change in `beanbeaver-mobile-util`. The
order is: fix it there, push, then move this repo's pointer:

```bash
cd shared && git pull origin main && cd ..
git add shared && git commit -m "chore(shared): bump beanbeaver-mobile-util"
```

## Build & run on macOS (Apple Silicon)

We target **Apple Silicon**; the app is **arm64-v8a only** (`ort` has no
`x86_64-linux-android` prebuilt), so the emulator **must be an arm64 system image**.
Use the **official Android Studio** (SDK at `~/Library/Android/sdk`, bundled JBR =
JDK 21). No nix — it was removed on purpose; don't reintroduce it.

The shell has none of these exported by default. Set them per-invocation:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
# Optional — build-android.sh auto-discovers the pinned NDK. Set it only to
# override, and it must match bb.ndkVersion in gradle.properties or the script
# refuses to run (AGP strips with that same NDK; see Gotchas).
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.15729638"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # for ./gradlew
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

./shared/scripts/fetch-models.sh   # if models/ lacks the 3 .onnx files
./build-android.sh                 # PROFILE=debug for faster iteration
./gradlew :app:assembleFullDebug   # → app/build/outputs/apk/full/debug/app-full-debug.apk
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

One-time SDK setup (Android Studio SDK Manager / Device Manager): install the **NDK**,
an **arm64-v8a** system image, and create an AVD. `onnxruntime` is **statically linked**
into `libbb_mobile_ffi.so` — there's no separate `libonnxruntime.so`.

### Release build for Play (AAB)

Play needs a **signed `.aab`**, not an APK:

```bash
cp keystore.properties.example keystore.properties   # then fill in real values
./build-android.sh                                    # PROFILE=release (default)
./gradlew :app:bundleFullRelease                      # → app/build/outputs/bundle/fullRelease/app-full-release.aab
```

- Signing reads `keystore.properties` (git-ignored; see `keystore.properties.example`).
  Without it the release variant builds **unsigned**. Enroll in **Play App Signing** so
  the upload key is resettable.
- Every upload needs a unique, higher `versionCode` (`app/build.gradle.kts`).
- **Two gates run automatically; neither has an escape hatch, by design.**
  `:bbreceiptkit:verifyReleaseNativeProfile` refuses to start a release build whose
  `jniLibs/` came from `PROFILE=debug`, and `:app:verifyFullReleaseBundle` finalizes
  `bundleFullRelease` and refuses to leave behind an `.aab` that would earn a Play warning
  (size, missing native symbols, missing mapping). Each failure message names the
  console text it prevents. A healthy bundle is **~38 MB**.
- 16 KB page-size support is **mandatory** for Android 15+ targets: `useLegacyPackaging =
  false`, `extractNativeLibs="false"`, JNA ≥5.17, and the `max-page-size=16384` link arg
  in `build-android.sh` — all already wired. Verify with
  `zipalign -c -P 16 -v 4 app-full-release.aab` or Android Studio's APK Analyzer.
- Console-side (not in this repo): privacy-policy URL (host `PRIVACY.md`), Data safety
  form, content rating, target-audience + financial-app declarations, and store-listing
  assets (512² icon, 1024×500 feature graphic, ≥2 screenshots).

### CI (`.github/workflows/android-build.yml`)

Two `ubuntu-latest` jobs here, plus a third in its own file (below).

**`build`** — the Android twin of iOS's `ios-build.yml`: NDK cross-build of
`bb-receipt-ffi` + UniFFI codegen (`PROFILE=debug ./build-android.sh`) →
`:app:assembleFullDebug` (APK uploaded as an artifact) → `:app:testFullDebugUnitTest` →
`:app:lintFullDebug` → **host E2E**: `batch_e2e` scans the bundled fixture and
`shared/scripts/compare-e2e.py` grades merchant/date/total/items against
`tests/receipts_e2e/`.
Cargo, the `ort` prebuilt, the ONNX weights and Gradle are all cached; a warm run
is a few minutes.

**`release-bundle`** — the path that actually ships, which nothing exercised
until v0.4.0 reached Play carrying a debug native library. Release-profile
`./build-android.sh` (deliberately: the gates refuse a debug library, and this
job exists to test what gets uploaded) → `:app:bundleFullRelease`, which runs R8, the
strip/symbol extraction, and both gates as a finalizer. Unsigned — signing has no
bearing on what the gates read. **This is the only CI coverage R8 has**, since
`:app:assembleFullDebug` never runs it.

### `fdroid.yml` — the F-Droid build

`fdroid-ort-from-source` compiles ONNX Runtime instead of downloading it and
builds the **foss** flavour, then asserts both properties rather than trusting the
wiring: no `libonnxruntime` `DT_NEEDED` and no `aarch64-linux-android` entry in
ort's download cache, and **zero** `com.google.android.gms` strings in the foss
dex. Every other job happily downloads a prebuilt and links GMS, so this wiring
can rot without any of them noticing.

Its own file so it can have its own triggers: master pushes, `workflow_dispatch`,
and **only** PRs touching build inputs (`paths:` in the workflow). The ORT compile
is ~15 min, which is not a tax worth putting on a docs PR.

**There is deliberately no cache, and that is the point.** The only thing worth
caching is the ORT build — which is exactly what this job exists to prove still
works. A warm run would show that linking succeeds while saying nothing about
whether ONNX Runtime still *compiles*, and the compile is where breakage lives (a
bumped `ort`, an upstream CMake change, `re2` moving in or out of
`EXCLUDE_FROM_ALL`). Nothing that ships depends on it being fast: release builds
fetch `bb-receipt-ffi` at its pinned tag and build from scratch, and F-Droid's
buildserver has no cache of ours either. Also no `Swatinem/rust-cache` — the ORT
archives are a build input outside `target/`, and caching the two independently is
what wedged beanbeaver-ios's main branch (ios #57).

If you add a build input, add it to that `paths:` list. A flavour file that slips
past the filter loses its only ORT-side coverage on that PR.

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

### Flavours: `full` (Play) and `foss` (F-Droid)

One dimension, `distribution`, so every variant task carries the flavour:
`assembleFullDebug`, `bundleFullRelease`, `testFullDebugUnitTest`,
`lintFullDebug`, `verifyFullReleaseBundle`. **`full` is `isDefault`**, so it is
what the IDE and any half-remembered command land on, and it is what ships to
Play. Same `applicationId` in both — one app on two stores.

The entire difference is one dependency and one file:

| | `full` | `foss` |
|---|---|---|
| `play-services-mlkit-document-scanner` | yes (`fullImplementation`) | **no** |
| `ui/DocumentScan.kt` | GMS guided capture (edge-detect, deskew, retake) | system photo picker |
| `BuildConfig.DISTRIBUTION` | `"ML Kit (Play services)"` | `"FOSS (photo picker)"` |
| everything else | identical | identical |

`DISTRIBUTION` is a `buildConfigField` set in each flavour block and shown in
Settings > About, so a user reporting a scan problem can say which build they
have. It is declared beside the flavour rather than switched on
`BuildConfig.FLAVOR` at the call site, to keep the flavour block the only place
that spells out the difference.

`rememberDocumentScanLauncher` has the same signature in both source sets, so
`ui/BeanBeaverApp.kt` calls it without knowing the flavour and neither file needs
an `#ifdef`-shaped conditional. What `foss` loses is *guided capture*, not
scanning: the user shoots with their own camera app and picks the photo, and the
bytes reach `ReceiptPipeline.scan` by the same path — which is already the common
case in `full`, since batch import has always used the picker. Deskew is left to
receipt-core's own (shipped v0.7.2).

Both flavours are built in CI, and `fdroid-ort-from-source` fails if any
`com.google.android.gms` string survives into the foss dex. **Build both before
opening a PR** — a flavour that only exists in one source set is exactly the kind
of thing that compiles for you and not for the other variant.

### ONNX Runtime from source (the F-Droid path)

`ort`'s default `download-binaries` fetches a precompiled static ONNX Runtime from
pyke's CDN. **F-Droid never accepts a prebuilt shared library**, so submission needs
a build that compiles it. That build exists and is gated by the
`fdroid-ort-from-source` CI job:

```bash
ORT_ANDROID_LIB_LOCATION="$(./scripts/build-ort-android.sh --print-lib-location)" \
  ./build-android.sh
```

Verified 2026-08-09: it links, and the resulting APK passes the pilot E2E on an
arm64 emulator with output identical to a prebuilt-ORT build (`COSTCO 2026-03-01
$72.41`, 7 items). ~3.5 min to compile ORT on a 10-core M-series; 1.4 GB under
`target/ort/` (git-ignored).

Four things here are non-obvious, and three of them fail *late*:

- **The version is derived, never hardcoded.** `ort-sys/build/download/dist.txt`
  records the upstream build its binaries came from (`ms@1.24.2`). A different
  version links cleanly and then misbehaves at runtime.
- **Point ort-sys at `<build_dir>/Release`, not `<build_dir>`.** `build.py` makes
  `Release` the CMake binary dir, so `_deps` is *inside* it. Aimed one level up,
  ort-sys detects `profile="Release"`, finds the ten `libonnxruntime_*.a`, appends
  `/Release` to every dependency path, and still reports success — the link then
  fails on unresolved protobuf/onnx symbols.
- **`re2` must be built explicitly.** ort-sys links it unconditionally, but it is
  `EXCLUDE_FROM_ALL`, and with `--build_shared_lib` off and unit tests off nothing
  ORT builds ever links a binary — so CMake has no reason to build it. `build.py`
  exits 0 and Rust dies much later with `could not find native static library 're2'`.
- **The variable is `ORT_ANDROID_LIB_LOCATION`, not ort-sys's own
  `ORT_LIB_LOCATION`.** The latter applies to *every* cargo invocation, including
  `build-android.sh`'s **host** uniffi-bindgen build, which would then try to link
  Android `.a` files into a host dylib. It is forwarded to the target build only.

Still open for F-Droid, and not solved by any of the above: the OCR weights are
downloaded at build time and their fetch is unpinned (they *are* freely licensed
— `THIRD_PARTY_NOTICES.md` records PP-OCRv5 as Apache-2.0 — and SHA-256s already
exist in `beanbeaver/runtime/ocr_models.py`); the host bindgen build still
fetches a *host* prebuilt ORT (not shipped in the APK, but F-Droid would run that
step too, and fixing it means splitting the bindgen bin out of the package that
depends on `bb-receipt-ffi`); and there is still no `LICENSE` file, no git tags,
and no fastlane metadata.

### Gotchas (already cost time — don't relearn)

- **`ndkVersion` must be pinned, or AGP silently stops stripping *and* stops producing
  Play symbols.** With no `ndkVersion`, AGP 8.13.2 looks for its own built-in default
  (`27.0.12077973`), doesn't find it, and degrades `stripReleaseDebugSymbols` into a
  **file copy** — with only a warning. And because `ExtractNativeDebugMetadataTask`
  skips any library whose stripped output is the same length as its input, that also
  disables the Play symbols upload, so `debugSymbolLevel = "FULL"` produces *nothing*.
  Two symptoms, one cause. `bb.ndkVersion` in `gradle.properties` is the single source
  of truth: both modules read it, `build-android.sh` prefers it and hard-fails on a
  mismatch, and CI installs it. **Never let the NDK that compiles the `.so` differ from
  the one AGP strips it with.** Symptom if it regresses: the packaged
  `libbb_mobile_ffi.so` is ~37 MB instead of ~25 MB, `libc++_shared.so` is 9.5 MB
  instead of 1.4 MB, and `BUNDLE-METADATA/…/debugsymbols/` is absent.
- **Stripping rewrites the ELF, so re-check 16 KB alignment after any change to it.**
  `llvm-objcopy --strip-unneeded` rebuilds section headers and file layout. It *does*
  preserve `p_align` (verified: all four packaged `.so`s report `Align 0x4000`, and
  `zipalign -c -P 16 -v 4` passes), but alignment is a hard Play requirement, so verify
  both layers rather than assuming:
  ```bash
  unzip -p app-release.apk lib/arm64-v8a/libbb_mobile_ffi.so > /tmp/s.so
  "$ANDROID_HOME/ndk/<pin>/toolchains/llvm/prebuilt/*/bin/llvm-readelf" -l /tmp/s.so | grep LOAD
  "$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 app-release.apk
  ```
- **A debug `.so` will ship if you let it.** `build-android.sh` copies whatever cargo
  last built into `jniLibs/`, so a `PROFILE=debug` run left behind by emulator testing
  becomes the library in your next release bundle — 224 MB instead of 37 MB. This
  actually reached Play as v0.4.0. `verifyReleaseNativeProfile` now refuses it by
  reading the `PROFILE` that `build-android.sh` records in the generated
  `BuildInfo.kt`, which is a matched pair with the `.so` beside it. **After any
  `PROFILE=debug` build, re-run `./build-android.sh` before bundling** — or just let
  the gate tell you.
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

## Two pinned tags, and why the pair matters

`Cargo.toml` pins **two** git dependencies, and they are not independent:

| Dep | Why | Pinned at |
|---|---|---|
| `bb-mobile-ffi` (beanbeaver-mobile-util) | **the library that ships.** Carries both UniFFI namespaces; `build-android.sh` builds *this* into `libbb_mobile_ffi.so` | v0.1.1 |
| `bb-receipt-ffi` (beanbeaver-core) | only for `shared/src/bin/batch_e2e.rs`, which uses the core's **Rust** API | v0.9.0 |

The `shared/` submodule pointer is a **third** thing to move and is not covered by
either pin: `shared/src/bin/` is compiled into this package, so a mobile-util tag
bump usually wants the submodule moved to the same commit. Both apps must land on
the same one.

**They must agree on the core version.** `bb-mobile-ffi` pins `bb-receipt-ffi`
itself; if this repo pins a different tag, cargo resolves two copies of the core
and the packaged library carries the wrong one. `Cargo.lock` is committed, so a
duplicate `bb-receipt-ffi` entry there is the tell — check it after any bump.

Bumping the core therefore has an extra hop: land it in `beanbeaver-core`, tag,
land it in `beanbeaver-mobile-util`, tag *that*, then move both pins here
together. The umbrella `~/src/bb/CLAUDE.md` owns the full order.

## Conventions & open items

- **Core tag:** in step with iOS at **v0.9.0**, reached *through* mobile-util
  v0.1.1 — see the table above before bumping either. When bumping, update **this**
  `Cargo.toml` and the iOS root together, rerun `./build-android.sh` here and
  `./build-xcframework.sh` in iOS. Check `crates/ffi/src/lib.rs` in the tag range
  first: a parser/rules-only bump needs no Kotlin change, but an FFI signature
  change means adapting `ReceiptScanner.kt` (as v0.5.0's `currency` +
  `tax_account` did) **and `shared/src/bin/batch_e2e.rs`** — nothing built that bin, so
  it silently rotted against the v0.6.x `scan()` arity and `ScanTimings.spans`
  until CI started compiling it. The one-command check is
  `git -C ../beanbeaver-core diff <from> <to> -- crates/ffi/src/lib.rs`; empty
  output means only the parse changed (v0.7.1 → v0.7.11 was empty; v0.7.11 →
  v0.8.4 was not — see below). **Non-empty is not the same as breaking**: read the
  diff. v0.8.4 → v0.9.0 touches five lines and none of them are exported — imports
  moved from `ocr_paddle::` to the `scan` composition root (core #61), so no Kotlin
  call site and no `batch_e2e.rs` symbol moved.
- **A warning is a record, not a string** (core v0.8.0). `ReceiptResult.warnings`
  is `[ReceiptWarning]` (`kind` + `message` + `afterItemIndex`) and
  `warning_after_item_indices` is gone. `WarningSeverity.kt` is the single place
  this app ranks a finding and the only place that may — no screen re-derives a
  severity from a kind, and nothing anywhere reads `message` to work out what
  happened. Treat `ReceiptWarningKind` as **open**: kinds are additive, so the
  `when` has an `else ->` that degrades to "show it quietly". `WarningSeverityTest`
  fails if a new core variant goes unranked. The two `[String]` schemas that
  predate kinds — the ledger `.json` sidecar and `batch_e2e`'s dump — stay
  strings and filter to `worthShowing`, so exporting the new `INFO` findings
  can't quietly change every details file and every E2E comparison. `batch_e2e`
  spells the same filter out in Rust (`worth_showing`, exhaustive on purpose).
- **A field rename reaches further than its call sites.** v0.7.0's
  `ReceiptItem.category -> account` and `tags: [String] -> [ItemTag]` also moved
  three JSON writers (`LedgerEntry` sidecar, `DebugInfoStore`, `BatchRunner`),
  the batch's own persistence, `batch_e2e`'s output shape and the
  `compare-e2e.py` that grades it. `ReceiptResultJson.decode` reads **both**
  tag shapes on purpose: a batch saved by the previous build is still on disk
  when the app updates. Pre-0.7.0 `category` is deliberately *not* read into
  `account` — it held a classifier key (`grocery_dairy`), not an account.
- The git deps can't be run via `cargo run -p <dep>`; codegen is hosted by the local
  `uniffi-bindgen` bin (see `shared/src/bin/uniffi-bindgen.rs`), pointed at the built
  `libbb_mobile_ffi`, which emits **both** namespaces in one pass.
- Keep the app teachable and small; prefer straightforward Kotlin over cleverness.
- **The spend arithmetic is not in this repo any more.** It is
  `spend-core` in `beanbeaver-mobile-util`, reached through the `bb_mobile_ffi`
  namespace, so this app and iOS compute spending from one implementation.
  `SpendSummary.kt` keeps only what is genuinely Android's: the **projection**
  (`SpendRecord` → `SpendInput`, including resolving `scannedAt` to a local
  calendar date, which needs a timezone database Rust deliberately does not
  carry) and **re-attachment** (Rust returns a record id and an item index; the
  screens want the app's own objects). Its public Kotlin surface is unchanged,
  so no screen moved. Don't re-add arithmetic here — a second implementation's
  opinion is the thing that was just deleted. `BudgetPrefs` keeps the *storage*
  of the budget target while the *resolution rule* moved too; a target is still
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
- **GitHub filing is idempotent per receipt *folder*, not per file.** `basename`
  carries the export's clock time (`hhmm`), so re-filing a receipt a minute later
  produces a path that has never existed — a per-file existence check can never
  see it, and the receipt files itself twice. The identity is the folder
  (merchant + date + image sha8, from `beanbeaverId`), so `isAlreadyFiled` does
  one directory listing per receipt and skips it if a `.beancount` is already
  there. Note `GET /contents/<dir>` returns a JSON **array**, which is why the
  transport splits into `api` / `apiArray` over a shared `http`.

## Staying in step with beanbeaver-ios

This repo is a deliberately parallel port of `beanbeaver-ios` (same core, same
UI language). When either side ships, the other usually has a twin to port.

**Find what's missing.** Both repos live under `~/src/bb/`. The last Android
commit that closed gaps marks the fork point:

```bash
git -C ~/src/bb/beanbeaver-android log --oneline master | head -1   # fork point
git -C ~/src/bb/beanbeaver-ios log --oneline --since=<fork date>
```

Everything iOS committed after that date is the port backlog. iOS commit
subjects carry the feature name; port them one commit at a time.

**Where each iOS feature lives here.**

| iOS (`BeanBeaver/BeanBeaver/…`) | Android twin |
|---|---|
| `ContentView.swift` (home, `ReceiptCard`, `SettingsView`) | `ui/BeanBeaverApp.kt`, `ui/SettingsScreen.kt` |
| `ReceiptPipeline.swift` / `ReceiptBatch.swift` / `BatchImportView.swift` | `receipt/ReceiptPipeline.kt`, `receipt/ReceiptBatch.kt`, `ui/BatchImportScreen.kt` |
| `ReceiptCaptureStore.swift` | `receipt/ReceiptCaptureStore.kt` |
| `SpendStore.swift` / `SpendSummary.swift` | `receipt/SpendStore.kt`, `receipt/SpendSummary.kt` |
| `SpendingView.swift` / `ReceiptsView.swift` / `CategoryItemsView.swift` | `ui/SpendingScreen.kt`, `ui/ReceiptsScreen.kt`, `ui/CategoryItemsScreen.kt` |
| `ItemRuleStore.swift` / `ItemRulesView.swift` | `receipt/ItemRuleStore.kt`, `ui/ItemRulesScreen.kt` |
| `Theme.swift` (`CategoryDisplay`/`PriceFormat`/`ReceiptDateFormat`/`AmountPrivacy`) | `ui/Format.kt`, `ui/AmountPrivacy.kt` (`BudgetPrefs` lives in `receipt/SpendStore.kt`) |
| `Theme.swift` (`ExportStatusDot`, `bbExported`/`bbUnexported`) | `ui/Components.kt`, `ui/theme/Theme.kt` |
| `WarningSeverity.swift` | `receipt/WarningSeverity.kt` (ranking, no Compose) + `ui/Format.kt` (its colors/icons) |
| `PhotoSaver.swift` | `receipt/PhotoSaver.kt` |
| `LedgerExport.swift` / `LedgerSettingsView.swift` | `export/LedgerExport.kt` + `github/GitHubSyncViewModel.kt`, `ui/GitHubSettingsScreen.kt` |
| `GitHubLedger.swift` / `GitHubDeviceFlow.swift` | `github/GitHubLedger.kt` / `github/GitHubApp.kt` |
| `MoneyManagerExport.swift` | `export/MoneyManagerExport.kt` |
| `DebugInfoStore.swift` / `DataDump.swift` / `Entitlements.swift` | `debug/…`, `Entitlements.kt` |

**Deliberately not ported** (don't re-investigate): iOS launch-arg harnesses
(`-dumpSpending`, `-showAmounts`, `-scrollToDebug`, `-showBatchImport`) — Android
has no process-args convention; use logcat and `scripts/` instead. The
Files-inbox ledger destination is commented out on iOS too, so Android has no
twin by design. iOS's CI ORT-cache self-heal is iOS-specific. `PhotoSaver`'s
`notAuthorized` failure has no twin either: a scoped-storage MediaStore insert
needs no runtime permission at minSdk 34, so there is nothing to refuse.

**Where the two diverge on purpose.** iOS's export target is a picker (GitHub /
Money Manager / a Files inbox), so its home status line and backlog bar name
whichever is selected; Android's only configurable ledger destination is GitHub,
so `exportStatusLine` says "GitHub" outright. `reachedTargets` still reads what
receipts actually reached — Money Manager gets there via the share sheet — so
the line can say "filed to GitHub and Money Manager" even though only one of
them is a setting.

**A port is not done until it compiles.** Kotlin has no Swift argument labels,
and translating `func month(id:from:)` into `fun month(id: String, from records:
List<...>)` is a syntax error, not a style choice — `for` is a hard keyword on
top of that. Greps and brace-balance will not catch any of it. Run, on the Mac,
before opening a PR:

```bash
./build-android.sh                 # regenerates the git-ignored UniFFI Kotlin
./gradlew :app:assembleFullDebug :app:assembleFossDebug \
  :app:testFullDebugUnitTest :app:lintFullDebug
cargo check --bin batch_e2e        # CI builds this bin; it has rotted before
```

The first is not optional: `bbreceiptkit/src/main/kotlin/uniffi/` is generated,
so nothing in the app compiles until it exists.
