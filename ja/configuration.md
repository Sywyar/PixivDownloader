# 設定リファレンス

PixivDownloader の設定は所有者ごとに分かれています。これらの保存先は互換ではありません。

| 設定 | パス | 所有者 |
| --- | --- | --- |
| ホスト設定とプラグイン有効状態 | `config/config.yaml` | アプリケーションシェル |
| プラグインの業務設定 | `config/plugins/{pluginId}.properties` | そのプラグイン |
| プラグインの認証情報 | `config/credentials/{pluginId}.properties` | そのプラグイン。ホストが暗号化 |

まず GUI の設定ページを使ってください。初回起動時は `config/config.yaml` が生成され、アップグレード時は既存値を上書きせず不足しているホストキーだけが追加されます。手動編集時は UTF-8 を使い、空の値をコメントアウトせず有効な `key: value` として残します。

## ホスト設定

### サービス、デバッグ、ダウンロード

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS サービスポート |
| `debug.enabled` | `false` | デバッグモード |
| `download.root-folder` | `pixiv-download` | ダウンロード作品のルート |
| `download.user-flat-folder` | `false` | 作者ディレクトリを省略する構成 |
| `download.max-concurrent` | `10` | 同時ダウンロード数。追加で最大 100 件をキューに保持し、それを超えると 429 |
| `database.maximum-pool-size` | `28` | SQLite 接続プールの上限 |

`download.root-folder` には作品だけが保存され、設定、データベース、プラグイン状態、キャッシュは別の場所に置かれます。小説、Douyin などの固有設定は各プラグインが所有します。

画像、表紙、埋め込み画像は 1 件 100 MiB、通常の作品 / 小説 1 タスク全体で 1 GiB が上限です。Ugoira は ZIP 100 MiB、500 エントリ / フレーム、展開後 1 件 32 MiB / 合計 200 MiB、1 フレーム 25,000,000 ピクセルまでです。各変換プロセスは最長 10 分、出力 100 MiB までです。超過時は処理を止めて一時ファイルを削除しますが、保存対象の ZIP とフレーム時刻ファイルは保持します。

### 主データベースと既存データの移行

既定の SQLite は独立した database provider プラグインで、`data/pixiv_download.db` と既存の配置規則を使います。PostgreSQL 17 / 18（UTF8）を使う場合は、対応プラグインをマーケットでインストールして完全に再起動し、接続先を設定します。主庫と対応済みのプラグイン私有ストレージは、選択したエンジンを使います。SQLite 私有庫は `data/{pluginId}/plugin.db`、PostgreSQL は owner ごとの schema と制限付きアカウントです。

#### 設定画面で切り替える

Swing / Compose の「設定 → 実行とネットワーク → 主データベース」、またはマーケットのデータベース設定から操作します。

1. 読み込まれた provider を選び、接続情報を入力します。パスワードの空欄は保存済みの値を保持します。削除する場合はクリアを選択して保存します。
2. 接続テストでアドレスとアカウントを確認します。テストは設定保存、データベース作成、移行を行いません。変更後に確認して続行する操作を選び、アプリと全プラグインの移行元・移行先・処理を検査します。
3. 確認一覧を読みます。移行先にデータがあり、安全な置換を provider が提供する場合は、先にバックアップして置換することを表示します。対応していない場合は拒否します。キャンセルすると入力を保持したまま戻り、接続設定は保存しません。
4. 確認すると、このページの他の設定を保存して業務処理を止め、完全に再起動します。新しいプロセスが移行・照合し、全データベースの検証後に接続設定を切り替えて起動を続けます。同じデータベースの接続情報だけを直す場合は、接続適用と再起動を選び、データはコピーしません。

接続詳細には現在と再起動後の接続先を表示します。環境変数や起動引数で上書きしている場合は、その設定を先に修正してください。

