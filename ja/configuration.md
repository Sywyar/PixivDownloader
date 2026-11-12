# 設定リファレンス

通常の設定は、デスクトップ GUI の「設定」ページで変更・保存し、画面の案内に従って即時適用または再起動してください。手動で編集する場合は、まず設定項目のファイルと所有者を確認します。

| 設定の種類 | パス | 所有者 |
| --- | --- | --- |
| ホスト設定とプラグインの有効・無効 | `config/config.yaml` | アプリケーションのホスト |
| プラグインの業務設定 | `config/plugins/{pluginId}.properties` | 対応するプラグイン |
| プラグインの機密認証情報 | `config/credentials/{pluginId}.properties` | 対応するプラグイン。ホストが暗号化して管理 |

初回起動時にデフォルトの `config/config.yaml` が生成されます。更新時は不足するホストのキーだけを追加し、既存値は上書きしません。手動編集では UTF-8 のフラットな `key: value` 行を使ってください。空値の代わりに設定をコメントアウトしたり、外部プラグインに移されたキー（`mail.*`、`push.*`、`notification.*`、`download.novel-*` など）を `config.yaml` に戻したりしないでください。

## 認証情報の鍵とバックアップ

インストールごとに独立したランダムなマスターキーを生成し、保護した状態で `config/credentials.key` に保存します。Windows では既定で現在のユーザーの DPAPI を使うため、サービスは常に同じアカウントで実行してください。Windows ユーザーや PC を変更する前に、認証情報を含むパスワード付き `.pdbackup` をエクスポートし、移行先でインポートしてください。設定ディレクトリのコピーだけでは復元できません。

Linux、macOS、Docker では、認証情報を初めて保存または更新する前に、別の保護用鍵ファイルが必要です。32 バイトの乱数を標準 Base64 でエンコードした内容を保存し、実行アカウントだけが読み取れるようにして、設定ディレクトリとそのバックアップの外に置いてください。OpenSSL があれば、初回に Bash で次を実行してファイルと起動用の環境変数を用意できます。既存ファイルは上書きしません。

```bash
umask 077
mkdir -p "$HOME/.config/pixivdownload-secrets"
( set -C; openssl rand -base64 32 > "$HOME/.config/pixivdownload-secrets/credentials.key" )
export PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE="$HOME/.config/pixivdownload-secrets/credentials.key"
```

同じターミナルからアプリを起動してください。バックグラウンドサービスではサービスの環境に変数を設定します。Docker ではファイルを `/run/secrets/pixivdownload-credentials` に読み取り専用でマウントし、変数をそのコンテナ内パスに設定します。Java 起動引数 `-Dpixivdownload.credential-key-file=/絶対パス` も使えます。保護用の鍵は別途安全にバックアップし、起動のたびに生成し直さないでください。鍵がない、または一致しない場合、元の認証情報を保持して復号を拒否します。

通常の読み書き時に旧形式を自動更新し、無効なプラグインの認証情報も移行します。旧鍵はこの更新にのみ使い、全ファイルの移行後は旧形式の再投入を拒否します。破損ファイルは修復と再試行のために保持し、他のプラグインは引き続き使えます。既存のオフラインバックアップの保護方式は変わりません。環境間の移行には暗号化した `.pdbackup` を使い、インポート時に移行先の鍵で再暗号化します。

リポジトリの Compose テンプレートは保護用鍵を自動でマウントします。ホストの環境変数にはホスト側のファイルパスを設定し、未設定の場合は起動を拒否します。Linux ホストではコンテナのアカウント UID/GID `10001:10001` だけが読み取れるようにしてください。生成後に `sudo chown 10001:10001 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"` と `sudo chmod 400 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"` を実行する方法があります。ファイルを使う Compose secret はホスト側の権限を引き継ぎます。

## 設定と環境の移行

Swing と Compose の設定ページには、どちらも「設定と環境の移行」があります。読み込むのは保存済みの設定だけです。エクスポート前に変更を保存し、インポート前に未保存の下書きを処理してください。

1. **`.pdbackup` のエクスポート**：デフォルトではホストと登録済みプラグインの設定、プラグインの有効・無効、デスクトップの設定を含みます。認証情報、データベース、保存済み状態、ユーザーリソース（コレクションのアイコン、小説の参照音声）も選択できます。認証情報、状態、データベースのいずれかを含める場合はアーカイブのパスワードが必須で、エクスポート中はバックエンドを一時停止します。
2. **プレビューとインポート**：移行先で互換性のあるプラグインとデータベース接続を準備し、アーカイブを選んでプレビューします。未インストールのプラグインの設定やファイルは書き込まず、アーカイブがプラグインを自動インストールしたり、信頼の承認を復元したりすることはありません。取り込む項目、データベース、ファイル群を選択します。リソースはデータベースと一緒に取り込む必要があります。確認後、まず移行先をバックアップし、プロセス全体の再起動後に移行を実行します。

