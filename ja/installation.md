# インストールガイド

## 動作要件

| 依存関係 | 最低バージョン | 備考 |
|------------|----------------|-------|
| **Java** | 17 以上 | Java 標準 / 完全オフラインパッケージに必要。Windows インストーラーには JRE を同梱 |
| **OS** | Windows / macOS / Linux | クロスプラットフォーム |
| **Tampermonkey** | 最新版 | ユーザースクリプトに必要 |
| **ffmpeg** | 任意 | うごイラを WebP に変換する場合に必要 |

## 方法 1：Java 標準 / 完全オフラインパッケージ

### 1. Java 17 以上をインストール

- **Windows**：[Adoptium](https://adoptium.net/) からダウンロード
- **macOS**：`brew install openjdk@17`
- **Linux**：Debian / Ubuntu は `sudo apt install openjdk-17-jdk`、Fedora は `sudo dnf install java-17-openjdk`

```bash
java -version
# openjdk version "17.0.x" のように表示されます
```

### 2. ダウンロードと展開

[Releases](https://github.com/Sywyar/PixivDownloader/releases) から次を選びます。

- `PixivDownload-*-java.zip` — Windows インストーラーと同じ既定プラグイン構成（オンデマンドの Douyin と Compose GUI を除く）
- `PixivDownload-*-full-offline.zip` — 上記に Douyin を追加した完全オフライン版

アーカイブは**すべて展開**してください。JAR だけを取り出してはいけません。起動スクリプトと `plugins/` ディレクトリが必要です。公式外部プラグインは起動時に作業ディレクトリの `plugins/` から読み込まれます。

?> 単体の `PixivDownload-*.jar` は `download-workbench` を含まないコアシェルです。通常の利用者向け添付物ではなく、直接起動するとリカバリーモードになります。

### 3. 起動

Windows は `run.bat`、Linux / macOS は展開先で `sh run.sh` を実行します。

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

Windows で文字化けしないよう、常に `-Dfile.encoding=UTF-8` を付けてください。`run.bat` / `run.sh` にはあらかじめ含まれています。

### 4. サーバー / Docker でバックグラウンド起動

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
nohup java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui > app.log 2>&1 &
```

`--no-gui` は初回セットアップ完了後にだけ使用できます。未設定の場合は先に次を実行してください。

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

プロキシは、Pixiv のダウンロード、オンライン更新、FFmpeg の取得、一部のオンライン TTS など、ホストまたはタスクの経路が選択したバックエンドアクセスに使用されます。ブラウザー、SMTP、明示的な直接接続は別の経路です。詳しくは [ネットワークアクセスとサードパーティサービス](/ja/network-access) を参照してください。

## 方法 2：Windows インストーラー（Windows 推奨）

1. [Releases](https://github.com/Sywyar/PixivDownloader/releases) から `PixivDownload-x.x.x-win-x64-setup.exe` を取得して実行する
2. インストール言語、日本語を含む言語を選ぶ
3. インストール先を選ぶ
4. 必要なら「FFmpeg をダウンロードしてインストール」を選ぶ

通常の画像取得に FFmpeg は不要です。後から GUI のステータスページで追加できます。

インストーラーをもう一度実行すると、修復、コンポーネント変更、アンインストールを選べます。インストール時は `PixivDownload.exe` が実行中なら終了を求められます。

?> Windows インストーラーには、必須 `download-workbench` と既定 `gui-swing` を含む標準インストール対象の公式プラグインが入ります。オンデマンドの `douyin` と `gui-compose` は Web プラグインマーケットから追加でき、完全オフラインパッケージには両方が含まれます。

## 方法 3：Docker

リポジトリには `Dockerfile` と `docker-compose.yml` があります。

```bash
docker compose run --rm app --setup
docker compose up -d
docker compose logs -f app
```

初期化前に `up` を実行しないでください。セットアップが完了していないコンテナは終了コード 78 で再起動を繰り返します。起動後は `http://<host-ip>:6999/` を開きます。

コンテナ内の `127.0.0.1` はコンテナ自身を指します。ホストのプロキシを使う場合は、セットアップ時に `--proxy-host=host.docker.internal --proxy-port=7890` を指定してください。設定を変更したら `docker compose restart app` を実行します。

`config/`、`state/`、`data/`、`pixiv-download/`、`log/` は compose によりホストへマウントされ、再起動後も保持されます。

## ユーザースクリプト（任意）

まず Web UI の `http://localhost:6999/pixiv-batch.html` を開き、「🧩 ユーザースクリプト」カードから「⬇ インストール」をクリックする方法を推奨します。リリースから `.user.js` を取得し、Tampermonkey のダッシュボードへドラッグすることもできます。

Web UI からのインストールでは、現在のバックエンドアドレスに合わせて `@connect` が設定されます。別ホストで使う場合はスクリプトを編集して接続先を変更してください。

## FFmpeg のインストール（任意）

FFmpeg はうごイラを WebP に変換するために使います。

- Windows インストーラーで「FFmpeg をダウンロードしてインストール」を選ぶ
- GUI の「ステータス」ページで「FFmpeg をダウンロード」をクリックする
- [FFmpeg 公式サイト](https://ffmpeg.org/download.html)から取得し、`ffmpeg.exe` のあるディレクトリを `PATH` に追加する

確認：`ffmpeg -version`

## 起動後の確認

- `http://localhost:6999/` — ダウンロードページへリダイレクト
- `http://localhost:6999/setup.html` — 初回セットアップ（完了後はリダイレクト）
- `http://localhost:6999/intro.html` — 製品紹介
- `http://localhost:6999/pixiv-batch.html` — 一括ダウンロード
- `http://localhost:6999/monitor.html` — ダウンロード監視
- `http://localhost:6999/pixiv-gallery.html` — 作品ギャラリー（`gallery` プラグインが必要）

`download-workbench` は必須の外部プラグインで、ダウンロードページ、API、キュー、ユーザースクリプト入口、Pixiv プロキシ、スケジュールタスクホストを提供します。不足・破損・検証失敗時はリカバリーページになります。その他の任意プラグインがない場合は、そのプラグインのページや機能だけがなくなり、リカバリーには入りません。