移行先の旧データは置換前にバックアップし、移入したデータを検証してから庫ごとに置換します。報告とバックアップは `data/database-migrations/` のタスクディレクトリに保存します。スケジュールの認証情報を含む場合があるため、機密データとして保管してください。作品、参照音声、認証情報ファイルは元の場所に残ります。

失敗時は元の接続設定を保持し、データごとの実際の結果を表示します。他の庫の失敗でコミット済みの庫を自動的には戻しません。「結果不明」は保存できたか未確認であり、ロールバック済みではありません。元データとバックアップを保管し、問題を直して再確認します。再試行では移行元の変化と移行先の一致を検査し、照合できた移行済みデータだけを再利用します。

provider がない、選択中のプラグインが無効・欠落・非互換・読み込み失敗、または接続不能の場合は復旧モードになります。業務画面はマーケットへ移動し、API は `503`、直接の意味的な参照はデータベース利用不可を示した空の結果を返します。書き込みと依存するバックグラウンド処理は停止します。別エンジンや空の SQLite へ自動で切り替えることはありません。プラグインや接続を直して確認・再起動してください。元の接続を解析できない場合は、元データを移行せず接続だけを修正することを確認一覧に表示します。

#### PostgreSQL の準備

専用のデータベースと主庫 schema を作り、アプリのアカウントにテーブル、インデックス、シーケンス、関数、トリガーの作成と読み書きを許可します。私有庫を使う場合は、独立したログインロールと schema を作るため、`CREATEROLE` とデータベースの `CREATE` 権限も必要です。主庫や他 owner のデータを `PUBLIC` に公開すると、隔離検査で私有庫への接続を拒否します。

`currentSchema` は小文字、数字、アンダースコアからなる 1 つの名前だけを指定できます。先頭は数字以外、最大 63 文字で、省略時は `public` です。リモート接続は `sslmode=verify-full` を推奨し、独自 CA には `sslrootcert` を指定します。JDBC は指定サーバーへ直接接続し、Pixiv の HTTP プロキシを使いません。

以下は扁平なキーの例です。既存データの移行は設定画面を使ってください。ファイルの直接編集は次の接続を変えるだけで、履歴をコピーしません。パスワードは専用項目または `DATABASE_PASSWORD` に設定し、JDBC URL に含めないでください。

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

#### 停止中に CLI で移行する

独自に停止時間や報告確認を管理したい場合の入口です。CLI はアプリの接続設定を自動で変えず、データのある移行先のバックアップ・置換も行いません。

移行元・先の provider をインストールし、元データを使うアプリ、プラグイン、オフライン書き込みツールをすべて停止します。作品、私有庫、参照音声、設定、認証情報を保持してください。有効な設定を変えず、元と先の接続を `source.yaml` と `target.yaml` に保存します。配布物の展開先で、`<boot.jar>` を実際の boot JAR 名に置き換えて実行します。

`source.yaml` と `target.yaml` の両方で `database.provider` を明示してください。SQLite の移行元には `database.provider: database-sqlite` と `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"` を指定できます。両ファイルを保管し、移行後も `source.yaml` を上書きしないでください。私有データベースの検査で元の接続先を確認するために使います。

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

ツールはインスタンスロックを取得し、移行元 provider が整合したバックアップと構造・データを用意し、移行先 provider が空の移行先へ読み込み・照合します。SQLite のバックアップはコミット済み WAL を含み、旧構造は作業用コピー上で更新します。SQLite と PostgreSQL は双方向の移行に対応し、ID、ミリ秒時刻、NULL、論理削除、メタデータ、メディア、ファイル参照を保持します。テーブルごとの件数、ダイジェスト、生成キーのシーケンスを検査します。移行先が空でない、テーブルの owner 宣言がない、型が非互換の場合は停止し、未知のテーブルを飛ばしたり移行先を消去したりしません。

小説の再生成可能な SQLite オブジェクトには明示的な指定が必要です。実在する場合に `--rebuild-objects novels_fts,novel_soft_delete_cleanup` を追加し、一覧には存在するものだけを書きます。PostgreSQL では起動時に小説プラグインの宣言に従って削除トリガーを復元し、本文検索は移行した本文を直接使います。他の補助オブジェクトも provider が再生成できるか確認してください。通常の履歴テーブルをこの指定で除外することはできません。

