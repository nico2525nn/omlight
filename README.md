# OMLight

OI.Share 代替の軽量カメラアプリ。公開 OPC プロトコルのクリーンルーム実装。

カメラの Wi-Fi につないで、写真の一覧・保存・撮影ができる。余計な機能なし。

## 使い方

1. カメラの Wi-Fi に接続 (カメラの液晶に表示される SSID)
2. アプリを開く → 自動で接続
3. 写真タブで選んで保存、撮影タブでリモート撮影

[リリース](https://github.com/nico2525nn/omlight/releases)から APK を入手。

## 特徴

- カメラ通信だけカメラ Wi-Fi に流し、普段の通信を邪魔しない
- バックグラウンド転送 (中断しても後で再開できる)
- 転送の完全性保証 (サイズ・形式検証、壊れたファイルは保存しない)
- RAW (ORF) 含め原本のまま保存

## 対応機種

- E-M5 Mark II: 実機確認済み
- その他 OM-D / PEN / OM 系: 仕組み上は対応 (未検証、報告歓迎)

## 開発

```sh
cd omlight
./gradlew :app:assembleDebug          # ビルド
./gradlew :app:testDebugUnitTest      # テスト
python3 mockcam.py --port 8080       # モックカメラ (エミュレータ用)
```

## カメラ調査 (Termux OK)

```sh
python3 camprobe.py                  # 読み取り専用の全取得
python3 camprobe.py --shoot          # テスト撮影あり
python3 camprobe.py --live 30        # ライブビュー30枚
python3 camprobe.py --deep           # カメラ設定も取得
```

カメラの Wi-Fi につないで実行。結果は `camprobe-日時/` に保存。
stdlib のみ、pip 不要。

詳細は [`OMLight_OPC_Protocol_Guide_JA.md`](OMLight_OPC_Protocol_Guide_JA.md)。

## 意図的に入れない CGI

純正 APK の全 68 CGI を調査済み。以下 10 種は危険・無意味のため移植しない:

| CGI | 理由 |
|---|---|
| `fwup_*` (7 種) | ファームウェア更新。失敗=文鎮化。純正に任せる |
| `send_agpsassistdata`, `update_agpsassistdata` | A-GPS 書き込み。効果不明・副作用あり |
| `clear_cameralogdata` | カメラ内ログ消去。消す理由がない |

読み取り系 (`get_*`, `check_*`) と撮影・転送・設定系は全移植済み。
`exec_reboot` / `exec_pwoff` / `clear_resvflg` は確認 UI 付きで実装。