移行先のデータベース接続、データベース provider の選択、インストール識別情報は保持されます。認証情報は移行先のマスターキーで再暗号化します。管理者認証情報をインポートするとログインアカウントが置き換わり、旧セッションは失効します。スケジュールタスクは無効、手動キューは一時停止で復元します。プログラム本体、プラグインパッケージ、作品ファイル、キャッシュはアーカイブに含まれないため、作品ディレクトリは別途コピーしてください。パスマッピングは宣言されたパス項目とデータベースのプレフィックスだけを更新し、`{0}` は確認済みの絶対ディレクトリに固定されます。タスクのバックアップとデータベースごとのレポートは `data/environment-transfers/<タスク ID>/` に保存され、最新状態は `pending.json` で確認できます。「復旧が必要」となった場合は起動が禁止されます。レポートとバックアップを確認し、ログを削除して強制起動しないでください。

### Windows のインストールディレクトリの移転

Windows のインストールディレクトリを移動した後は、インストーラーを実行して新しい場所を選び、移行を確認してください。レジストリのインストール先、ショートカット、アンインストール情報、既存の現在のユーザーの自動起動設定が更新されます。無効だった自動起動を有効にはしません。修復用バックアップは新ディレクトリの `state/installation-backups/` に保存されます。新しい場所での起動とデータを確認してから旧ディレクトリを整理し、旧ディレクトリのアンインストーラーは実行しないでください。旧 MSI 版の場合は、先にバックアップをエクスポートして MSI をアンインストールし、新版をインストールしてから取り込みます。

## ホスト設定（`config/config.yaml`）

### サービス、ダウンロード、同時実行上限

| キー | デフォルト | 説明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS の待ち受けポート |
| `debug.enabled` | `false` | デバッグモード |
| `download.root-folder` | `pixiv-download` | 作品の保存ルート。ダウンロード作品だけを置く |
| `local-import.presets` | `[]` | 再利用するパステンプレート。保存後にバックエンドを再起動。[使い方と記入例](/ja/local-import) |
| `download.user-flat-folder` | `false` | 作者別一括ダウンロードで作者サブディレクトリを省略するか |
| `download.max-concurrent` | `10` | 同時ダウンロード上限。別途最大 100 作品を待機でき、満杯なら 429 を返す |
| `database.maximum-pool-size` | `28` | 主データベースの接続プール上限。最小 `8` |
| `ffmpeg.max-concurrent` | `2` | FFmpeg の変換とメディア調査で共有する同時実行数。範囲 `1–8`、バックエンド再起動後に反映 |

固定の安全上限は設定で引き上げられません。作品画像・表紙・埋め込み画像は 1 枚 100 MiB、1 回のイラスト・小説ダウンロードタスクは合計 1 GiB です。うごイラ ZIP は 100 MiB、最大 500 フレーム・エントリー、展開後は 1 エントリー 32 MiB、合計 200 MiB、1 フレーム 25,000,000 ピクセルです。1 回の変換は最長 10 分、出力は 100 MiB です。サービスは `Content-Length`、実際のデコード済みストリーム、画像ヘッダーの許可リストを確認し、上限超過時は中断して不完全なファイルを削除します。

### 主データベースとエンジンの切り替え

デフォルトは `database-sqlite`（`data/pixiv_download.db`）です。PostgreSQL 17 / 18（UTF8）に切り替える場合は、プラグインマーケットで `database-postgresql` をインストールし、完全に再起動してください。主データベースと対応済みプラグインの専用データベースは、選択したエンジンを共用します。SQLite の専用データベースは `data/{pluginId}/plugin.db` にあり、PostgreSQL では owner ごとに schema と制限付きロールを分離します。

GUI とデータベースの両方を利用できない場合は、ホストの復旧ウィンドウからプラグインマーケットを開いて修復するか、「インストール済み」で無効なプラグインを再び有効にしてください。再起動後、デスクトップのデータベース設定で接続を確認します。データベースを利用できない場合でも、この設定ページは開けます。

デスクトップの「設定 → 実行とネットワーク → 主データベース」で切り替えます。