インストール済みなら無効なプラグインも移行宣言を提供します。データを残してプラグインを削除する前に、同じ入口の `--export-schema schema.json` で宣言を保存できます。移行時は `--retained-schema schema.json` で欠落 owner を補い、現在の SDK と一致するファイルを使います。私有ストレージの構造更新に必要なプラグインはインストールしておく必要があります。宣言がなければプラグインを復元するか確認済みの宣言を補い、履歴テーブルを削除しないでください。

互換の私有 schema を宣言した owner のデータも移行します。未知の owner や宣言不足はコピー前に拒否します。主庫と私有庫は別々にコミットし、部分失敗で保存済みの庫を自動で戻しません。接続保存と起動前に私有庫の移行結果と元データの変化を再確認します。独自 JDBC や SQLite 固有 SQL は自動変換されず、作者による対応が必要です。

報告が `DATA_VERIFIED` の場合だけコピーの照合が完了しています。報告、再生成対象、各データの結果を確認してから有効な接続設定を変え、ギャラリー、小説検索、通知、スケジュール、ファイルを確認します。`IN_PROGRESS` と `FAILED_TARGET_NOT_VERIFIED` は成功ではありません。バックアップを保管して移行先のトランザクション結果を確認し、CLI の再試行には新しいバックアップと確認済みの空の移行先を使います。途中再開はありません。切り替え後に新しい書き込みがある場合、旧接続へ戻すだけでは増分を失います。設定画面で再確認して逆方向へ移行するか、停止後に管理者が処理してください。

#### 移行のバックアップと検証の詳細

接続項目と型はデータベースプラグインが宣言します。SQLite には接続項目がなく、PostgreSQL にはアドレス、ユーザー名、パスワードがあります。解析できない元の接続だけを修正する場合も、元の設定のスナップショットを保持し、私有データベースの移行検査を行います。

設定画面で SQLite の移行先を置換する場合、provider は旧データベースをロックし、コミット済み WAL を含む整合したバックアップを保存してから一時データベースへ取り込みます。全データベースの検証後、元の場所で庫ごとにトランザクションを使って内容を置換します。置換のコミット前に失敗した場合は旧内容を保持し、コミット済みの庫は取り消しません。検査後に移行先が変化した場合や未知のテーブルがある場合は拒否します。各タスクの `target-*.backup` は主庫と私有庫の旧内容を個別に保存します。両接続先を使う他のアプリやオフライン書き込みツールを停止してください。`data/database-migrations/` の記録、設定スナップショット、バックアップは起動検査と再試行に必要です。認証情報を含む場合があるため保管してください。

PostgreSQL の移行元は対応済みの構造である必要があります。バックアップはデータ、シーケンス、対応オブジェクト定義を含む形式 1 の論理 XML で、`pg_dump` ファイルではありません。このアーカイブを直接復元する入口はありません。移行元 provider が欠落または無効なら移行を停止し、他のドライバーで代用しません。SDK の移行契約を満たす provider は、自身のエクスポートとインポートを実装し、ホストへの専用分岐の追加を必要としません。

移行はバイナリも保持し、型付き SHA-256 ダイジェストと生成キーの過去の最大値を照合し、参照に関する警告を報告します。孤立レコードは削除しません。中立なストレージインターフェースに渡す文字列からは provider が NUL（U+0000）を除去します。改行、タブ、その他の有効な Unicode、空文字列、NULL、バイナリは保持し、不正なエンコーディングは拒否します。既存テキストの移行では一時作業コピーだけを処理して移行先の実データを照合し、元データと原本のバックアップは変更しません。一意キーの衝突や制約違反が起きれば失敗とし、レコードを自動で統合・削除しません。

