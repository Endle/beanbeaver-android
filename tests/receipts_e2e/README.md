# E2E ground truth

One `<stem>.expected.json` per receipt fixture — the same schema and the same
grader (`shared/scripts/compare-e2e.py`) as `beanbeaver-ios/tests/receipts_e2e/`, so a
fixture graded there grades identically here.

**The images do not live here.** The only public fixture is the 2 MB redacted
Costco receipt the app already ships as its bundled sample
(`app/src/main/assets/samples/costco_20260301_redact.jpg`), and one copy in the
repo is enough. `scripts/e2e-fixtures.sh` stitches the two halves into the
`<stem>.jpg` + `<stem>.expected.json` layout the harnesses want:

```bash
FIX=$(./scripts/e2e-fixtures.sh)      # host: scan + grade, no device
cargo run --release --bin batch_e2e -- --models models --in-dir "$FIX" --out "$FIX/batch_out.json"
python3 shared/scripts/compare-e2e.py --results "$FIX/batch_out.json" --manifest "$FIX/manifest.json"

./scripts/android-e2e.sh "$FIX" --pilot   # same fixture, on a real emulator
```

Receipts with PII stay in `beanbeaver-private-test/` and are run with
`PRIVATE_RULES=… ./scripts/android-e2e.sh <that dir>` — never added here.