1. 読み込み済みのデータベースプラグインを選び、接続情報を入力して「接続テスト」をクリックします。パスワードを空欄にすると保存済みの値を保持します。
2. 「確認して続行」で、主データベースとすべてのプラグイン専用データベースの移行元、移行先、処理方法を事前検査します。空でない移行先は、provider が安全なバックアップと置換に対応する場合だけ続行できます。
3. 一覧を確認すると、このページの設定を保存して自動的に完全再起動します。新プロセスでバックアップ、移行、データベースごとの検証（型付き SHA-256、行数、生成キーの到達値、NUL 文字の除去）、原子的な置換を行い、すべて成功してから接続設定を切り替えます。同じデータベースの接続パラメーターだけを直す場合は「接続を適用して再起動」を使います。

移行記録とバックアップは `data/database-migrations/` に保存されます。移行計画の認証情報を含む場合があるため安全に保管し、起動時の再確認や再試行に使えるよう保持してください。選択したデータベースプラグインが不在、無効、接続失敗の場合は復旧モードに入り、業務 API は 503 を返し、バックグラウンドのデータベースタスクは停止します。他のエンジンへの自動切り替えや、空の SQLite の新規作成は行いません。

#### PostgreSQL の準備と設定例

専用のデータベースと主データベース用 schema を事前作成し、テーブル、インデックス、シーケンス、関数、トリガーの作成と読み書きの権限を与えてください。プラグイン専用データベースには `CREATEROLE` とデータベースの `CREATE` 権限も必要で、主データベースや他の owner の権限を `PUBLIC` に開放してはいけません。`currentSchema` は小文字、数字、アンダースコアからなる単一の名前のみ対応し、数字で始めず、最大 63 文字、デフォルトは `public` です。リモート接続には `sslmode=verify-full` を推奨します。パスワードは独立した設定項目または環境変数 `DATABASE_PASSWORD` に置き、JDBC URL には含めないでください。`config.yaml` を直接変更しても既存データは移行されません。

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

#### 停止中に使う CLI 移行ツール（任意）

停止時間を別途確保して**空の移行先データベース**へ移行する場合は、移行元を使う全プロセスを停止し、`database.provider` を明示した `source.yaml` と `target.yaml` を用意します。SQLite の移行元は `database.provider: database-sqlite` と `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"` を指定できます。配布パッケージを完全に展開したディレクトリで実行してください。

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

- 移行元に小説の再構築可能なオブジェクトがある場合は、`--rebuild-objects novels_fts,novel_soft_delete_cleanup` を明示的に追加します。実在するオブジェクトだけを指定してください。
- アンインストール後もデータベースのデータを残すプラグインは、アンインストール前に `--export-schema schema.json` で宣言を出力し、移行時に `--retained-schema schema.json` で補えます。
- `report.json` が `DATA_VERIFIED`、かつすべての `stores` が `COMMITTED` の場合だけ、コピーの検証が完了したと判断できます。その後で有効な接続設定を切り替え、アプリケーションを起動します。

### 画像とうごイラの保存形式

デスクトップの「設定 → ダウンロード」または Web のダウンロード設定で、保存形式を複数選択できます。少なくとも 1 つ必要です。デフォルト値は `config/plugins/download-workbench.properties` の `download-workbench.media.*` に保存されます。

- **画像**：元の形式（デフォルト）、PNG、JPG、WebP。元の形式を選択しない場合でも、選んだすべての派生形式の生成に成功してから元画像を削除します。
- **うごイラ**：WebP（デフォルト）、GIF、APNG、MP4（FFmpeg の H.264 エンコーダーが必要）、元 ZIP。ZIP にはオフラインの追加変換用に `.frames.properties` のフレーム時間情報も保存します。
- **変換パラメーター**：`quality` はデフォルト `90`（`1–100`）、`webp-lossless` は `false`、`maximum-edge` は `0`（サイズ変更なし、`1–16383` を指定可能）。新たにエンコードするコピーだけに適用します。

既存作品はデスクトップの「ツール → 過去のメディア処理」で対象形式を検査し、不足する形式とサムネイルを補えます。1 バッチの上限は 500 作品、10,000 候補ファイルです。ギャラリーは「保持した元画像 → WebP → PNG → JPG」の順に優先表示し、MP4 には動画プレーヤーを使います。

### プラグインマーケット