PostgreSQL の移行元では `--rebuild-objects` に実在するトリガーと関数名を指定し、所有プラグインが移行先で再作成できることを確認してください。provider が私有庫の探索、隔離、物理マッピング、SQL 方言、移行を担当し、業務プラグインは構造と業務変換を宣言して中立な契約で読み書きします。第三者プラグインの任意の SQL を自動変換する機能はありません。

全データベースのコピーと検証を終えてから、個別のコミットを開始します。切り替え時と起動時には、信頼済みの移行元 provider を使って私有庫の変更情報と移行先の正確な識別情報を再確認します。元の設定、元データ、移行先が変われば切り替えを拒否します。報告と私有庫の移行記録は `formatVersion: 1` を使い、交換モデルと記録は未知の形式を拒否します。

報告の `stores` は庫ごとに `COMMITTED`、`ROLLED_BACK`、`UNKNOWN` を記録します。中止や失敗時は、コミットをまだ試みていないセッションを中止します。部分コミットを全体のロールバックとして報告してはいけません。コミット中の切断、強制終了、報告の保存失敗では `COMMITTING`、`UNKNOWN`、古い進捗が残る場合があるため、移行先を手動で確認してください。失敗後に残る空のデータベースファイル、私有 schema、ロールは自動削除しません。

`DATA_VERIFIED` には、全コミット、移行記録の保存、リソース解放の完了も必要です。切り替え前に参照警告を確認し、元データと設定を保管してください。移行先への新規書き込み前なら停止して元の設定に戻せますが、書き込み後に直接戻すと増分を失います。

### 画像と Ugoira の保存

デスクトップの「設定 → ダウンロード」で既定の形式、品質、WebP 可逆圧縮、最大辺を設定し、保存後にバックエンドを再起動します。形式のドロップダウンは複数選択でき、最低 1 項目が必要です。

- 画像：元の形式（既定）、PNG、JPG、WebP。元の形式は解除できます。選択した出力がすべて成功してから原画像を削除し、失敗・キャンセル時は保持します。元と同じ形式は既存ファイルを再利用します。
- Ugoira：WebP（既定）、GIF、APNG、MP4、元の ZIP。MP4 には現在の FFmpeg の H.264 エンコーダーが必要です。ZIP 保存時はオフライン再変換用のフレーム時刻も保存します。
- 品質は既定 90（1–100）、WebP 可逆圧縮は既定で無効、最大辺は既定 0（サイズ維持）または 1–16,383。これらは新しくエンコードするコピーに適用し、再利用する原画像には適用しません。

両方の Web ダウンロード画面でも、今回のダウンロードと新規スケジュールの形式を選択できます。既定値は `config/plugins/download-workbench.properties` の `download-workbench.media.image-formats`、`ugoira-formats`、`quality`、`webp-lossless`、`maximum-edge` に保存します。全キーに同じプレフィックスを付けます。

FFmpeg の共有同時実行数は既定 2、デスクトップのダウンロード設定で 1–8 に変更でき、バックエンド再起動後に適用します。ホストキーは `config/config.yaml` の `ffmpeg.max-concurrent` です。変換とメディア解析で枠を共有します。

既存作品は Compose デスクトップの「ツール → FFmpeg とツール → ダウンロード済みメディアの処理」で対象形式を選び、不足項目を検出します。作品 ID の入力は不要です。結果を確認して開始すると、不足する形式とサムネイルだけを追加し、既存ファイルを保持します。戻っても処理は続きます。Swing でもツールページから同じ検出・処理ができます。Ugoira の追加変換には保存済み ZIP とフレーム時間情報が必要です。FFmpeg の管理で実際のコーデック対応を確認でき、MP4 には H.264 エンコーダーが必要です。

1 回に対象作品 500 件、候補ファイル 10,000 件まで処理でき、同時に実行する処理は 1 件です。上限に達したら処理後に再検出します。検出だけではファイルを生成しません。選択を変更した場合は再検出が必要で、ダウンロード設定の変更だけで既存作品の処理は始まりません。

