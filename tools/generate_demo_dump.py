#!/usr/bin/env python3
"""エミュレータのデモモード用に、X100VI を模した DeviceDump JSON を生成する。

サムネイルは ImageMagick (convert) で生成する。ファイル一覧は架空だが、形式コード・対応オペレーション・
USB 記述子などは実機ダンプ（fixtures/dumps/x100vi-fw132.json, FW1.32）に合わせてある。

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

STORAGE = 0x10000001
FMT_ASSOCIATION = 0x3001
FMT_JPEG = 0x3801
FMT_QUICKTIME = 0x300D
FMT_RAF = 0xB103
FMT_UNDEFINED = 0x3000  # HIF の実際のコードは未確認なので Undefined にしておく

OPERATIONS = [0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1006, 0x1007, 0x1008,
              0x1009, 0x100A, 0x100B, 0x100C, 0x100D, 0x100F, 0x1014, 0x1015,
              0x1016, 0x101B, 0x900C, 0x900D, 0x901D, 0x9801, 0x9802, 0x9803, 0x9805]
EVENTS = [0x4002, 0x4003, 0x4004, 0x4005, 0x4006, 0x4008, 0x4009]
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
    is_file = fmt != FMT_ASSOCIATION
    is_image = fmt in (FMT_JPEG, FMT_RAF, FMT_UNDEFINED)
    return {
        "handle": handle, "storageId": STORAGE, "format": fmt, "compressedSize": size,
        "thumbFormat": 0x3808 if is_file else 0,  # 実機は JFIF を返す
        "thumbPixWidth": 160 if is_file else 0,
        "thumbPixHeight": 120 if is_file else 0,
        "imagePixWidth": 7728 if is_image else 0,
        "imagePixHeight": 5152 if is_image else 0,
        "parent": parent, "name": name,
        "dateCreatedMillis": ms(created), "dateModifiedMillis": ms(created),
        # Fujifilm は Keywords にカメラ時計の日時文字列を入れてくる
        "keywords": created.strftime("%Y%m%dT%H%M%S"),
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
            files.append((f"{stem}.RAF", FMT_RAF, 86_000_000))
        if i in (17, 33):
            files = [(f"{stem}.MOV", FMT_QUICKTIME, 350_000_000)]
        for name, fmt, size in files:
            objects.append(obj(handle, name, fmt, 2, size, created))
            if len(thumbs) < len(COLORS) and fmt == FMT_JPEG:
                color = COLORS[len(thumbs)]
                thumbs.append({"handle": handle, "objectFormat": fmt, "fileName": name,
                               "size": 0, "base64": thumbnail(stem, color)})
            handle += 1

    # RAF / HIF のフォールバックサムネイル（動画は実機でも GetThumb が失敗するので用意しない）
    for fmt, label in ((FMT_RAF, "RAF"), (FMT_UNDEFINED, "HEIF")):
        first = next(o for o in objects if o["format"] == fmt)
        thumbs.append({"handle": first["handle"], "objectFormat": fmt, "fileName": first["name"],
                       "size": 0, "base64": thumbnail(label, "#7f8c8d")})
    for t in thumbs:
        t["size"] = len(base64.b64decode(t["base64"]))

    dump = {
        "schemaVersion": 1,
        "createdAt": base.isoformat(),
        "source": "demo",
        "usb": {"vendorId": 0x04CB, "productId": 0x0305, "productName": "USB PTP Camera",
                "version": "1.32", "interfaces": [
                    {"id": 0, "alternateSetting": 0, "interfaceClass": 6, "interfaceSubclass": 1,
                     "interfaceProtocol": 1, "endpoints": [
                         {"address": 0x01, "type": 2, "direction": 0, "maxPacketSize": 512, "interval": 1},
                         {"address": 0x81, "type": 2, "direction": 128, "maxPacketSize": 512, "interval": 0},
                         {"address": 0x82, "type": 3, "direction": 128, "maxPacketSize": 32, "interval": 11}]}]},
        "deviceInfo": {"manufacturer": "FUJIFILM", "model": "X100VI", "version": "demo",
                       "serialNumber": "DEMO-0000", "operationsSupported": OPERATIONS,
                       "eventsSupported": EVENTS},
        "storages": [{
            "info": {"storageId": STORAGE, "description": "External Memory (demo)", "volumeIdentifier": None,
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