| キー | デフォルト | 説明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | マーケット全体のスイッチ。無効にすると、どのプラグインリポジトリにもアクセスしない |
| `plugin-catalog.official-repository-enabled` | `true` | 組み込みの公式リポジトリを有効にするか |
| `plugin-catalog.community-repository-enabled` | `true` | 組み込みのコミュニティリポジトリを有効にするか |
| `plugin-catalog.connect-timeout-ms` | `15000` | リポジトリへの接続タイムアウト（ミリ秒） |
| `plugin-catalog.read-timeout-ms` | `60000` | リポジトリの読み込みタイムアウト（ミリ秒） |
| `plugin-catalog.max-manifest-bytes` | `1048576` | マニフェスト 1 件のサイズ上限（1 MiB） |
| `plugin-catalog.max-package-bytes` | `201326592` | プラグインパッケージ 1 件のサイズ上限（192 MiB） |
| `plugin-catalog.repositories` | 空のリスト | カスタムプラグインリポジトリの一覧 |

デスクトップの「設定 → プラグインマーケット設定」で `repository.json` 記述子を取り込むか、カスタムリポジトリを入力する方法を推奨します。手動設定の例：

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

リポジトリの `id` は一意とし、予約値の `official`、`community`、`configured` は使えません。`proxy-policy` は `direct-strict`（直接接続、公開ネットワークの HTTPS のみ、リダイレクト禁止）、`proxy-trusted`（アプリケーションのプロキシを使用。組み込みの信頼済みホストに限り最大 5 回のリダイレクトを許可し、各段階で再検証）、`custom` に対応します。

### 外向きプロキシとブラウザー PAC

| キー | デフォルト | 説明 |
| --- | --- | --- |
| `proxy.enabled` | `true` | ホストの外向き HTTP プロキシを有効化 |
| `proxy.host` | `127.0.0.1` | プロキシのホスト |
| `proxy.port` | `7890` | プロキシのポート |

ユーザースクリプトとの併用などで、ブラウザーから `pixiv.net` へ接続する際もバックエンドと同じプロキシを使い、システム全体のプロキシは有効にしたくない場合、OS またはブラウザーの「自動プロキシ設定スクリプト（PAC）URL」を `http://localhost:6999/proxy.pac` に設定できます。このマシンからだけアクセスでき、プロキシ設定のホットリロードに合わせて自動更新され、Pixiv 関連ドメインだけをプロキシ経由にします。

### マルチユーザーモードのクォータとゲストのレート制限

`multi-mode.*` は、マルチユーザーモードで未ログインかつ招待コードを持たない**匿名ユーザー**だけを対象にします。招待コードを持つ**招待ゲスト**は、`solo` / `multi` の両方で独立した `guest-invite.*` のレート制限を使います。

| キー | デフォルト | 説明 |
| --- | --- | --- |
| `multi-mode.quota.enabled` | `true` | 匿名ユーザーのクォータを有効化 |
| `multi-mode.quota.max-artworks` | `50` | 1 周期での作品数の上限 |
| `multi-mode.quota.reset-period-hours` | `24` | クォータのリセット周期（時間） |
| `multi-mode.quota.archive-expire-minutes` | `60` | 作成したアーカイブの有効期間（分） |
| `multi-mode.quota.limit-image` | `0` | 1 作品の画像数がこの値を超えると複数作品分に換算。`0` は換算しない |
| `multi-mode.quota.max-proxy-requests` | `200` | プロキシリクエストのクォータ |
| `multi-mode.quota.archive-max-concurrent` | `10` | アーカイブ作成の同時実行上限 |
| `multi-mode.post-download-mode` | `pack-and-delete` | `pack-and-delete`、`never-delete`、`timed-delete` から選択 |
| `multi-mode.delete-after-hours` | `72` | `timed-delete` で保持する時間数 |
| `multi-mode.limit-page` | `3` | ページ数の上限。`0` は無制限 |
| `multi-mode.request-limit-minute` | `300` | 匿名ユーザーの毎分リクエスト上限 |
| `multi-mode.static-resource-request-limit-minute` | `1200` | 匿名ユーザーの毎分静的リソースリクエスト上限 |
| `guest-invite.request-limit-minute` | `300` | 招待ゲストの毎分リクエスト上限 |
| `guest-invite.static-resource-request-limit-minute` | `1200` | 招待ゲストの毎分静的リソースリクエスト上限 |
| `guest-invite.tts-request-limit-minute` | `30` | 招待ゲストの毎分 TTS リクエスト上限 |
| `setup.login-rate-limit-minute` | `10` | ログインと招待コード交換の毎分試行上限 |

### 保守時間帯、画面、更新、スケジュールタスク