ギャラリーは保持された原画像、WebP、PNG、JPG の順で実在するファイルを選び、MP4 は動画プレーヤーで表示します。

### プラグインマーケット

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | マーケットのマスター切り替え |
| `plugin-catalog.official-repository-enabled` | `true` | 組み込み公式リポジトリ |
| `plugin-catalog.connect-timeout-ms` | `15000` | 接続タイムアウト |
| `plugin-catalog.read-timeout-ms` | `60000` | 読み取りタイムアウト |
| `plugin-catalog.max-manifest-bytes` | `1048576` | マニフェストサイズ上限 |
| `plugin-catalog.max-package-bytes` | `104857600` | パッケージサイズ上限 |
| `plugin-catalog.repositories` | 空リスト | カスタムリポジトリ |

公式リポジトリ URL と信頼ルートは組み込まれています。カスタムリポジトリは HTTPS マニフェストと Ed25519 公開鍵を自分で宣言し、公式の信頼ルートを継承しません。手動例：

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

リポジトリ ID は一意で、`official` と `configured` は使えません。`direct-strict` は直接 HTTPS のみ、`proxy-trusted` はアプリプロキシと組み込み信頼ホストへの最大 5 回のリダイレクト、`custom` はエントリのフラグに従います。個別エントリではタイムアウトとサイズ上限を上書きできます。

### 外向きプロキシ

| キー | 既定値 |
| --- | --- |
| `proxy.enabled` | `true` |
| `proxy.host` | `127.0.0.1` |
| `proxy.port` | `7890` |

プロキシ対応が必要なプラグインは、安定した HTTP / WebSocket SDK または `core-api` のプロキシポートを使います。ホストの `ProxyConfig` 実装へ依存しないでください。

#### バックエンド設定のプロキシ経由で Web 版 Pixiv にアクセス（システムプロキシ不要）

バックエンドは設定したプロキシ（デフォルトは `127.0.0.1:7890`）経由で Pixiv にアクセスし、システムプロキシには依存しません。システムプロキシを有効にせず、ブラウザーで `pixiv.net` を直接開きたい場合（Userscript を使う場合など）は、内蔵のプロキシ自動設定（PAC）を利用できます。

OS またはブラウザーの「自動プロキシ設定スクリプト（PAC）URL」を `http://localhost:6999/proxy.pac` に設定してください（設定したポートに合わせてください。HTTPS を有効にすると `https://<domain>:<port>/proxy.pac` になります）。その後、Pixiv 関連ドメインだけが同じバックエンド設定のプロキシを経由し、それ以外は直接接続します。このエンドポイントはローカル専用です。ホットリロードを含むプロキシ変更も自動的に反映されるため、システムプロキシを何度も切り替える必要はありません。

Firefox の設定は `about:preferences#general`、Windows のプロキシ設定は `ms-settings:network-proxy` から開けます。

### マルチモードのクォータと制限

| キー | 既定値 |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

`multi-mode.post-download-mode` は `pack-and-delete`、`never-delete`、`timed-delete` のいずれかです。ゲストには `guest-invite.request-limit-minute=300`、`guest-invite.static-resource-request-limit-minute=1200`、`guest-invite.tts-request-limit-minute=30`、`setup.login-rate-limit-minute=10` が適用されます。

### メンテナンス時間

`maintenance.enabled` の既定値は `true` です。月曜は `maintenance.monday.enabled=true`、他の曜日は `false`、各曜日の時刻は既定で `10:00` です。

### HTTPS とリバースプロキシ

| キー | 既定値 |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | 空 |
| `server.ssl.certificate-private-key` | 空 |
| `server.ssl.key-store-type` | `JKS` |
| `server.trusted-proxy-cidrs` | 空 |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

PEM の証明書 / 秘密鍵、または JKS キーストアを指定します。秘密鍵とキーストアパスワードをコミットしないでください。

`server.trusted-proxy-cidrs` には、実際にバックエンドへ接続するプロキシ出口の IPv4 / IPv6 CIDR だけをカンマ区切りで記載します。

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

