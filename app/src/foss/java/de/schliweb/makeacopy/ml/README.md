# Vendored from MakeACopy — do not edit

Every `.java` file under this directory is copied **verbatim** from
[MakeACopy](https://github.com/egdels/makeacopy) (Apache-2.0), and must stay
that way: `diff -r` against an upstream checkout should be empty.

That is a deliberate choice over porting the logic. `DocQuadPostprocessor` alone
is 34 KB of corner geometry; rewriting it — by hand or otherwise — would relaunch
its bug history, obscure where it came from, and strand us on a fork nobody can
diff. Copying keeps provenance legible and upstream fixes mergeable.

Two things make "no edits" possible, and both live *outside* this directory:

- `../BuildConfig.java` — a shim supplying the two constants these files read
  from MakeACopy's own generated BuildConfig. It is ours, and says so.
- Lombok, in `app/build.gradle.kts` — `DocQuadPostprocessor` and `DocQuadScore`
  are `@UtilityClass`. The processor is cheaper than editing them.

Only the ML corner detector was taken. MakeACopy's OpenCV detectors, the corner
refiner and the composite/factory plumbing were not: we have no OpenCV, and
BeanBeaver keeps its own PP-OCRv5 pipeline rather than adopting theirs, so the
platforms don't diverge.

Attribution and the model's training-data obligations are in
`THIRD_PARTY_NOTICES.md`.