- **保守時間帯**：`maintenance.enabled` はデフォルト `true`。`maintenance.monday.enabled=true` で、他の曜日はデフォルト `false`。各曜日の時刻キー `maintenance.<day>.time` はデフォルト `10:00` です。
- **言語とデスクトップ画面**：`app.language` はデフォルトで空（システムに追従。画面だけに適用され、実行ログは英語固定）。`app.theme` は `system`（`light`、`dark`、現在の画面の拡張テーマも選択可能）。`app.gui-provider` は空（デフォルトで Compose を選択し、切り替えには完全再起動が必要）。`app.config-menu-expand-all`（デフォルト `false`）は Swing だけに適用されます。
- **オンライン更新**：`update.enabled=true`、`update.auto-check=true`。`update.manifest-url` と `update.nightly-manifest-url` は公式の署名付きマニフェストを指します。`update.check-nightly` は正式版ではデフォルト `false`、nightly 版では `true` です。
- **ホストのスケジュールタスク**：`schedule.*` は[スケジュールタスクの関連設定](/ja/scheduled-tasks#トラブルシューティングと関連設定)を参照してください。
- **プラグインの有効・無効**：`plugins.{pluginId}.enabled` はホストが管理し、反映方法はプラグインのライフサイクル方針に従います（[プラグイン管理](/ja/plugin-management)を参照）。

### HTTPS とリバースプロキシ

| キー | デフォルト | 説明 |
| --- | --- | --- |
| `ssl.domain` | `localhost` | 外部アクセス用ドメイン |
| `ssl.type` | `pem` | `pem` または `jks`。両方を設定した場合は PEM を優先 |
| `server.ssl.enabled` | `false` | 組み込み HTTPS を有効にするか |
| `server.ssl.certificate` / `server.ssl.certificate-private-key` | 空 | PEM 証明書と秘密キーのパス |
| `server.ssl.key-store-type` / `key-store` / `key-store-password` | `JKS` / 空 / 空 | JKS キーストアの設定 |
| `ssl.http-redirect` / `ssl.http-redirect-port` | `false` / `80` | HTTP から HTTPS への自動リダイレクト |
| `server.trusted-proxy-cidrs` | 空 | 信頼するリバースプロキシの出口 IPv4 / IPv6 CIDR。カンマ区切り |

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

`server.trusted-proxy-cidrs` が空なら直接接続モードで動作し、`Forwarded`、`X-Forwarded-*`、`X-Real-IP` を含むリクエストをすべて拒否します。設定する場合は、実際にバックエンドへ接続するリバースプロキシの出口またはコンテナーネットワークだけを指定し、`0.0.0.0/0`、`::/0`、クライアントのネットワークを指定してはいけません。信頼するリバースプロキシは、各リクエストに完全で単一形式の転送ヘッダーを付ける必要があります。`for`/`proto`/`host` を含む RFC `Forwarded`、または `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host` の組を使用します。欠落、混在、信頼 CIDR からのヘッダーなしの直接接続はいずれも HTTP 400 になります。

## プラグインの設定、認証情報、拡張設定

- **プラグインの業務設定（`config/plugins/{pluginId}.properties`）**：UTF-8 の Java properties 形式を使い、デスクトップ設定ページに集約表示してまとめて保存します。ホストのキーの上書きや、平文の認証情報の記入は禁止です。
- **プラグインの認証情報（`config/credentials/{pluginId}.properties`）**：パスワード、Cookie、API Key、Secret、Webhook の秘密キーを保存します。ホストが暗号化し、owner ごとにプラグインコンテキストへ注入します。環境間の移行には認証情報を含むパスワード付き `.pdbackup` を使ってください。同じ環境に戻すファイルバックアップでも、`config/credentials.key` とその復号条件を保持する必要があります。
- **翻訳設定（`translation` プラグイン）**：通常の項目は `config/plugins/translation.properties` にあります。`translation.engine` はデフォルト `ai`、選択肢は `youdao`、`libretranslate`。`translation.libretranslate-url` は `/translate` を含めません。`translation.use-proxy` はデフォルト `false` で、有道と LibreTranslate だけを制御します。`translation.max-concurrent` はデフォルト `10` です。保存後にバックエンドを再起動すると反映されます。[小説と読み上げ](/ja/novel#翻訳エンジン（管理者）)を参照してください。
- **ログイン検証ポリシー**：`setup.login-verification-policies` はデフォルトで空です。`LoginVerification.policyId()` のポリシーキーをカンマ区切りで最大 16 件指定でき、バックエンド再起動後に反映されます。設定前に対応する検証プラグインが有効であることを確認してください。[操作 CAPTCHA](/ja/captcha)と[サードパーティープラグイン SDK](/ja/plugin-development#gui-に依存しない認証情報と汎用操作検証)を参照してください。
