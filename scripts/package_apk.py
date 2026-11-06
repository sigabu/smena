"""Package a verified build and write the GitHub update manifest.

Usage: python scripts/package_apk.py [--personal]
Run after assembleDebug with the matching publicBuild setting.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def package(personal=False):
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    metadata = json.loads((apk.parent / "output-metadata.json").read_text(encoding="utf-8"))["elements"][0]
    sdk = Path(os.environ.get("ANDROID_HOME", "D:/Android/sdk"))
    tools = sdk / "build-tools/36.0.0"
    subprocess.run([str(tools / ("apksigner.bat" if os.name == "nt" else "apksigner")), "verify", str(apk)], check=True)
    if not personal:
        # Never package embedded personal defaults as a public update.
        dump = subprocess.run([str(tools / ("aapt2.exe" if os.name == "nt" else "aapt2")), "dump", "resources", str(apk)],
                              check=True, capture_output=True, text=True, encoding="utf-8").stdout
        for name in ("default_source_url", "default_employee"):
            resource = re.search(r"resource[^\n]*string/" + name + r"\b[^\n]*\n([^\n]*)", dump)
            if resource is None or not re.search(r'\(\) ""\s*$', resource.group(1)):
                raise SystemExit("Public APK contains personal defaults; rebuild with -PpublicBuild=true")
    target = ROOT / "dist" / ("smena.apk" if personal else "smena-public.apk")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix(".apk.sha256").write_text(f"{digest}  {target.name}\n", encoding="utf-8")
    if not personal:
        manifest = {"versionCode": metadata["versionCode"], "versionName": metadata["versionName"],
                    "sha256": digest, "size": target.stat().st_size}
        (target.parent / "update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    else:
        versioned = target.parent / f"smena-{metadata['versionName']}.apk"
        shutil.copyfile(target, versioned)
        versioned.with_suffix(".apk.sha256").write_text(f"{digest}  {versioned.name}\n", encoding="utf-8")
        print(versioned)
    print(target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--personal", action="store_true")
    package(parser.parse_args().personal)