`0.0.0.0/0` や `::/0` を信頼しないでください。空の場合は直接モードとなり、転送ヘッダーを拒否します。信頼プロキシは毎回、`Forwarded` の `for` / `proto` / `host`、または `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host`（任意で Port）の完全な一組を送る必要があります。欠落、混在、形式不正、信頼されない送信元では HTTP 400 になります。

### 言語とデスクトップ UI

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `app.language` | 空 | システムに従うか、対応言語コードを指定 |
| `app.theme` | `system` | GUI テーマ ID |
| `app.config-menu-expand-all` | `false` | Swing の設定メニューの展開だけを制御。Compose はこの項目を表示せず、保存済みの値も変更しません |
| `app.gui-provider` | 空 | デスクトップ GUI provider ID。選択肢は有効な provider プラグインから取得 |

どちらのデスクトップ UI でも `system`、`light`、`dark` を選べます。追加テーマは、選択した UI が提供します。GUI provider の選択肢は有効な `DesktopUiProvider` から取得します。配布物には Compose と Swing が含まれ、初回起動で未指定の場合は Compose を選択します。UI の切り替えにはアプリケーション全体の再起動が必要です。

言語とテーマはすぐにプレビューできます。選択を保持するには、設定ページ共通の保存操作を使ってください。言語設定は画面表示に適用され、ログは英語で出力されます。

### 更新とスケジュールホスト

更新は `update.enabled=true`、`update.auto-check=true` が既定です。`update.manifest-url` と `update.nightly-manifest-url` でマニフェストを指定できます。

スケジュール関連の主な設定は `schedule.enabled=true`、`schedule.tick-interval-ms=60000`、`schedule.max-tasks=100`、`schedule.inbox-check-every=500`、`schedule.auth-failure-circuit-breaker=5`、`schedule.pending-max-attempts=5`、`schedule.overuse-defer-default-minutes=60` です。ダウンロード元や認証情報はプラグインが所有します。

### プラグインの有効状態

ホストは `plugins.{pluginId}.enabled` を所有します。

```yaml
plugins.douyin.enabled: true
```

必須プラグインは無効化できません。変更が即時反映されるかはプラグインのライフサイクルポリシーによります。詳しくは [プラグイン管理](/ja/plugin-management) を参照してください。

## プラグイン業務設定

各プラグインは UTF-8 の Java properties 形式で `config/plugins/{pluginId}.properties` だけを書き込みます。

```properties
example.timeout-ms=15000
example.output-format=json
```

ホストキー、`plugins.*.enabled`、認証情報らしいキーの上書きは拒否されます。プラグインは子 Spring コンテキストの `Environment`、`@Value`、`@ConfigurationProperties` から値を読み取り、ファイルを直接読まないでください。`GuiConfigContribution` は field、group、layout、action、preset を含むプラグイン設定 section 全体の事実源です。アプリケーションシェルがコア設定と統合して保存し、Swing / Compose は同じ文書を描画するだけです。保存後、ホストは即時反映、バックエンド再起動、プロセス再起動のどれが必要かを示します。

## プラグイン認証情報

パスワード、Cookie、トークン、API キー、秘密、Webhook キーは `config/credentials/{pluginId}.properties` に置きます。暗号化、権限、移行、所有者単位の注入はホストが担当します。`config.yaml` や `config/plugins/*.properties` に認証情報を置かず、プラグインから認証情報ファイルを読み取り・復号しないでください。

暗号化バックアップを別環境で復元するには、元の認証情報マスターキーも必要です。

## 現在の契約を確認する

1. GUI の設定ページを開く
2. ホストの既定値は現在の `DefaultConfigTemplate` を確認する
3. プラグイン項目は `GuiConfigContribution`、`@ConfigurationProperties`、設定サービスを確認する
4. 旧来の `mail.*`、`push.*`、`notification.*`、`download.novel-*` を `config.yaml` にコピーしない
