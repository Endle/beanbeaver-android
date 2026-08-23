#!/usr/bin/env python3
"""Regenerate scripts/ort-required-ops.config -- the operator list for the
reduced-operator ONNX Runtime build (`--include_ops_by_config`).

No single ORT tool produces a usable list, because the operators a session needs
are not the operators in the model file: the session-init optimizer both creates
and removes them. So this builds three lists and unions them:

  A  raw .onnx                 -- ops the runtime may KEEP that offline folding drops
  B  offline .ort conversion   -- the fusions the raw graph lacks
  C  runtime-optimized dump    -- the graph ORT itself says it builds, as a check

The union is closed under whether a given fusion fires, which is the property
that matters; see the header of ort-required-ops.config for the full rationale.

Rewrites only the operator rows and the recorded model hashes. The prose header
is hand-maintained -- update it if the numbers this prints change.

The python `onnxruntime` MUST match the ORT version build-ort-android.sh builds
(which fusions fire is a property of that version); this refuses to run if it
cannot confirm that.

    scripts/gen-ort-ops-config.py                 # rewrite the config in place
    scripts/gen-ort-ops-config.py --dry-run       # just print the tables
"""
import argparse, collections, hashlib, pathlib, re, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODELS_DEFAULT = ROOT / "app/src/main/assets/models"
CONFIG_DEFAULT = ROOT / "scripts/ort-required-ops.config"
GEN = ROOT / "target/ort/src/tools/python/create_reduced_build_config.py"


def die(msg):
    sys.exit(f"gen-ort-ops-config: {msg}")


def expected_ort_version():
    """The ORT version the Android build links, from ort-sys's dist.txt."""
    hits = list(ROOT.glob("target/**/ort.dist.txt")) + list(
        ROOT.glob("target/**/download/dist.txt")
    )
    for h in hits:
        m = re.search(r"/ms@([0-9][0-9.]*)/", h.read_text())
        if m:
            return m.group(1)
    return None


def sh(*a):
    r = subprocess.run([str(x) for x in a], capture_output=True, text=True)
    if r.returncode != 0:
        die(f"failed: {' '.join(str(x) for x in a)}\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
    return r.stdout


def parse(path):
    """config text -> {(domain, opset): {ops}}"""
    out = collections.defaultdict(set)
    for line in pathlib.Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        dom, vers, ops = line.split(";")
        for v in vers.split(","):
            out[(dom, int(v))] |= {o.strip() for o in ops.split(",") if o.strip()}
    return out


def flat(d):
    return {(dom, op) for (dom, _), ops in d.items() for op in ops}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=pathlib.Path, default=MODELS_DEFAULT)
    ap.add_argument("--config", type=pathlib.Path, default=CONFIG_DEFAULT)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    try:
        import onnxruntime as rt
    except ImportError:
        die("needs the python `onnxruntime` (and `onnx`, `flatbuffers`) installed")

    want = expected_ort_version()
    if want is None:
        print("!! could not read the ort-sys pin; cannot confirm the version matches.")
        print(f"!! python onnxruntime is {rt.__version__}. Run `cargo fetch` and retry.")
    elif rt.__version__ != want:
        die(
            f"python onnxruntime is {rt.__version__} but the Android build links {want}.\n"
            f"  Which fusions fire is a property of the ORT version, so a list generated\n"
            f"  by a different one is not the list this build needs.\n"
            f"  Fix:  pip install 'onnxruntime=={want}'"
        )
    if not GEN.exists():
        die(f"{GEN} missing -- run scripts/build-ort-android.sh once to clone the ORT source")

    models = sorted(args.models.glob("*.onnx"))
    if not models:
        die(f"no .onnx under {args.models}")

    work = pathlib.Path(tempfile.mkdtemp(prefix="bb-ortops-"))
    raw, opt, ortfmt = work / "raw", work / "opt", work / "ortfmt"
    for d in (raw, opt, ortfmt):
        d.mkdir(parents=True)
    for m in models:
        shutil.copy2(m, raw / m.name)
        shutil.copy2(m, ortfmt / m.name)

    # A -- raw graph
    sh(sys.executable, GEN, "-f", "ONNX", raw, work / "a.config")

    # C -- the graph the runtime actually builds
    for m in sorted(raw.glob("*.onnx")):
        so = rt.SessionOptions()
        so.graph_optimization_level = rt.GraphOptimizationLevel.ORT_ENABLE_ALL
        so.optimized_model_filepath = str(opt / m.name)
        rt.InferenceSession(str(m), so, providers=["CPUExecutionProvider"])
    sh(sys.executable, GEN, "-f", "ONNX", opt, work / "c.config")

    # B -- offline .ort conversion
    sh(sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort",
       ortfmt, "--optimization_style", "Fixed")
    cands = [p for p in ortfmt.rglob("*.config") if "required_operators" in p.name]
    if not cands:
        die("convert_onnx_models_to_ort produced no config")
    b = [p for p in cands if "type_reduction" not in p.name] or cands
    shutil.copy2(b[0], work / "b.config")

    A, B, C = (parse(work / f"{n}.config") for n in "abc")
    U = collections.defaultdict(set)
    for src in (A, B, C):
        for k, v in src.items():
            U[k] |= v

    print(f"ORT {rt.__version__}; {len(models)} models\n")
    for name, d in (("A raw", A), ("B offline .ort", B), ("C runtime-opt", C), ("UNION", U)):
        print(f"  {name:16} {len(flat(d)):3d} ops, {len(d):2d} (domain,opset) rows")
    print()
    named = (("A raw", A), ("B offline .ort", B), ("C runtime-opt", C))
    for name, d in named:
        others = set().union(*[flat(x) for n, x in named if n != name])
        uniq = sorted(op for _, op in flat(d) - others)
        print(f"  {name:16} unique: {', '.join(uniq) if uniq else '-'}")

    body = "".join(
        f"{dom};{ver};{','.join(sorted(U[(dom, ver)]))}\n" for dom, ver in sorted(U)
    )
    hashes = "".join(
        f"#   {hashlib.sha256(m.read_bytes()).hexdigest()}  {m.name}\n" for m in models
    )

    if args.dry_run:
        print("\n--- would write ---\n" + body, end="")
        return

    old = args.config.read_text()
    new = re.sub(r"(?m)^(?!#)(?!$).*\n?", "", old).rstrip("\n") + "\n" + body
    new = re.sub(
        r"(# Models this list was generated from[^\n]*\n)(?:#   \S+  \S+\n)+",
        lambda m: m.group(1) + hashes,
        new,
    )
    args.config.write_text(new)
    print(f"\nwrote {args.config}")


if __name__ == "__main__":
    main()
