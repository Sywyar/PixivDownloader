# インストールと起動

[Releases](https://github.com/Sywyar/PixivDownloader/releases) から、実行環境に合う配布パッケージを選んでください。以下のファイル名にある `*` や `x.x.x` はバージョン番号を表します。コマンド内の `APP.jar` は実際の本体 JAR 名に置き換え、パッケージ全体を展開したディレクトリで実行してください。

| 利用環境 | 配布パッケージ | 別途必要なもの |
| --- | --- | --- |
| Windows x64 デスクトップ | `*-win-x64-setup.exe` | JRE は同梱。必要に応じて FFmpeg をインストール |
| Windows / Linux / macOS で Java を使用 | `*-java.zip` | Java 17+。必要に応じて FFmpeg をインストール |
| オプションのデータベース、インポート、CAPTCHA などの公式プラグインを事前に揃える | `*-full-offline.zip` | Java 17+。必要に応じて FFmpeg をインストール |
| Docker の常駐サービス | リポジトリの `Dockerfile` と完全な Java 配布ディレクトリからビルド | Docker Engine、Compose、書き込み可能なマウントディレクトリ |

Java 標準パッケージと Windows インストーラーには、既定の公式プラグイン群が含まれます。オフライン完全パッケージには、`database-postgresql`、`pixiv-batch-downloader-import` など、必要に応じて使う公式プラグインも含まれます。どちらの Java パッケージにも、JRE、FFmpeg、サードパーティの `douyin` プラグインは含まれません。「オフライン完全」はオプションの公式プラグインを事前に揃えているという意味で、Pixiv、オンライン AI、オンライン TTS の利用にはネットワークが必要です。

## 方法 1：Java 標準パッケージ / オフライン完全パッケージ

1. Java 17 以降をインストールし、バージョンを確認します。Windows は [Adoptium](https://adoptium.net/)、macOS は `brew install openjdk@17`、Debian/Ubuntu は `sudo apt install openjdk-17-jdk`、Fedora は `sudo dnf install java-17-openjdk` を利用できます。

   ```bash
   java -version
   ```

2. `PixivDownload-*-java.zip` または `PixivDownload-*-full-offline.zip` をダウンロードし、**全体を展開**します。同じディレクトリにある起動スクリプトと `plugins/` を残してください。プログラムは起動時に、作業ディレクトリの `plugins/` から外部プラグインを読み込みます。

   ?> 単独の `PixivDownload-*.jar` は、外部プラグインを含まないコア部分のビルド入力であり、一般利用者向けの配布パッケージではありません。そのまま起動しても、ダウンロードワークベンチやデータベースプラグインは用意されません。

3. プログラムを起動します。Windows は `run.bat`、Linux / macOS は展開先で `sh run.sh` を実行するか、手動で次のコマンドを実行します。

   ```text
   java -Dfile.encoding=UTF-8 -jar APP.jar
   ```

   起動スクリプトには `-Dfile.encoding=UTF-8` が含まれます。手動起動でもこの引数を残し、中国語版 Windows のコンソールで文字化けするのを防いでください。

4. [初回セットアップ](/ja/first-setup)に従って、管理者アカウントとプロキシを設定します。

### デスクトップのないサーバー（`--no-gui`）

サーバーにはデスクトップの初回ガイドがなく、`setup.html` はローカルアクセスのみを許可します。未初期化のまま `--no-gui` で起動すると、終了コード `78` で停止します。初回起動前に、同じディレクトリで CLI 初期化を行ってください。

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

自動化スクリプトで非対話形式の初期化が必要な場合は、引数を明示できます。パスワードとプロキシは実際の値に置き換えてください。コマンドラインのパスワードはシェル履歴やプロセス一覧に残るため、通常は対話形式を優先してください。

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

プロキシが不要なら `--proxy-enabled=false` に変更します。Linux / macOS でバックグラウンド実行する例です。

```bash
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

バックエンドのプロキシは、ホストまたはタスクのルーティングを使うリクエストに適用されます。Pixiv のダウンロード、オンライン更新、FFmpeg のダウンロード、一部のオンライン TTS が対象です。ブラウザーからの直接通信、SMTP、データベース接続はそれぞれの経路を使います。詳しくは[ネットワークアクセスと外部サービス](/ja/network-access)を参照してください。

## 方法 2：Windows インストーラー

1. `PixivDownload-x.x.x-win-x64-setup.exe` を実行し、インストール言語とディレクトリを選びます。簡体字中国語、繁体字中国語、英語、日本語、韓国語に対応しています。
2. 必要に応じて「FFmpeg をダウンロードしてインストール」を選びます。通常の静止画像には FFmpeg は不要で、インストール後にデスクトップの「状態」ページからダウンロードすることもできます。
3. インストール完了後に起動するか、スタートメニューやデスクトップのショートカットから起動します。

インストール前に `PixivDownload.exe` が動作中と検出された場合は、終了して再試行するよう案内されます。インストーラーを再実行すると、修復、コンポーネント変更、アンインストールを行えます。インストーラー、インストール済みアプリ、ポータブルランチャーは、既定で管理者権限を要求します。ホストが実際に高い権限で動作している場合、`host-process-full-trust` プラグインも同じ権限を引き継ぎ、プラグイン管理ページにその状態が継続して表示されます。

## 方法 3：Docker でデプロイする

リポジトリの `Dockerfile` は、署名済みの完全な Java 配布ディレクトリからイメージをビルドします。イメージには Java 17、FFmpeg、curl が含まれます。

1. `*-java.zip` または `*-full-offline.zip` をリポジトリの `build/dist/default-downloader/` にすべて展開します。その直下に本体 JAR、`plugins/`、`plugins-manifest.json`、`SHA256SUMS` があることを確認し、イメージをビルドします。

   ```bash
   docker compose build
   ```

2. コンテナーは UID / GID `10001` で動作します。Linux に新規デプロイする場合は、`docker-compose.yml` のあるディレクトリで、書き込み可能なマウントディレクトリを作成してください。

   ```bash
   sudo install -d -o 10001 -g 10001 config state data pixiv-download log
   ```

3. 初期化を実行し、その後で常駐コンテナーを起動します。

   ```bash
   docker compose run --rm app --setup --mode=solo
   docker compose up -d
   docker compose logs -f app
   ```

!> `--setup` を完了せずに `docker compose up -d` を実行すると、終了コード `78` で再起動を繰り返します。

既定ではホストの `127.0.0.1:6999` にだけポートをバインドします。同じ端末のブラウザーで `http://127.0.0.1:6999/` を開いてください。リモートからアクセスする場合は、SSH トンネルまたはリバースプロキシを使用します。停止には `docker compose down` を実行し、データを残す場合はボリュームを削除する `-v` を付けないでください。

### コンテナー内のプロキシと永続化ディレクトリ

コンテナー内の `127.0.0.1` はコンテナー自身を指します。`docker-compose.yml` は `host.docker.internal` をホストのゲートウェイに割り当てています。ホストのプロキシを使う場合は、`--setup` または `config/config.yaml` のプロキシホストを `host.docker.internal` に設定し、ホスト側のプロキシがコンテナーのネットワークからの接続を許可していることを確認してください。

```bash
docker compose run --rm app --setup --mode=solo --proxy-host=host.docker.internal --proxy-port=7890
```

マウントした `config/config.yaml` を変更した後は、`docker compose restart app` で反映します。`server.port` を変更する場合は、`docker-compose.yml` のポート割り当てとヘルスチェックのアドレスも変更してください。

| ホストのマウント先またはボリューム | 永続化する内容 |
| --- | --- |
| `./config/` | `config.yaml`、プラグインの業務設定、暗号化された認証情報 |
| `./state/` | ログイン状態、`setup_config.json`、キューの再開情報、インスタンスロック、SQLite ネイティブライブラリの展開ディレクトリ |
| `./data/` | SQLite データベース、コレクションのアイコン、サムネイルと TTS キャッシュ、補完処理の状態、参照音声 |
| `./pixiv-download/` | ダウンロードしたイラスト、漫画、うごイラ、小説、一時アーカイブ `_archives` |
| `./log/` | 実行ログ |
| 名前付きボリューム `plugins` | インストール済みプラグインと provenance |

`state/` と `state/instance/` は書き込み可能で、SQLite ネイティブライブラリを読み込める必要があります。コンテナーのルートファイルシステムは読み取り専用、`/tmp` は `noexec,nosuid,nodev` を維持します。イメージを更新しても既存の `plugins` ボリュームは自動で上書きされません。コンテナーを再作成した後は、プラグイン管理ページでバージョンと互換性の状態を確認してください。

## FFmpeg とユーザースクリプト（任意）

- **FFmpeg**：うごイラ（Ugoira）から WebP / GIF / APNG / MP4 への変換やメディア処理に必要です。MP4 には H.264 エンコーダーが必要です。Windows のインストール時に選ぶか、デスクトップ GUI の「状態」ページで「FFmpeg をダウンロード」をクリックできます。プロジェクトの `ffmpeg-stable` Release から、署名を検証した Windows x64、Linux x64/arm64、macOS x64/arm64 のアセットを取得します。[FFmpeg 公式サイト](https://ffmpeg.org/download.html)からインストールしてシステムの `PATH` に追加する方法もあり、`ffmpeg -version` で確認できます。
- **ユーザースクリプト**：スクリプトをインストールしなくても、`pixiv-batch.html` で一括ダウンロードできます。Pixiv のページから直接操作する場合は、バックエンド起動後にダウンロードページの「ユーザースクリプト」からインストールするか、ソースリポジトリの独立した `.user.js` をインストールします。別の端末にデプロイしている場合は、スクリプトのバックエンドアドレスと `@connect` を合わせて設定してください。詳しくは[ユーザースクリプト](/ja/userscripts)を参照してください。

## インストールの確認とプラグインについて

[初回セットアップ](/ja/first-setup)の後、`http://localhost:6999/` にログインし、[初回ダウンロード](/ja/first-download)に従って作品を 1 件ダウンロードしてください。ディスクにファイルが作成され、ギャラリーでその作品を開けることを確認します。`GET /actuator/health` の `{"status":"UP"}` は HTTP サービスの起動だけを表し、プラグイン、データベース、プロキシ、Pixiv Cookie がすべて準備できていることを表すものではありません。

主な入口：

- `http://localhost:6999/`：有効なダウンロードページへ既定でリダイレクト
- `http://localhost:6999/setup.html`：ローカルの初回設定ページ。初期化後は自動リダイレクト
- `http://localhost:6999/pixiv-batch.html`：ダウンロードワークベンチ。既定でインストールされる `download-workbench` が提供
- `http://localhost:6999/pixiv-gallery.html`：作品ギャラリー。既定でインストールされる `gallery` が提供
- `http://localhost:6999/monitor.html`：ダウンロード監視ページ

既定でインストールされる公式プラグインには、`download-workbench`、`database-sqlite`、`gui-compose`（既定のデスクトップ画面）、`gui-swing`（予備のデスクトップ画面）、`gallery`、`gallery-tools`、`novel`、`notification`、`push`、`mail`、`tts`、`ai`、`posthog`、`multi-mode-decision-survey` などがあります。`database-postgresql`、`pixiv-batch-downloader-import`、`page-editor`、`captcha`、`translation` などは、必要に応じてプラグインマーケットやオフライン完全パッケージから取得できます。選択中のプライマリデータベースプラグインが使えない場合は復旧モードに入り、ログイン、プラグイン管理、修復の入口だけを公開します。オプションのプラグインが無効または存在しない場合は、そのプラグインのページと機能だけが利用できなくなります。[プラグイン管理](/ja/plugin-management)と[ストレージとバックアップ](/ja/storage)を参照してください。
