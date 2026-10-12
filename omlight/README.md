# OMLight — 軽量 OM/オリンパス カメラアプリ

OI.Share の代替を目指す、公開 OPC プロトコルベースのクリーンルーム実装。
Material 3 Expressive の最小 UI + バックグラウンド転送特化。

## 設計 (ストレス削減の要点)

- **他通信を遮断しない**: カメラ AP はネットなし Wi-Fi。`Network.getSocketFactory()`
  でカメラ宛ソケットだけ AP にバインドし、プロセス全体のデフォルト
  ルートには触らない。他アプリ・自アプリの他通信はモバイル回線等を継続。
  VPN 有効時は警告を表示。
- **バックグラウンド DL**: `FOREGROUND_SERVICE_DATA_SYNC` のフォアグラウンド
  サービス + 通知プログレス。ホームに戻っても完走。
  保存先は `Pictures/OMLight` / `Movies/OMLight` (MediaStore, 権限不要)。
- **転送の完全性**: 一時ファイル staging → マジック検証 → サイズ検証 →
  MediaStore 公開。HTTP Range 再開、重複スキップ、転送履歴、
  一時停止/再開、キュー永続化、失敗再試行。
- **余計なものなし**: 写真グリッド / 撮影 / 録画 / ライブビューのみ。
  アカウント・クラウド・編集・広告なし。
- **多機種対応**: `get_commandlist.cgi` で機能検出、未対応は出さない。
  方言プローブで新旧吸収。

## プロトコル

- Olympus OPC Communication Protocol 1.0a (2015, 公開仕様)
- 実機方言は OSS `olympus-wifi` の知見を参照
  (`switch_cammode` / `shutter` / `get_commandlist` / UA フォールバック等)
- APK のデコンパイル結果は一切使用していない

## 構成

```
app/src/main/java/com/omlight/
  cam/
    OpcModels.kt      OPC 定数・モデル
    OpcParse.kt       imglist/XML パーサ (単体テストあり)
    OpcTransport.kt   OkHttp + Network バインド転送層 (Range 対応)
    OpcClient.kt      接続・撮影・ブラウズ・プロパティ・ズーム・管理
    CameraNetwork.kt  AP 探索・要求・切断監視・VPN 検出
    EventReceiver.kt  push event 受信
    LiveviewReceiver.kt RTP/JPEG 組立
    WifiQr.kt         WIFI: QR ペイロード解析 (単体テストあり)
  dl/
    DownloadService.kt フォアグラウンド DL (検証・再開・一時停止)
    FileSniff.kt      マジックバイト検証 (単体テストあり)
    TransferStore.kt  履歴・キュー永続化
  ui/
    CamViewModel.kt   画面状態 (イベント・再接続・絞込)
    Screens.kt        Compose 画面 (詳細・設定・バナー)
    Theme.kt          M3 Expressive テーマ
    ThumbStore.kt     サムネイルディスクキャッシュ
mockcam.py            エミュレータ検証用モックカメラ (Range・event 対応)
rtpcheck.py           RTP 組立ロジックのホスト側検証
evtcheck.py           イベント・管理系のホスト側検証
```

## ビルド・検証

```sh
export ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
GRADLE=~/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle
$GRADLE :app:assembleDebug --offline        # キャッシュ済み依存のみで可

# モック + エミュレータ (debug は 10.0.2.2:8080 を見る)
python3 mockcam.py --port 8080 --seed 12
$GRADLE :app:testDebugUnitTest --offline --tests "com.omlight.cam.*"
python3 rtpcheck.py
```

release ビルド (`CAM_HOST=192.168.0.10:80`) は実機カメラ AP 用。

## 未検証・既知の制限

- ライブビューはエミュレータの UDP 制限で未検証 (RTP 組立はホストで検証済み)。
  実機確認が必要。
- `get_commandlist` のない古い機種は方言プローブで吸収するが、実機確認が必要。
- BLE ペアリング・QR スキャナ内蔵は未実装 (軽量化のため対象外)。
  システムカメラで QR を読んで接続する動線を想定。
- 動画の機内再生は未実装 (転送のみ。再生はギャラリーアプリに任せる)。
