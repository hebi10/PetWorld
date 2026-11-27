"""Exercise a disposable PetWorld release update using local synthetic data."""
from pathlib import Path
import re
import subprocess
import sys
import time
from xml.etree import ElementTree as ET

APP = "com.haebi.petworld"
NAME = "R8PilotCat"
DUMP = "/sdcard/petworld-dex04.xml"
LAUNCHER_ANR = {"Pixel Launcher isn't responding", "Launcher3 isn't responding"}


def adb(*args, binary=False):
    return subprocess.run(["adb", *args], check=True, capture_output=True,
                          text=not binary, timeout=70).stdout


def hierarchy():
    adb("shell", "uiautomator", "dump", DUMP)
    return adb("exec-out", "cat", DUMP)


def android_launcher_anr(nodes):
    return any(node.get("package") == "android" and node.get("text") in LAUNCHER_ANR
               for node in nodes)


def close_unresponsive_launcher(nodes):
    for node in nodes:
        if node.get("package") == "android" and node.get("text") == "Close app":
            a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds", "")))
            adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2))
            print("Closed an Android launcher ANR dialog", flush=True)
            return
    raise AssertionError("Android launcher ANR has no known close action")


def label(value, contains=False):
    def match(node):
        labels = (node.get("text", ""), node.get("content-desc", ""))
        return any(value in text if contains else value == text for text in labels)
    return match


def locate(match, description, scroll=False):
    size = adb("shell", "wm", "size")
    width, height = map(int, re.findall(r"(\d+)x(\d+)", size)[-1])
    recovered_launcher = False
    for direction in [None] * 4 + (["down"] * 5 + ["up"] * 7 if scroll else [None] * 5):
        if direction:
            top, bottom = height // 3, height * 3 // 4
            y1, y2 = (bottom, top) if direction == "down" else (top, bottom)
            adb("shell", "input", "swipe", str(width // 2), str(y1),
                str(width // 2), str(y2), "370")
        time.sleep(.6)
        try:
            nodes = list(ET.fromstring(hierarchy()).iter("node"))
        except (subprocess.CalledProcessError, ET.ParseError):
            continue
        if android_launcher_anr(nodes):
            if recovered_launcher:
                raise AssertionError("Android launcher ANR reappeared after recovery")
            close_unresponsive_launcher(nodes)
            recovered_launcher = True
            time.sleep(1)
            continue
        for node in nodes:
            if node.get("package") != APP or not match(node):
                continue
            coords = re.findall(r"\d+", node.get("bounds", ""))
            if len(coords) == 4:
                a, b, c, d = map(int, coords)
                if c > a and d > b:
                    return ((a + c) // 2, (b + d) // 2)
    raise AssertionError(f"Missing PetWorld UI element: {description}")


def tap(value, *, contains=False, scroll=False):
    x, y = locate(label(value, contains), value, scroll=scroll)
    adb("shell", "input", "tap", str(x), str(y))
    print(f"Tapped {value}", flush=True)


def see(value, *, contains=False, scroll=False):
    locate(label(value, contains), value, scroll=scroll)
    print(f"Confirmed {value}", flush=True)


def fill(prefix, value):
    def is_field(node):
        return node.get("class") == "android.widget.EditText" and label(prefix, True)(node)

    try:
        x, y = locate(is_field, prefix)
        adb("shell", "input", "tap", str(x), str(y))
    except AssertionError:
        tap(prefix, contains=True, scroll=True)
    adb("shell", "input", "text", value)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")


def start():
    adb("shell", "am", "force-stop", APP)
    state = adb("shell", "am", "start", "-W", "-n", f"{APP}/.MainActivity")
    assert "Status: ok" in state, state


def capture(folder, title):
    (folder / f"{title}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    (folder / f"{title}.xml").write_text(hierarchy(), encoding="utf-8")


def run(folder):
    assert any(adb("shell", "getprop", name).strip() == "1"
               for name in ("ro.kernel.qemu", "ro.boot.qemu")), "Require disposable emulator"
    assert not adb("shell", "pm", "list", "packages", APP).strip(), "Require fresh app"
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    adb("install", str(folder / "baseline.apk"))
    start()
    tap("반려동물 등록")
    fill("이름", NAME)
    tap("등록 완료", scroll=True)
    see(f"{NAME}의 하루", contains=True)
    tap("빠른 기록 추가")
    tap("음수")
    see("음수 기록", contains=True)
    fill("음수량", "123")
    tap("기록 저장", scroll=True)
    tap("기록")
    see("123mL", contains=True, scroll=True)
    capture(folder, "baseline-record")

    adb("install", "-r", str(folder / "candidate.apk"))
    start()
    see(f"{NAME}의 하루", contains=True)
    tap("기록")
    see("123mL", contains=True, scroll=True)
    tap("123mL", contains=True)
    see("발생 시각", contains=True, scroll=True)
    capture(folder, "optimized-updated-record-detail")
    print("Verified offline profile and recorded water amount persist across optimized release update.", flush=True)


if __name__ == "__main__":
    folder = Path(sys.argv[1])
    try:
        run(folder)
    except Exception:
        for attempt in (lambda: capture(folder, "failure"),
                        lambda: (folder / "logcat.txt").write_text(adb("logcat", "-d"), encoding="utf-8")):
            try:
                attempt()
            except Exception as error:
                print(f"Diagnostics could not be captured: {error}", file=sys.stderr)
        raise
