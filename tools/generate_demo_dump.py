#!/usr/bin/env python3
"""エミュレータのデモモード用に、X100VI を模した DeviceDump JSON を生成する。

サムネイルは ImageMagick (convert) で生成する。値はすべて架空で、実機の挙動とは一致しない。
実機の診断ダンプが取れたら、それで置き換えてよい。

usage: python3 tools/generate_demo_dump.py
"""
import base64
import datetime
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/demo/x100vi-demo.json"

STORAGE = 0x00010001
FMT_ASSOCIATION = 0x3001
FMT_JPEG = 0x3801
FMT_QUICKTIME = 0x300D
FMT_UNDEFINED = 0x3000  # RAF / HIF の実際のコードは未確認なので Undefined にしておく

OPERATIONS = [0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1006, 0x1007, 0x1008,
              0x1009, 0x100A, 0x100B, 0x1014, 0x1015, 0x1016, 0x101B]
COLORS = ["#c0392b", "#d35400", "#27ae60", "#2980b9", "#8e44ad", "#2c3e50"]


def thumbnail(label: str, color: str) -> str:
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / "t.jpg"
        subprocess.run([
            "convert", "-size", "160x120", f"gradient:{color}-#f5f5f5",
            "-gravity", "center", "-pointsize", "20", "-fill", "white",
            "-annotate", "0", label, "-strip", "-quality", "70", str(path),
        ], check=True)
        return base64.b64encode(path.read_bytes()).decode()


def ms(dt: datetime.datetime) -> int:
    return int(dt.timestamp() * 1000)


def obj(handle, name, fmt, parent, size, created):
    return {
        "handle": handle, "storageId": STORAGE, "format": fmt, "compressedSize": size,
        "thumbFormat": FMT_JPEG if fmt != FMT_ASSOCIATION else 0,
        "thumbPixWidth": 160 if fmt != FMT_ASSOCIATION else 0,
        "thumbPixHeight": 120 if fmt != FMT_ASSOCIATION else 0,
        "imagePixWidth": 7728 if fmt in (FMT_JPEG, FMT_UNDEFINED) else 0,
        "imagePixHeight": 5152 if fmt in (FMT_JPEG, FMT_UNDEFINED) else 0,
        "parent": parent, "name": name,
        "dateCreatedMillis": ms(created), "dateModifiedMillis": ms(created),
    }


def main():
    base = datetime.datetime(2026, 9, 20, 9, 0, 0, tzinfo=datetime.timezone(datetime.timedelta(hours=9)))
    objects = [
        obj(1, "DCIM", FMT_ASSOCIATION, 0, 0, base),
        obj(2, "100_FUJI", FMT_ASSOCIATION, 1, 0, base),
    ]
    thumbs = []
    handle = 100
    for i in range(1, 41):
        created = base + datetime.timedelta(days=i // 14, minutes=i * 7)
        stem = f"DSCF{i:04d}"
        files = []
        if i % 5 == 0:
            files.append((f"{stem}.HIF", FMT_UNDEFINED, 9_800_000))
        else:
            files.append((f"{stem}.JPG", FMT_JPEG, 14_500_000))
        if i % 3 == 0:
            files.append((f"{stem}.RAF", FMT_UNDEFINED, 41_000_000))
        if i in (17, 33):
            files = [(f"{stem}.MOV", FMT_QUICKTIME, 350_000_000)]
        for name, fmt, size in files:
            objects.append(obj(handle, name, fmt, 2, size, created))
            if len(thumbs) < len(COLORS) and fmt == FMT_JPEG:
                color = COLORS[len(thumbs)]
                thumbs.append({"handle": handle, "objectFormat": fmt, "fileName": name,
                               "size": 0, "base64": thumbnail(stem, color)})
            handle += 1

    # RAF / HIF（Undefined 形式）と動画のフォールバックサムネイル
    for fmt, label in ((FMT_UNDEFINED, "RAW/HEIF"), (FMT_QUICKTIME, "MOVIE")):
        first = next(o for o in objects if o["format"] == fmt)
        thumbs.append({"handle": first["handle"], "objectFormat": fmt, "fileName": first["name"],
                       "size": 0, "base64": thumbnail(label, "#7f8c8d")})
    for t in thumbs:
        t["size"] = len(base64.b64decode(t["base64"]))

    dump = {
        "schemaVersion": 1,
        "createdAt": base.isoformat(),
        "source": "demo",
        "usb": {"vendorId": 0x04CB, "productId": 0x0000, "manufacturerName": "FUJIFILM",
                "productName": "X100VI (demo)", "interfaces": [
                    {"id": 0, "alternateSetting": 0, "interfaceClass": 6, "interfaceSubclass": 1,
                     "interfaceProtocol": 1, "endpoints": []}]},
        "deviceInfo": {"manufacturer": "FUJIFILM", "model": "X100VI", "version": "demo",
                       "serialNumber": "DEMO-0000", "operationsSupported": OPERATIONS,
                       "eventsSupported": [0x4002, 0x4003, 0x4004, 0x4005]},
        "storages": [{
            "info": {"storageId": STORAGE, "description": "SD (demo)", "volumeIdentifier": None,
                     "maxCapacity": 128 * 1024 ** 3, "freeSpace": 96 * 1024 ** 3},
            "handleCountAll": len(objects), "handleCountRoot": 1,
            "objects": objects, "objectsTruncated": False,
        }],
        "thumbnails": thumbs,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(dump, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(objects)} objects, {len(thumbs)} thumbnails)")


if __name__ == "__main__":
    main()
