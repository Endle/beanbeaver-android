# BeanBeaver Android - Scan Receipts, Track Spending

BeanBeaver turns a photo of a receipt into an itemized Beancount transaction — entirely on
your phone.

## Parsing Grocery Receipts
Snap a receipt with the camera (or pick one from your photo library). BeanBeaver reads it with on-device text recognition, then extracts the merchant, date, line items, and total cost. It provides detail of actual categories (Dairy $30, Meat $50, Drink $40, Fruit $30), instead of one lump sum record (Costco $100, T&T $50).


## Privacy is Top Priority
BeanBeaver uses an on-device OCR model. Scanning, parsing, and categorizing all happen on your device. There is no account registration, no analytics, no user profiling or fingerprinting, and no cloud server. Everything stays on your phone unless you explicitly export it somewhere. The full policy is in [`PRIVACY.md`](PRIVACY.md).

## Open Source Project
The app and its parsing engine are MIT-licensed:

- https://github.com/Endle/beanbeaver-android
- https://github.com/Endle/beanbeaver-core

The Google Play and SafeHaven builds use the [GMS ML Kit Document
Scanner](https://developers.google.com/ml-kit/vision/doc-scanner/android) for the
guided capture screen, and nothing else. A **fully OSS build** with no Google Play
dependency is in progress.

Third-party components are credited in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Building from source

```bash
git clone --recurse-submodules https://github.com/Endle/beanbeaver-android
cd beanbeaver-android
./shared/scripts/fetch-models.sh   # PP-OCRv5 models — not committed
./build-android.sh                 # Rust core → .so + UniFFI Kotlin
./gradlew :app:assemblePlayDebug   # APK
```

Requires Android Studio (JDK 21 + SDK), the NDK, and Rust 1.80+; the app is
arm64-v8a only. Full instructions, build knobs and the pre-PR checklist are in
[`BUILDING.md`](BUILDING.md).
