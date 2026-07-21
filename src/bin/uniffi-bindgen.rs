//! Local `uniffi-bindgen` entry point (library mode), used by
//! `build-android.sh` to emit Kotlin glue from the compiled core:
//!   cargo run -p beanbeaver-android-ffi-build --bin uniffi-bindgen -- \
//!     generate --library target/debug/libbb_receipt_ffi.so \
//!     --language kotlin --out-dir <dir>
//!
//! bb-receipt-ffi ships this same bin, but it's a git dependency (not a
//! workspace member), so `cargo run -p bb-receipt-ffi --bin uniffi-bindgen`
//! can't reach it. We host our own here.
fn main() {
    uniffi::uniffi_bindgen_main()
}
