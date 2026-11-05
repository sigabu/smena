"""Small adb helper for inspecting the native app; no browser dependencies.

Usage: python scripts/android_ui.py dump|screenshot|tap|input|swipe [arguments]
ANDROID_HOME and ANDROID_SERIAL can override SDK location and test device.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

SDK = Path(os.environ.get("ANDROID_HOME", "D:/Android/sdk"))
ADB = SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")


def adb(*args, binary=False):
    result = subprocess.run([str(ADB), "-s", SERIAL, *args], check=True, capture_output=True, timeout=30)
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/smena-ui.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/smena-ui.xml")).iter("node"))


def tap(label):
    snapshot = nodes()
    matches = [n for n in snapshot if n.get("content-desc") == label]
    if not matches:
        matches = [n for n in snapshot if n.get("text") == label]
    for n in matches:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return
    raise RuntimeError(f"UI element not found: {label}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    command = sys.argv[1]
    if command == "dump":
        for n in nodes():
            if n.get("text") or n.get("content-desc"):
                print(n.get("class").split(".")[-1], n.get("text"), "|", n.get("content-desc"), n.get("bounds"))
    elif command == "screenshot":
        path = Path(sys.argv[2]); path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(adb("exec-out", "screencap", "-p", binary=True)); print(path)
    elif command == "tap":
        tap(sys.argv[2])
    elif command == "input":
        adb("shell", "input", "text", sys.argv[2])
    elif command == "swipe":
        adb("shell", "input", "swipe", *sys.argv[2:])
    else:
        raise SystemExit("Unknown command")
