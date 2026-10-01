# 開発ガイド

## 必要なもの

- Android Studio（最新の安定版）
- JDK 17 以上（Android Studio 同梱のもので可）
- Android SDK Platform 36

リポジトリのルートを Android Studio で開き、Gradle Sync を実行すればビルドできる。

### NixOS の場合

`flake.nix` に Android SDK・JDK 17・adb を揃えた devShell を用意してある。

```sh
nix develop                     # 初回は flake.lock が生成されるのでコミットしておく
./gradlew :app:assembleDebug    # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # adb で繋いだ端末にインストール
```

- SDK のバージョン（compileSdk、build-tools）は `gradle/libs.versions.toml` の `android-*` を flake も読んでいる。
  変えるときはそこだけ直せばいい
- AGP が Maven から取ってくる aapt2 は NixOS では動かないため、devShell が `GRADLE_OPTS` で SDK 内の aapt2 を使わせている
- Android SDK は unfree なので、flake 内で `allowUnfree` とライセンス同意を有効にしている
- direnv を使うなら `.envrc` に `use flake` と書けば自動で入れる（`.envrc` は gitignore 済み）
- adb は Wi-Fi 経由（後述のワイヤレスデバッグ）なら追加設定は不要。USB 接続で使う場合は NixOS 側で udev ルールの設定が必要

### ツールチェーンのバージョン

`gradle/libs.versions.toml` で管理している。M0 の雛形は Google Maven を参照できない環境で書いたため、
確実に存在するバージョン（AGP 8.13 / Kotlin 2.2.21 / Compose BOM 2025.06）に固定している。
Android Studio の AGP Upgrade Assistant が更新を提案してきたら、適用して問題ない。

## モジュール構成

| モジュール | 種別 | 内容 |
|---|---|---|
| `:app` | Android app | 画面（写真一覧、診断）、接続管理、サムネイル取得（Coil）、USB 接続時の自動起動 |
| `:core:ptp` | Kotlin/JVM | `PtpClient` インターフェース、PTP コード表、ダンプ形式、`FakePtpClient`、`DiagnosticsRunner` |
| `:core:ptp-android` | Android library | `android.mtp.MtpDevice` を使った `FrameworkPtpClient`、USB 権限・接続（`UsbCameraConnector`） |
| `:core:camera` | Kotlin/JVM | `CameraProfile`、`ProfileRegistry`、各機種プロファイル、`Shot` へのグルーピング、写真一覧 `CameraCatalog` と ObjectInfo キャッシュ |
| `:core:geotag` | Kotlin/JVM | GPX の読み込み、撮影時刻（EXIF → UTC）、トラックとの突き合わせ、時計ずれの推定 |

`:core:ptp` と `:core:camera` は Android に依存しないので、JVM のユニットテストだけで検証できる。

```sh
./gradlew test                 # 全ユニットテスト
./gradlew :app:assembleDebug   # デバッグ APK
```

## エミュレータで動かす（デモモード）

エミュレータには USB カメラを繋げないので、アプリ同梱のダンプ
（`app/src/main/assets/demo/x100vi-demo.json`）を `FakePtpClient` で再生する「デモモード」を使う。

1. Android Studio の Device Manager で AVD を作る（Pixel 系、API 35 か 36）
2. `app` を Run
3. 「デモモード」→「診断を実行」
4. 診断結果・GetThumb サンプル・JSON の共有/保存まで一通り動く

デモデータは架空の値。作り直す場合は ImageMagick を入れたうえで次を実行する。

```sh
python3 tools/generate_demo_dump.py
```

実機のダンプが取れたら、それをデモデータの代わりに使ってもよい（形式は同じ）。

## 実機で動かす

### APK を入れる

次のどれかでインストールする。

- **Android Studio から Run**（下記のワイヤレスデバッグで接続しておく）
- **コマンドライン**: `./gradlew :app:installDebug`
- **CI の成果物**: GitHub Actions の「Android CI」の実行結果から `app-debug` をダウンロードして端末で開く
  - CI でビルドした APK は実行ごとに署名鍵が変わるため、前のバージョンが入っているとそのまま上書きできない。先にアンインストールすること

### ワイヤレスデバッグを使う

スマートフォンの USB-C 端子はカメラとの接続で埋まるため、ADB は Wi-Fi で繋ぐ（Android 11 以降）。

1. 端末の「開発者向けオプション」→「ワイヤレスデバッグ」をオンにする
2. 「ペア設定コードによるデバイスのペア設定」を開き、表示された IP:ポートとコードで
   ```sh
   adb pair <IP>:<ペア設定用ポート>
   adb connect <IP>:<ワイヤレスデバッグ画面のポート>
   ```
   Android Studio の「Pair Devices Using Wi-Fi」からでもよい
3. ログを見るときは `adb logcat -s FujiPtp`

### X100VI での診断手順（M0 のゴール）

1. カメラの MENU →「ネットワーク/USB設定」→「PC接続モード」を「USBカードリーダー」にする
   - これで認識されなければ、他のモードも試して、どれで認識されたかを記録する
2. 診断中に電源が切れないよう、カメラのオートパワーオフを長めにしておく
3. USB-C ケーブル（両端 USB-C、OTG 対応）でスマートフォンとカメラを繋ぎ、カメラの電源を入れる
4. 「Fuji PTP を開きますか？」のダイアログが出たら開く。出なければアプリを起動して「USB カメラに接続」を押す
5. 「診断を実行」を押す（数百ファイルの ObjectInfo 取得と、各形式 1 ファイルの転送をするので少し時間がかかる）
6. 「共有」か「ファイルに保存」で JSON を書き出し、`fixtures/dumps/` に置く。
   リポジトリは公開なので、先に `python3 tools/sanitize_dump.py <入力> <出力>` でシリアル番号とサムネイルを取り除くこと

ダンプにはシリアル番号、ファイル名、（オンにした場合は）サムネイル画像が含まれる。

### 診断で確認すること

| 確認したいこと（設計 §11） | 見るところ |
|---|---|
| どの PC 接続モードで PTP として見えるか | 接続できたかどうか（手順 1 で記録） |
| USB PID、`DeviceInfo.model` | 接続カードの「USB VID:PID」とモデル名 |
| RAF / HEIF の ObjectFormat コード | `format-summary` |
| RAF / HEIF / 動画で GetThumb が JPEG を返すか | `thumb-*` |
| GetPartialObject 対応 | `partial-support`、`partial-*` |
| ObjectInfo の CaptureDate | `capture-date` |
| EXIF の OffsetTimeOriginal | `exif` |
| 転送速度 | `download-*` |
| 一覧取得にかかる時間 | `object-info-*`（全件取得時の推定秒数） |
| 全階層の一覧取得ができるか | `list-all-*` / `list-root-*` |

### うまくいかないとき

| 症状 | 考えられる原因 |
|---|---|
| 「PTP カメラが見つからない」 | PC 接続モードが違う／ケーブルが充電専用／端末が USB ホスト（OTG）非対応 |
| 「PTP セッションを開けない」 | 端末の別のアプリ（写真の取り込み、ファイルアプリなど）がカメラを使っている。そのアプリを閉じて繋ぎ直す |
| 診断の途中で失敗が続く | カメラがスリープした／電源が切れた。オートパワーオフの設定を確認 |
