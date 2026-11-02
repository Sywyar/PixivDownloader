# インストール

[Releases](https://github.com/Sywyar/PixivDownloader/releases) から環境に合う配布物を選びます。ファイル名の `*` / `x.x.x` はバージョンです。コマンドの `APP.jar` は実際の主 JAR 名に置き換え、パッケージ全体を展開したディレクトリで実行してください。

| 環境 | 配布物 | 別途必要なもの |
|---|---|---|
| Windows x64 | `*-win-x64-setup.exe` | 必要に応じて FFmpeg |
| Windows / Linux / macOS で Java を使う | `*-java.zip` | Java 17 以降、必要に応じて FFmpeg |
| 任意のデータベース・インポート機能も同梱したい | `*-full-offline.zip` | Java 17 以降、必要に応じて FFmpeg |
| Docker で常駐する | リポジトリの Dockerfile と完全な Java 配布物からビルド | Docker Engine、Compose、書き込み可能なデータディレクトリ |

標準パッケージは既定の公式プラグインを含み、オフライン完全版はさらに `database-postgresql` と `pixiv-batch-downloader-import` を含みます。Java 版のどちらにも JRE、FFmpeg、Douyin は入りません。「オフライン完全版」でも Pixiv、オンライン AI、TTS への接続にはネットワークが必要です。

## Java 版を使う

1. Java 17 以降を用意します。Windows は [Adoptium](https://adoptium.net/)、macOS は `brew install openjdk@17`、Debian / Ubuntu は `sudo apt install openjdk-17-jdk`、Fedora は `sudo dnf install java-17-openjdk` などを使えます。
2. `java -version` でバージョンを確認します。
3. `PixivDownload-*-java.zip` または `PixivDownload-*-full-offline.zip` をダウンロードし、すべて展開します。起動スクリプトと `plugins/` も必要です。
4. Windows は `run.bat`、Linux / macOS は展開先で `sh run.sh` を実行します。
5. [初回セットアップ](/ja/first-setup)を完了します。

手動起動は PowerShell / Bash 共通で次のとおりです。

```text
java -Dfile.encoding=UTF-8 -jar APP.jar
```

起動スクリプトには UTF-8 指定が含まれます。手動でも付けてください。単独のシェル JAR は必須のダウンロードプラグインを含まない内部ビルド入力で、通常のユーザー向け配布物ではありません。これだけを起動すると復旧モードに進みます。

### GUI のないサーバー

最初に `--setup` を実行し、管理者、モード、HTTP プロキシを設定します。GUI の案内はなく、`setup.html` はローカル接続のみです。未初期化の `--no-gui` は終了コード `78` で止まります。

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

Bash でバックグラウンド起動する例：

```bash
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

自動化では `--username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo`、必要なら `--proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890` を `--setup` に追加できます。値は実環境に合わせてください。パスワードは履歴・プロセス一覧に残り得るため、通常は対話入力を使います。プロキシ引数を省略すると質問し、不要なら `--proxy-enabled=false` を指定できます。

プロキシが適用されるのは、ホストやタスクがその経路を選ぶ Pixiv、更新、FFmpeg、一部の TTS などです。ブラウザー、SMTP、明示的な直接接続は別です。[ネットワークアクセス](/ja/network-access)を参照してください。

## Windows インストーラー

Windows 版には JRE と公式プラグインが含まれます。

1. `PixivDownload-x.x.x-win-x64-setup.exe` を取得し、起動します。
2. 言語（中国語 / English）とインストール先を選びます。
3. 必要なら FFmpeg のダウンロード・インストールを選びます。普通の画像だけなら不要で、後から GUI の状態画面で追加できます。
4. 完了後に起動し、初期設定を行います。以後はスタートメニューやショートカットから開けます。

インストール前に動作中の `PixivDownload.exe` を検出すると、終了して再試行する案内を表示します。再度インストーラーを実行すると、修復、コンポーネント変更、アンインストールを選べます。

インストーラーは UAC を要求します。インストール済みアプリと portable ランチャーも既定で管理者権限を要求します。ホストが実際に昇格している場合、`host-process-full-trust` プラグインも同じ権限を持ち、管理画面に警告を表示します。

## Docker

Dockerfile は署名済み Java 配布ディレクトリを入力にし、Java 17、FFmpeg、curl を含むイメージを作ります。

1. リポジトリの `build/dist/default-downloader/` に標準版またはオフライン完全版を全展開します。その直下に主 JAR、`plugins/`、`plugins-manifest.json`、`SHA256SUMS` があることを確認します。
2. `docker compose build` を実行します。
3. Linux の新規環境では、コンテナーの UID / GID `10001` が書き込めるディレクトリを用意します。

   ```bash
   sudo install -d -o 10001 -g 10001 config state data pixiv-download log
   ```

4. `Dockerfile` と `docker-compose.yml` のある場所で初期化します。

   ```bash
   docker compose run --rm app --setup
   ```

5. 起動し、ログを確認します。

   ```bash
   docker compose up -d
   docker compose logs -f app
   ```

初期化を飛ばすと終了コード `78` で再起動を繰り返す場合があります。アカウントは `state/setup_config.json`、プロキシは `config/config.yaml` に保存します。

既定の公開ポートはホストの `127.0.0.1:6999` だけにバインドします。同じホストで `http://127.0.0.1:6999/` を開いてください。リモートからは既存の SSH トンネルや、別途設定したリバースプロキシを使います。管理者ログイン後にプラグインの照会・インストール・修復ができます。

### Docker のプロキシ

コンテナー内の `127.0.0.1` はコンテナー自身です。Compose は `host.docker.internal` をホストゲートウェイへ割り当てています。ホストのプロキシがコンテナーからの接続を許可していることを確認し、例のポートを実際のものに変更してください。

```bash
docker compose run --rm app --setup --proxy-host=host.docker.internal --proxy-port=7890
```

不要なら `--proxy-enabled=false` を指定します。後から設定する場合は、マウントされた `config/config.yaml` を編集し、`docker compose restart app` を実行します。

```yaml
proxy.host: host.docker.internal
proxy.port: 7890
proxy.enabled: true
```

ポート、SSL などの変更後も再起動します。`server.port` を変えたら Compose のポート割り当てとヘルスチェックも合わせてください。

### 永続データと更新

| ホスト側の場所 | 内容 |
|---|---|
| `./config/` | ホスト・プラグイン設定と認証情報 |
| `./state/` | 初期化、ログイン、キュー、GUI の状態 |
| `./data/` | SQLite、アイコン、サムネイル、TTS、補完状態、参照音声など |
| `./pixiv-download/` | 作品、小説、シリーズ、短期アーカイブ |
| `./log/` | ログ |
| 名前付き volume `plugins` | インストール済みプラグイン |

インスタンスロックの `state/instance/` と SQLite ネイティブライブラリーの展開先 `state/` は書き込み可能で、ライブラリーをロードできる必要があります。ルートファイルシステムは読み取り専用、`/tmp` は `noexec,nosuid,nodev` のままです。

イメージを更新しても既存のプラグイン volume は自動で上書きしません。再作成後は管理画面でバージョンと無効状態を確認します。必須プラグインが非互換なら復旧モードとなるため、ホストに合う信頼済みパッケージをインストールします。

停止は `docker compose down` を使い、データを残す場合は volume を消す `-v` を付けないでください。

### ヘルスチェック

`GET /actuator/health` はログインなしで `{"status":"UP"}` を返し、内部の詳細は公開しません。`GET /actuator/info` は名前とバージョンです。`docker compose ps` では `healthy` / `unhealthy` を確認できます。`UP` だけでは、プラグイン、Cookie、ダウンロード全体の動作は確認できません。

## FFmpeg とユーザースクリプト

FFmpeg はメディア変換に使います。MP4 には H.264 エンコーダーが必要です。Windows のインストール時、または GUI の状態画面から自動導入できます。自動導入はプロジェクトの `ffmpeg-stable` Release を使い、公式の最新安定ソースを元にした Windows x64、Linux x64 / arm64、macOS x64 / arm64 の成果物を選びます。その他の環境はシステムの FFmpeg を使います。

手動では [FFmpeg](https://ffmpeg.org/download.html) を取得し、実行ファイルのディレクトリを PATH に追加して `ffmpeg -version` で確認します。

ユーザースクリプトは任意です。ダウンロード画面のスクリプト一覧からインストールするか、リポジトリのコミット済み個別スクリプトを使います。All-in-One はバックエンドから導入します。接続先が変わった場合は、新しいバックエンドから再インストールし、スクリプト設定の送信先も確認します。`@connect` はホスト許可だけで、送信先を変更しません。[ユーザースクリプト](/ja/userscripts)を参照してください。

## 保存先と動作確認

プラグインは `plugins/`、設定は `config/`、状態は `state/`、データ・キャッシュは `data/`、ログは `log/`、既定の作品保存先は `pixiv-download/` です。必要になってから作るディレクトリもあります。バックアップや更新では、作品だけでなくデータベースも保持します。[ストレージ](/ja/storage)で詳しい範囲を確認してください。

初期化・ログイン後に 1 件ダウンロードし、キューの完了、ファイル、ギャラリー表示を確認します。

| 既定の URL | 用途 |
|---|---|
| `http://localhost:6999/` | ダウンロード画面へ移動 |
| `http://localhost:6999/setup.html` | 初回設定。完了済みなら移動 |
| `http://localhost:6999/intro.html` | 紹介ページ |
| `http://localhost:6999/pixiv-batch.html` | `download-workbench` のダウンロード画面 |
| `http://localhost:6999/monitor.html` | ダウンロード監視 |
| `http://localhost:6999/pixiv-gallery.html` | `gallery` が提供するギャラリー |

## 同梱プラグインと復旧

既定の集合は `download-workbench`、`database-sqlite`、`gui-compose`、`gui-swing`、`gallery-tools`、`posthog`、`gallery`、`novel`、`notification`、`multi-mode-decision-survey`、`push`、`mail`、`tts`、`ai` です。Compose が既定、Swing が代替です。Douyin は第三者プラグインとしてカスタムリポジトリまたはローカルパッケージから導入します。

`download-workbench` は必須で、ダウンロード画面・API・キュー・スクリプト入口・Pixiv イラストプロキシ・スケジュールホストを提供します。欠落、破損、非互換、オフライン再検証失敗では、ログイン、プラグイン管理、修復を提供する復旧状態になります。

任意のプラグインがない場合は、その画面、API、ナビゲーション、リソース、翻訳、設定、能力がなくなります。未選択の任意機能の欠落は復旧モードを起こしませんが、現在選択しているデータベース provider の欠落は業務開始を妨げます。

`gallery-tools` がなくても画像ハッシュと履歴は保持します。`gallery` がなくてもダウンロード、スクリプト、プロキシ、スケジュールホスト、作品メタデータ、取得記録、ハッシュ、ローカル資源索引は保持します。`novel` がない場合は小説取得、画廊、本文保存、翻訳状態、合本、小説用スケジュール実行がなくなり、コアに代替実装はありません。
