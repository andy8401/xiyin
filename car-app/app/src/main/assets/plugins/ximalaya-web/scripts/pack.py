"""Package the plugin into an installable ZIP (Python standard library only)."""
from __future__ import annotations

import hashlib
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
INCLUDE_DIRS = ("src", "scripts", "tests")
INCLUDE_FILES = ("plugin.json", "package.json", "index.js", "README.md")
EXCLUDE_SUFFIXES = (".pyc",)


def main() -> None:
    manifest = json.loads((ROOT / "plugin.json").read_text(encoding="utf-8"))
    DIST.mkdir(exist_ok=True)
    target = DIST / f"{manifest['id']}-v{manifest['version']}.zip"
    files = [ROOT / name for name in INCLUDE_FILES]
    for directory in INCLUDE_DIRS:
        files.extend(sorted(path for path in (ROOT / directory).rglob("*") if path.is_file()))
    missing = [str(path) for path in files if not path.is_file()]
    if missing:
        raise SystemExit(f"missing required files: {missing}")

    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            if path.suffix in EXCLUDE_SUFFIXES:
                continue
            archive.write(path, path.relative_to(ROOT).as_posix())

    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f"packed: {target}")
    print(f"files: {len(files)}, size: {target.stat().st_size} bytes")
    print(f"sha256: {digest}")
    with zipfile.ZipFile(target) as archive:
        for name in archive.namelist():
            print(f"  {name}")


if __name__ == "__main__":
    main()
