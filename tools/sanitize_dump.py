#!/usr/bin/env python3
"""診断ダンプから公開リポジトリに載せたくない情報を取り除く。

- シリアル番号を伏せる
- サムネイル画像本体（写真の縮小画像）を削除する（サイズなどのメタ情報は残す）

usage: python3 tools/sanitize_dump.py <input.json> <output.json>
"""
import json
import sys


def main():
    src, dst = sys.argv[1], sys.argv[2]
    dump = json.load(open(src, encoding="utf-8"))
    dump["deviceInfo"]["serialNumber"] = "REDACTED"
    for thumb in dump.get("thumbnails", []):
        thumb["base64"] = None
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(dump, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {dst}")


if __name__ == "__main__":
    main()
