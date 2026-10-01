# 実機ダンプ置き場

アプリの診断画面で書き出した JSON（`fujiptp-<機種>-usb-<日時>.json`）をここに置く。

- プロファイルの値（ObjectFormat コード、GetThumb の可否など）を決める根拠になる
- `FakePtpClient` のフィクスチャとして、実機なしの UI 開発・テストに使う

リポジトリは公開なので、コミットする前にシリアル番号とサムネイル（写真の縮小画像）を取り除くこと。

```sh
python3 tools/sanitize_dump.py ~/Downloads/fujiptp-X100VI-usb-XXXX.json fixtures/dumps/x100vi-fwXXX.json
```

| ファイル | 機種 / ファームウェア | 端末 |
|---|---|---|
| `x100vi-fw132.json` | X100VI / 1.32 | Nothing Phone (A024), Android 16 |
