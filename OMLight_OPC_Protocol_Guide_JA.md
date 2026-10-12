# OMLight向け Olympus OPC / OM-D Wi-Fi通信プロトコル調査ノート

**調査日:** 2026-10-12  
**対象:** OMLight・OLYMPUS OM-D E-M5 Mark II  
**目的:** 公開OPC 1.0 alpha仕様とOI.Share系カメラの実通信を区別し、実装・テストに必要な情報を整理する。

> **最重要:** 本書はオリンパスの著作物である仕様書の転載・完全翻訳ではなく、一次資料と実機解析資料に基づく**独立した日本語の技術要約**。公開仕様書と、他のOM-Dで観測された非公式情報を混同しない。E-M5 Mark IIそのものの実際のコマンド一覧・挙動は、実機から採取するまで**未確認**とする。

## 0. 読むべき原典

| 資料 | 区分 | 用途 |
|---|---|---|
| [**Specification of Communication Protocol for Open Platform Camera 1.0 alpha (PDF)**](https://raw.githubusercontent.com/ccrome/olympus-omd-remote-control/master/OPC_Communication_Protocol_EN_1.0a/OPC_Communication_Protocol_EN_1.0a.pdf) | **オリンパス作成の公式仕様書の第三者保存コピー**（2015） | AIR A01向けOPCの正式な説明。送受信形式・CGI・イベントなどのベースライン |
| [OLYMPUS OM-D Firmware & WLAN Hacks](https://0l.de/projects/omd/) | **非公式実機解析**（E-M10 FW 1.2） | OI.Share系のHTTP実例・ライブビュー・`private`モード |
| [E-M10の実測 `get_commandlist.cgi`](https://0l.de/projects/omd/cgi/get_commandlist/) | 非公式実機応答サンプル | `<oishare>`、`<cmd1>`、`<param1>`構造と機種依存CGI |
| [E-M10の実測 `get_camprop.cgi`](https://0l.de/projects/omd/cgi/get_camprop/) | 非公式実機応答サンプル | `<desclist><desc>...` のパース検証 |
| [olympuswifi Python API](https://olympus-wifi.readthedocs.io/en/stable/api.html)・[コマンドの使い方](https://olympus-wifi.readthedocs.io/en/stable/usage.html) | OSS実装とドキュメント | 機種別capabilityツリーの読み方、パラメータ検証、画像転送 |
| [Open Oly ImageShare](https://www2.rigacci.org/wiki/doku.php/doc/appunti/hardware/olympus/open-oly-imageshare) | OSS関連資料・実機調査 | OI.Share代替の実装と、機種間の違い |
| [E-M5 Mark II 公式取扱説明書（英語）](https://learnandsupport.getolympus.com/sites/default/files/media/files/2018/03/E-M5Mk2_ENU_03_FW4.pdf) | **公式機種マニュアル** | Private / One-TimeのWi-Fi接続方式と機能制限 |
| [OMLightソース](https://github.com/nico2525nn/omlight) | 現行実装 | プロトコル仕様に対する差分を確認 |

公開OPCと、工場制御用途の**OPC UA / OPC Foundation**は無関係。同じ「OPC」の検索結果に惑わされないこと。

## 1. 仕様の適用範囲：公開OPC vs OI.Share private

- **公開OPC**: Olympus AIR A01（オープンプラットフォームカメラ）向けに仕様公開されたプロトコル。2015年の仕様書の接続例は `get_connectmode.cgi` に対して `<connectmode>OPC</connectmode>` が返る前提。
- **OI.Share系OM-D**: 同じ `192.168.0.10` のHTTP CGIを大量に共有する一方、`<connectmode>private</connectmode>` や `switch_cammode.cgi`、`exec_shutter.cgi` など独自のコマンド・モード・挙動が見つかっている。
- **E-M5 Mark II**: メーカー取説はPrivateとOne-Timeの2方式を規定している。両方式で画像の可視範囲等が違う。ただし、E-M5 Mark IIの *wire-level* CGI全仕様がメーカーから別途公開されたことは今回確認できていない。

| 観点 | 公開OPC 1.0a | OM-D OI.Share系の実例 | E-M5 Mark II |
|---|---|---|---|
| 想定機種 | AIR A01 | E-M10など | 実機で確認が必要 |
| 接続モード文字列 | `OPC` | `private`（E-M10の実測） | `private`の可能性が高いが応答未採取 |
| User-Agent | `OlympusCameraKit` | `OI.Share v2` を用いるOSSがある | 実機で比較 |
| モード切替 | `switch_cameramode.cgi` | `switch_cammode.cgi` | `get_commandlist`で確認 |
| 撮影 | `exec_takemotion`系 | `exec_takemotion` / `exec_shutter`系など | コマンド・モード検出が必須 |
| イベント | `start_pushevent.cgi` など | 実装されない機種・モードがある | 実機で確認 |
| ライブビュー | UDP/RTP系JPEG | 類似形式、独自拡張あり | 実機パケットで確認 |

**設計上の結論:** 「OPCモード以外は接続拒否」ではなく、`OPC`と`private`を別のdialect/profileとして認識し、capabilityを実機から問い合わせること。`private`ならすべての公式コマンドが動く、とも仮定しない。

## 2. 通信の基本

- カメラのWi-Fiアクセスポイントに接続。多くの機種でカメラアドレスは `192.168.0.10`、HTTP TCP/80。
- 制御は通常 `http://192.168.0.10/<command>.cgi?key=value` へのGET、設定変更の一部はPOST。
- 公開仕様の要求ヘッダーでは `Host: 192.168.0.10` と `User-Agent: OlympusCameraKit` が明記されている。
- OI.Share系では `User-Agent: OI.Share v2` の使用実例がある。
- XMLを返す応答もあれば、ボディのないHTTP 200、JPEG、DCFリスト形式のテキスト、バイナリファイルの応答もある。
- **HTTP 200だけでアプリケーションレベルの成功とは限らない**。XMLの `<result>`, `<errorcode>`, `<errormsg>` 等も解析する。
- CGIの有無、HTTPメソッド、許容引数はカメラによって変化する。個別に固定しない。

簡易観測（安全な読み取りのみ。カメラを接続して実行すること）:

```bash
CAM=http://192.168.0.10
curl --max-time 5 -sv -H 'User-Agent: OI.Share v2' "$CAM/get_connectmode.cgi"
curl --max-time 5 -sS -H 'User-Agent: OI.Share v2' "$CAM/get_caminfo.cgi"
curl --max-time 5 -sS -H 'User-Agent: OI.Share v2' "$CAM/get_commandlist.cgi" -o commandlist.xml
```

`-v` のログやXMLには機種識別情報等が含まれる場合がある。公開前に確認する。

### 2.1 最初のハンドシェイク

**公開OPCの基本シーケンス**: ネットワーク接続 → `get_connectmode` → 必要に応じて `switch_commpath` → イベント通知初期化 → カメラモード切替 → 操作。

**OI.Share系での実用的な接続シーケンス**:

1. カメラのAPへの接続を確立し、通信する`Network`を選ぶ。
2. `get_connectmode.cgi` を送信。`OPC` / `private` / 未知値を区別する。
3. `get_caminfo.cgi` で機種・ファームウェアなどの利用可能な識別値を保存。
4. `get_commandlist.cgi` を**XMLツリーのまま**パースし、各コマンドのHTTPメソッドと引数を記録。
5. サポートされるモード切替方式を決定する。
6. 再生・撮影などの必要な機能を、許可された順序で使用する。
7. 必要に応じてイベントやライブビューを開く。未対応なら機能を隠す。
8. 切断時は安全に停止・モード復旧し、取得したリソースを閉じる。

`get_connectmode`が失敗しても、単なるUA不一致、別のWi-Fi、カメラ未準備等の可能性がある。未知応答を盲目的に受け入れるのではなく、接続診断を残す。

## 3. `get_commandlist.cgi` — このプロトコル最大のポイント

**公開マニュアルを読むだけでは、個体の完全なコマンド一覧にはならない。** 対象カメラ自身が公開するコマンド一覧が最も大切。

実機で見られる主要な構造（以下は**説明用の独自短縮例**、実機のダンプ全文ではない）:

```xml
<oishare>
  <version>2.60</version>
  <cgi name="switch_cammode">
    <http_method type="get">
      <cmd1 name="mode">
        <param1 name="rec">
          <cmd2 name="lvqty">
            <param2 name="0320x0240" />
            <param2 name="0640x0480" />
          </cmd2>
        </param1>
        <param1 name="play" />
        <param1 name="shutter" />
      </cmd1>
    </http_method>
  </cgi>
  <cgi name="get_imglist">
    <http_method type="get"><cmd1 name="DIR" /></http_method>
  </cgi>
</oishare>
```

意味:
- `<cgi name="...">`: CGI名。通常URL末尾には `.cgi` を追加。
- `<http_method type="get|post">`: HTTPメソッド。
- `<cmd1 name="mode">`: 第1段階のパラメータ名 `mode`。
- `<param1 name="rec">`: `mode=rec` という許可値。
- `<cmd2 name="lvqty">` 以下: `mode=rec` の場合に限り指定できる従属パラメータ。
- `param1` の `name` がない場合もある。任意のファイルパスなどを指定する引数の表現に使われる。
- `cmd3` / `param3` など、更に深い段階のツリーもある。
- `<support func="web|remote|gps|release">` 等は高レベルの機能情報として扱う。

パーサーの推奨AST:

```text
CapabilityDocument
  protocolVersion
  supportFlags[]
  commands[commandName]
    httpMethod
    argumentTree
      argumentName
      acceptedValues[]
      nestedArgumentsByValue[]
```

ツリーを `Map<String, Set<String>>` の平坦なデータに潰すと、引数の親子関係と「どの値に対して有効か」が失われる。

**OMLightの実コードに対する直接の指摘:** 2026-10-12時点の `OpcParse.commandList()` は `"cmd"` / `"param"` タグを処理しているが、OM-D実例のタグ名は **`cmd1` / `param1` / `cmd2` / `param2` / ...**。この場合CGI名だけが読み込まれ、引数一覧は空になりやすい。モックカメラだけでなく、実機のXMLサンプルをfixtureにしてテストすること。

## 4. コマンド早見表

以下は**公開OPCの考え方とOM-D系で実測されたCGIの索引**。すべてが公開OPC仕様に記載されているとか、E-M5 Mark IIに対応しているという意味ではない。

| コマンド | 主な役割 | 備考・引数例 |
|---|---|---|
| `get_connectmode.cgi` | 接続モードの識別 | `OPC` / `private` 等 |
| `get_commandlist.cgi` | CGIとサポート引数の一覧 | capability検出の中心 |
| `get_caminfo.cgi` | カメラ情報 | `<model>` 等 |
| `switch_commpath.cgi` | 通信経路切替 | 公開OPC側の `path=wifi` 例。非対応機では呼ばない |
| `switch_cameramode.cgi` | 公開OPC系モード切替 | `mode=rec|play|standalone|playmaintenance` 等 |
| `switch_cammode.cgi` | OM-D系モード切替 | `mode=play|rec|shutter`、撮影系では `lvqty` など |
| `get_state.cgi` | 状態・残枚数等 | 機種・モードで利用可否差あり |
| `get_unusedcapacity.cgi` | メディア空き容量 | サイズ値の解釈に注意 |
| `get_dcffilenum.cgi` | DCFファイル情報 | 機種依存 |
| `get_imglist.cgi` | ディレクトリの画像一覧 | `DIR=/DCIM` 等 |
| `get_rsvimglist.cgi` | シェア予約画像一覧 | One-Timeと関係が深い |
| `get_rsvimglist_ext.cgi` | 拡張予約一覧 | 一部機種のみ |
| `clear_resvflg.cgi` | シェア予約フラグの解除 | 状態変更につき慎重に |
| `get_thumbnail.cgi` | サムネイル | `DIR=<ファイルパス>` |
| `get_screennail.cgi` | 表示用画像 | RAWについてもプレビュー取得実例がある |
| `get_resizeimg.cgi` | 縮小画像 | `DIR=...&size=1920` 等、許容値は機種別 |
| `/DCIM/...` | 元ファイルのHTTP取得 | 直接GETする。RAWの転送可否は実機検証 |
| `get_movfileinfo.cgi` | 動画ファイル情報 | 一部機種のみ |
| `get_movplaytime.cgi` | 動画再生時間情報 | 一部機種のみ |
| `exec_takemotion.cgi` | AF・静止画・動画撮影 | `com=assignafframe|starttake|...` |
| `exec_shutter.cgi` | シャッター操作 | OM-D実例には `1stpush` / `1st2ndpush` 等 |
| `exec_shuttermisc.cgi` | シャッター系ライブビュー | 対応モデルで使用 |
| `exec_takemisc.cgi` | ライブビュー・その他の撮影操作 | `startliveview|stopliveview|getrecview|...` |
| `get_camprop.cgi` | 設定値・一覧取得 | `com=get|desc&propname=...` |
| `set_camprop.cgi` | 設定変更 | POST、XMLボディ、`com=set&propname=...` |
| `set_utctimediff.cgi` | カメラ時計関連 | `utctime=...&diff=...` |
| `get_activate.cgi` | 機種固有の状態情報 | 要確認 |
| `get_gpsdivunit.cgi` | GPS関連 | 一部機種のみ |
| `req_attachexifgps.cgi` | GPS/EXIF情報の付与 | POST、影響を理解してから利用 |
| `req_storegpsinfo.cgi` | GPSログ情報 | POST、機種依存 |
| `exec_erase.cgi` | ファイル削除 | 機種・モード依存、非対応機あり |
| `exec_protect.cgi` | ファイル保護 | 同上 |
| `exec_pwoff.cgi` | カメラ電源OFF | 状態変更 |
| `start_pushevent.cgi` | イベント通知開始 | 公開OPCに登場。OM-Dで存在するとは限らない |
| `stop_pushevent.cgi` | イベント通知停止 | 同上 |
| `get_agpsinfo.cgi` ほか | A-GPS等 | TG系などで利用例あり |

**注意:** この表は「発見されたコマンドの索引」であり、各CGIの確定した許容パラメータの列挙ではない。確定値は対象機種の `get_commandlist.cgi` に従う。

## 5. 画像一覧・転送

### 5.1 DCF画像一覧

典型的な要求:

```http
GET /get_imglist.cgi?DIR=/DCIM HTTP/1.1
Host: 192.168.0.10
User-Agent: OI.Share v2
```

テキストの例（形式の説明用）:

```text
VER_100
/DCIM,100OLYMP,0,16,0,0
/DCIM/100OLYMP,P1010042.JPG,7765401,0,22313,18420
```

通常は6フィールド:

`directory, filename, byte_size, attribute, FAT_date, FAT_time`

- `attribute` の下位ビットに読み取り専用・隠し・システム・ディレクトリなどのフラグを含む。
- FAT/DCF日付はビットパック。`year=1980+(date>>9)`、`month=(date>>5)&15`、`day=date&31`。時刻は時・分・2秒単位。
- パスを構成する際に `/` の二重化・欠落を防ぐ。
- `/DCIM` 以下のディレクトリを巡回する場合は再帰の深さ・件数・循環を制限。
- 一覧が空だから写真がないとは限らない。One-Time接続・予約画像のみモードにも注意。

### 5.2 One-Time / Privateの差

E-M5 Mark IIの公式取説:

- **Private**: 自分のスマートフォン向けの接続。OI.Shareの全機能を利用できると記載。
- **One-Time**: 複数スマートフォンへの共有向け。その場で生成された設定を使用し、**転送機能のみ**で、**シェア予約画像だけ**を見る制約がある。

したがって接続モードを調べず、`/DCIM` が常にすべて見えると決めつけない。

### 5.3 プレビューとオリジナルの区別

| 取得対象 | 典型的な方法 | 説明 |
|---|---|---|
| 小サムネイル | `get_thumbnail.cgi?DIR=...` | 一覧の高速表示 |
| 表示用JPEG | `get_screennail.cgi?DIR=...` | RAWからプレビューを取得できることがある |
| 縮小JPEG | `get_resizeimg.cgi?DIR=...&size=...` | 対応サイズはコマンド一覧に従う |
| 元ファイル | `GET /DCIM/100OLYMP/...` | データとして原本を取り出す。ORF対応は実機ごとに検証 |

`get_screennail`からJPEGが得られたことは**ORF原本転送成功の証拠ではない**。

### 5.4 HTTP Rangeによる途中再開

- `Range: bytes=N-` を送った場合の `206 Partial Content`、`Content-Range`、`Content-Length`を検証。
- `200 OK` が返ったらRange非対応とみなし、**先頭から書き直す**。
- `206` でも返却開始位置が要求した位置と一致することを確かめる。
- ダウンロード途中の一時ファイルは、フルパス・撮影識別子・サイズ・時刻などのキーで一意にする。
- マジックバイトとサイズの検査は誤ったHTTPエラーページの保存を防ぐが、同じ種類・サイズの別画像混入は防げない。必要に応じハッシュで検証する。
- 一時ファイルからMediaStoreへのpublishが完了するまで、ジョブを永続キューから消さない。

**Rangeは公開OPC 1.0aの全機種保証事項ではなく、実際のHTTPサーバーが対応するかの確認が必要。**

## 6. 撮影・モード切替

### 6.1 新旧のモード方式

公開OPCの `switch_cameramode.cgi` とOM-Dで使われる `switch_cammode.cgi` を同一視しない。

例:

```text
switch_cameramode.cgi?mode=rec       # AIR側の例
switch_cammode.cgi?mode=rec&lvqty=0640x0480  # OM-D側の例
switch_cammode.cgi?mode=shutter      # シャッターモードのあるOM-D
```

`lvqty` の候補は `get_commandlist` に含まれる場合がある。`0640x0480` など**ゼロ埋めを含む文字列表現**を正しく扱う。

### 6.2 シャッター系CGI

E-M10の実例には:

- `exec_takemotion.cgi`: `assignafframe`, `releaseafframe`, `takeready`, `starttake`, `stoptake` など。
- `exec_shutter.cgi`: `1stpush`, `2ndpush`, `1st2ndpush`, `2nd1strelease`, `2ndrelease`, `1strelease` 等。
- `exec_takemisc.cgi` または `exec_shuttermisc.cgi`: ライブビューなど。

**`newstarttake` はこの実例の `get_commandlist` にはない。** それだけを理由に全機種非対応と断定はできないが、E-M5 Mark IIで固定値として呼ぶのは危険。`get_commandlist` と実機の成功応答を確認して分岐すること。

`exec_shutter`を使う場合も、`switch_cammode?mode=shutter`にした後の内部状態を更新し、撮影終了後に確実に復旧する。

### 6.3 ライブビュー

典型的なフロー:

1. 対応する撮影モードに切り替える。
2. スマホでUDP受信ソケットを開き、割り当てたポートを確認。
3. `exec_takemisc.cgi?com=startliveview&port=<受信ポート>` などを呼ぶ。
4. UDPパケットを受信し、RTP風のヘッダー、拡張情報、JPEG断片を検査・再構築。
5. 停止時に `stopliveview` を発行し、ソケットを閉じる。

E-M10実機解析では先頭12バイトにフレーム区切りや通番などに使う情報があり、フレーム開始・中間・終了に対応するビットが観測されている。これは標準RTPのように扱える部分があるが、**全機種を同じヘッダー拡張の形式だと決め打ちしない**。

パケット欠落・順序入替・先頭の欠損・JPEG終端欠損を検出し、壊れたフレームは捨てる。Androidのアプリ専用Wi-FiではUDPソケットのネットワーク束縛にも注意する。

## 7. Pushイベント

公開OPC 1.0aには、HTTPでイベント通知用ポートを開始し、カメラが別のTCP接続を提供する手順がある。

代表的な流れ:

```text
GET /start_pushevent.cgi?port=65000
(カメラ側でイベントTCPポートが有効化)
(制御アプリがそのTCPポートへ接続)
(イベントデータを受信)
GET /stop_pushevent.cgi
```

イベントは撮影状態、AF完了、モード変更などの通知に利用できる。**ただしE-M10の公開された `get_commandlist` 例に `start_pushevent` は含まれない。** 対応未確認のカメラで必須としない。

`EventReceiver`のTCP socketもカメラ用の `Network` に結びつける。`cancel()`だけでは無期限の同期読み込みを即座に停止できないことがあるので、socket自体を閉じる。

## 8. カメラの設定（camprop）

設定一覧:

```text
GET /get_camprop.cgi?com=desc&propname=desclist
```

観測されている構造:

```xml
<desclist>
  <desc>
    <propname>takemode</propname>
    <attribute>getset</attribute>
    <value>P</value>
    <enum>iAuto P A S M ART</enum>
  </desc>
</desclist>
```

- `get` = 参照可能な項目、`getset` = 設定変更可能な項目として扱う。
- `enum`は機種ごとに異なる。UIに固定値を列挙しない。
- `set_camprop.cgi?com=set&propname=...` は一般にPOSTでXMLを送る。`Content-Type` やXMLのトップレベル形式はカメラに合わせて検証する。
- 同一プロパティでも撮影モードによってread-onlyになりうる。
- UIと転送サービスが独立したセッションで撮影/再生モードを競合変更しないように、カメラ操作を単一セッションで直列化する。

## 9. E-M5 Mark IIに関する確度の分離

**確定（メーカー資料）**

- E-M5 Mark IIにはPrivate / One-Time Wi-Fi接続方式がある。
- One-Timeにはシェア予約画像のみ表示という制約がある。

**確定（他機種の実機ログ・OSS）**

- E-M10は `get_connectmode` で `private` を返す実例がある。
- E-M10のコマンド一覧は `<oishare>` および番号付き`cmd1`/`param1`等の構造である。
- OM-Dのコマンドは公開OPCのAIR A01向けコマンドと異なる場合がある。

**未確認（E-M5 Mark II現物のログが必要）**

- `get_connectmode` の具体的な文字列、ファームウェアバージョン差。
- `get_commandlist`の具体的な全CGI・許容引数。
- `get_imglist`からORFが出るか、元ファイルのHTTP GETでORFを取得できるか。
- `get_screennail`のRAWプレビュー挙動。
- `start_pushevent` と撮影系CGIの対応。
- `Range`とライブビューの実際の通信・エラーパターン。

## 10. OMLightへの要修正チェックリスト（2026-10-12 15:58 JST時点）

対象コード: [OpcClient.kt](https://github.com/nico2525nn/omlight/blob/main/omlight/app/src/main/java/com/omlight/cam/OpcClient.kt), [OpcParse.kt](https://github.com/nico2525nn/omlight/blob/main/omlight/app/src/main/java/com/omlight/cam/OpcParse.kt), [OpcTransport.kt](https://github.com/nico2525nn/omlight/blob/main/omlight/app/src/main/java/com/omlight/cam/OpcTransport.kt)。比較時の最新コミット: [`2bd9097`](https://github.com/nico2525nn/omlight/commit/2bd9097c7cb2d3a376e0fd861af738a17b3da5ff)。

### Blocker A — `private`を拒否

```kotlin
val cm = OpcParse.xmlFlat(t.getString("get_connectmode.cgi"))
if (cm["connectmode"] != "OPC") {
    throw IOException("not an OPC camera ...")
}
```

**さらに重大:** `OpcParse.xmlFlat()` は深さ2の子要素しか記録しないため、公開OPCの実際の単独ルート `<connectmode>OPC</connectmode>` を読み取れず、`cm["connectmode"]` が `null` になる。つまり **公開OPC対応機でも接続失敗しうる**。最初にルート要素の値をパースできるようにし、その後 `OPC` / `private` を区別して異なるコマンド系統へ振り分ける。

### Blocker B — capabilityパーサのタグ名が実機と一致しない

`OpcParse.commandList()`が `"cmd"`, `"param"` で分岐するが、実機は`cmd1`/`param1`/`cmd2`/`param2`/`cmd3`...。その結果、コマンド自体は見えても**引数と許容値を正しく認識できない**。階層を保持するASTでパースする。

### Blocker C — `xmlFlat()`はルート要素の単独値を拾えない

`xmlFlat()`は深さ2の子だけを記録する。公開OPCの `get_connectmode.cgi` が返す単独ルート `<connectmode>OPC</connectmode>`、および単独ルート `<result>OK</result>` という応答を読み取れない。`connect()`でモードを認識できず、`checkOk()`でも成功・失敗を見落とす。ルート要素と `<response>`配下の両方を扱う。

### Blocker D — 撮影コマンドの推測が危険

- `supportsArg()`が引数一覧空の場合も真になるなら `shutter`系を誤選択する。
- `newstarttake`を固定しているが、E-M10の実例は`starttake`。**実機一覧を優先**。
- シャッターモードへ直接遷移した場合に内部 `mode` 状態を追従させる。

### 改善 E — UA / 互換性プローブ

- `OlympusCameraKit`（公開OPC）と`OI.Share v2`（private）を切り替える。
- `connectmode`が`private`なのにHTTP200だからUA再試行をしない、というミスマッチを避ける。
- 最初の診断操作は読み取り専用に限定する。
- 無効な機能をグレーアウトするため、実機capabilityと失敗結果を保存。

### 改善 F — 実機通信と転送の堅牢性

- ファイル名・パス・時刻ごとに一意なステージングファイル。
- ダウンロード中のジョブが復旧キューから消えないようにする。
- Range 206の `Content-Range` とファイル内容を検証。
- Private / One-TimeをUIに明示。
- User-AgentやHTTPエラーの応答ボディを匿名化ログに保存。

## 11. 安全な実機検証手順

実機が接続できる状態で、**削除・書込み・撮影系のコマンドは最初に試さない**。

```bash
#!/usr/bin/env bash
set -euo pipefail
mkdir -p e-m5ii-protocol
cd e-m5ii-protocol
BASE='http://192.168.0.10'
for UA in 'OI.Share v2' 'OlympusCameraKit'; do
  suffix=$(echo "$UA" | tr -c 'a-zA-Z0-9' '_')
  for cmd in get_connectmode get_caminfo get_commandlist; do
    curl --connect-timeout 3 --max-time 10 \
      -H "User-Agent: $UA" \
      -D "${suffix}_${cmd}.headers" \
      -o "${suffix}_${cmd}.body" \
      "$BASE/${cmd}.cgi" || true
  done
done
```

**注意:** カメラによってはUAの切替やネットワーク切断で接続状態が変化することがある。最初は1本ずつ成功を確認するのがよい。

その後:

1. 両UAのstatus、ボディ、`connectmode`を比較。
2. `get_commandlist`を保存し、`.cgi`と`http_method`を抽出。
3. `get_imglist.cgi?DIR=/DCIM` からディレクトリを確認。
4. `get_rsvimglist.cgi` の有無と結果を確認（One-Timeで比較）。
5. JPEGとORFの一覧・プレビュー・オリジナルパスを区別して検証。
6. 機能別コマンドの検証は、バックアップと確認のうえ順序・モード条件を守って行う。

**公開を避けるもの:** Wi-Fi SSID/パスワード、QRコード原文、個人情報を含むExif、位置情報、撮影画像そのもの。

## 12. 推奨するテストセット

| テスト | 期待結果 |
|---|---|
| `get_connectmode` が `OPC` | 公開OPCプロファイルで接続 |
| `get_connectmode` が `private` | OM-D privateプロファイルへ進む。単純拒否しない |
| 不明なconnectmode | 明示的な「未対応/未知」診断。無断で変更系CGIを叩かない |
| E-M10の実機 `get_commandlist` fixture | `switch_cammode`のmodeとlvqtyの依存関係が保持される |
| `get_commandlist`に `start_pushevent`がない | イベントを必須にせず、代替更新を利用 |
| ルートが `<result>NG</result>` | 失敗として検出 |
| `get_camprop` の `desclist` | `getset`属性とenum値を復元 |
| One-Time接続 | シェア予約限定表示であることを説明 |
| HTTP 200だがXML error body | 画像と誤認しない |
| Range未対応200 / 不整合206 | 安全な再取得またはエラー |
| ネットワーク断・プロセス死 | キューと一時ファイルを復旧 |
| USB/Wi-Fiを使わない通常通信 | カメラ専用Networkへ束縛されない |

## 13. 実装方針（最小限の推奨設計）

```text
CameraNetwork
  └── TCP/UDPのNetwork束縛とAP寿命を管理

CameraSession (single owner)
  ├── detectConnectionMode()  -> OPC / PRIVATE / UNKNOWN
  ├── queryCapabilities()     -> nested CapabilityTree
  ├── dialectStrategy         -> AirOpcDialect / OmPrivateDialect
  ├── stateMachine            -> PLAY / REC / SHUTTER / ADMIN / UNKNOWN
  ├── commandQueue            -> カメラへのモード変更を直列化
  └── transport               -> UA・HTTP・応答・リトライポリシー

TransferManager
  ├── durable item states: PENDING / ACTIVE / PUBLISHING / DONE / FAILED
  ├── staged original bytes
  ├── integrity checks + Range verification
  └── MediaStore publication
```

プロトコル層がネットワーク層を直接書き換えないこと。UIと転送サービスは**共有のCameraSession**を扱い、通信モードの切替競合を起こさないこと。

## 14. 最終的な判断

- **「公式仕様と違う」は一部正しい。** 公開OPC 1.0aはAIR A01系であり、OI.Share privateのOM-Dとはコマンド名、認証ヘッダー、接続モード、対応機能が違う。
- ただし、**公開OPC仕様書は土台として有用**。HTTP/CGI、XML、DCFリスト、イベント、ライブビューなどの多くの概念が共通する。
- OMLightに必要なのは、公開仕様への機械的な完全準拠ではなく**対象のE-M5 Mark IIの実機能力への準拠**。
- 公開OPCの単独ルートXMLを読み取れないパーサの不具合も確認できるため、`connectmode` の値を比較する**前段階**で修正が必要。
- 現状の接続チェックと`get_commandlist`パーサの不一致は、アプリが実機で動かない直接的な原因になりうる。新機能開発より優先して直すべき。
- **E-M5 Mark IIの生の `get_commandlist.xml` と `get_connectmode.cgi` が取れれば、この文書の未確認項目を大幅に確定できる。**

---

### 出典について

公式PDFは2015年のOlympus Corporation資料を第三者が保存しているもの。PDFの冒頭には使用許諾条件への言及があり、本書は原文全文を再配布・転載しない。`0l.de` の内容はE-M10についての非公式な実機解析であり、E-M5 Mark IIでの動作保証ではない。GitHub上のOMLightコードは2026-10-12に確認したスナップショットに基づく。
