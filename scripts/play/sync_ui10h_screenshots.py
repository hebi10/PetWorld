#!/usr/bin/env python3
"""Synchronize Play screenshots from genuine Android API 35 UI captures."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
CAPTURE_DIR = ROOT / "qa/captures"
ASSETS = ROOT / "assets/play-store/ko-KR"
MANIFEST = ASSETS / "screenshots-manifest.json"
VALIDATOR = ROOT / "scripts/play/listing_images.py"
SOURCE_FILES = (
    "S03-home.png", "S05-quick-record-sheet.png", "S06-food-form.png",
    "S08-elimination-form.png", "S11-record-list.png", "S18-health.png",
    "S14-statistics.png", "S31-backup-password-dialog.png",
)
DEST_FILES = (
    "01-home.png", "02-quick-record.png", "03-food.png", "04-elimination.png",
    "05-records.png", "06-health.png", "07-statistics.png", "08-backup.png",
)
FONT_DIR = Path("/usr/share/fonts/opentype/noto")


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def typeface(size, bold):
    path = FONT_DIR / ("NotoSansCJK-Bold.ttc" if bold else "NotoSansCJK-Regular.ttc")
    if not path.exists():
        path = FONT_DIR / "NotoSansCJK-Regular.ttc"
    require(path.exists(), "Korean Noto CJK font not installed")
    return ImageFont.truetype(str(path), size, index=0)


def caption(draw, phrase, ypos, size, bold, color):
    while size >= 30:
        face = typeface(size, bold)
        region = draw.textbbox((0, 0), phrase, font=face)
        if region[2] - region[0] <= 1160:
            draw.text((90, ypos), phrase, font=face, fill=color)
            return
        size -= 2
    raise RuntimeError("Play description text overflows")


def compose(input_image, title, subtitle):
    require(input_image.size == (1080, 2400), "Android capture must be 1080x2400")
    art = Image.new("RGB", (1350, 2400), "#FAFAFA")
    draw = ImageDraw.Draw(art)
    caption(draw, title, 38, 70, True, "#141414")
    caption(draw, subtitle, 147, 38, False, "#5B5B5B")
    width = 955
    height = round(input_image.height * width / input_image.width)
    x = (1350 - width) // 2
    top = 225
    require(top + height < 2400, "Original UI would be cropped")
    screenshot = input_image.convert("RGB").resize((width, height), Image.Resampling.LANCZOS)
    art.paste(screenshot, (x, top))
    draw.rectangle((x, top, x + width - 1, top + height - 1), outline="#CCCCCC", width=2)
    return art


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-sha", required=True)
    ap.add_argument("--run-id", required=True, type=int)
    ap.add_argument("--artifact-id", required=True, type=int)
    ap.add_argument("--artifact-digest", required=True)
    args = ap.parse_args()

    require(re.fullmatch("[a-f0-9]{40}", args.source_sha), "Invalid Android source SHA")
    require(args.run_id > 0 and args.artifact_id > 0, "Missing screenshot artifact")
    require(re.fullmatch("(?:sha256:)?[a-f0-9]{64}", args.artifact_digest), "Missing capture digest")
    log = (CAPTURE_DIR / "instrumentation.log").read_text(encoding="utf-8", errors="replace")
    require("OK (1 test)" in log and "FAILURES!!!" not in log, "Real UI capture test failed")

    meta = json.loads(MANIFEST.read_text(encoding="utf-8"))
    require(meta.get("package") == "com.haebi.petworld" and meta.get("locale") == "ko-KR",
            "Wrong application or language")
    records = meta.get("files", [])
    require(len(records) == 8, "Store inventory must contain eight screenshots")
    for n, row in enumerate(records):
        original_path = "screenshots/light/" + SOURCE_FILES[n]
        require(row.get("original_capture") == original_path and
                row.get("file") == DEST_FILES[n] and row.get("order") == n + 1,
                "Screen mapping/order changed without approval")
        original = CAPTURE_DIR / original_path
        require(original.is_file(), "Missing Android original: " + original_path)
        data = original.read_bytes()
        require(len(data) > 10000, "Empty Android screenshot")
        with Image.open(original) as image:
            image.load()
            new_art = compose(image, row["title"], row["subtitle"])
        target = ASSETS / DEST_FILES[n]
        new_art.save(target, format="PNG", compress_level=9)
        output_bytes = target.read_bytes()
        require(len(output_bytes) < 8 * 1024 * 1024, "Play PNG too large")
        row.update(original_sha256=digest(data), image_sha256=digest(output_bytes),
                   width=1350, height=2400, mode="RGB", bytes=len(output_bytes))
    require(len(set(row["image_sha256"] for row in records)) == 8, "Duplicate Play screenshots")

    meta["generated_date"] = datetime.now(timezone.utc).date().isoformat()
    meta["source"] = {
        "repo": "hebi10/PetWorld", "commit": args.source_sha,
        "workflow_run": args.run_id, "artifact_id": args.artifact_id,
        "artifact_sha256": args.artifact_digest.removeprefix("sha256:"),
        "test": "OK (1 test)",
        "test_fixture": "Synthetic examples: 나비/초코; not private user data",
    }
    meta["rendering"] = "Real Android captures, aspect-preserving resampling and Korean captions. Original UI unchanged."
    meta["play_upload_status"] = "UI-10-H assets prepared; Play API readback pending"
    MANIFEST.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    validator = VALIDATOR.read_text(encoding="utf-8")
    old_id = "CAPTURE_SOURCE_RUN_ID = 42780690910"
    old_sha = "CAPTURE_SOURCE_COMMIT = 'e3871fe00c9f986515daf97a7345d490d185aa34'"
    require(old_id in validator and old_sha in validator,
            "Unexpected previous capture allowlist; abort without overwriting")
    validator = validator.replace(old_id, f"CAPTURE_SOURCE_RUN_ID = {args.run_id}")
    validator = validator.replace(old_sha, f"CAPTURE_SOURCE_COMMIT = '{args.source_sha}'")
    VALIDATOR.write_text(validator, encoding="utf-8")

    (ASSETS / "README-screenshots.md").write_text(
        "# PetWorld UI-10-H — Google Play 실제 Android 설명 이미지\n\n"
        "- 대상: com.haebi.petworld / ko-KR, 1350×2400 RGB PNG 8장.\n"
        "- 실제 Android API 35 Pixel 6 에뮬레이터의 라이트 화면만 활용했습니다.\n"
        "- 화면 UI를 변경하거나 생성하지 않고 비율 유지 축소와 한국어 제목/중성 회색 여백만 추가했습니다.\n"
        "- 테스트용 가상 반려동물 나비/초코 데이터입니다.\n"
        f"- 원본 커밋: {args.source_sha}.\n"
        f"- Android 화면 캡처 run: https://github.com/hebi10/PetWorld/actions/runs/{args.run_id}\n"
        f"- 원본 artifact ID: {args.artifact_id}; SHA-256: {args.artifact_digest}.\n"
        "- 원본/최종 SHA-256, 용량, 순서와 화면 이름은 screenshots-manifest.json을 참조합니다.\n"
        "- 기존 앱 아이콘·대표 이미지·AAB/서명·테스트 트랙은 변경하지 않았습니다.\n"
        "- 이미지 생성과 Play API 업로드/심사/게시 상태는 서로 다릅니다.\n",
        encoding="utf-8",
    )
    print(f"PASS: {len(records)} real source screenshots, source={args.source_sha}, run={args.run_id}")


if __name__ == "__main__":
    main()
