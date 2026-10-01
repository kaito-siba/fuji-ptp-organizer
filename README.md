# fuji-ptp-organizer

Fujifilm のカメラを Android 端末と USB 接続し、PTP で SD カード内の写真を閲覧・取り込みする Android アプリ。

- 対応機種: FUJIFILM X100VI（機種差分は `CameraProfile` で吸収）
- MVP: 写真一覧・プレビュー・選択ダウンロード
- 将来: GPSLogger の GPX と突き合わせたジオタグ付与

現在は **M0（雛形 + 実機診断画面）** の段階。

## ドキュメント

- [設計](docs/design.md)
- [開発ガイド（ビルド、エミュレータ、実機での診断手順）](docs/development.md)

## クイックスタート

```sh
./gradlew test                 # ユニットテスト
./gradlew :app:installDebug    # 接続中の端末/エミュレータにインストール
```

エミュレータでは「デモモード」で同梱のダミーデータを使って動かせる。
