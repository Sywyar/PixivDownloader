# 初回セットアップ

?> この手順は初回起動時だけ必要です。設定後はメイン画面が直接開きます。

## セットアップ入口

| 起動方法 | 入口 |
|--------------|-------------------|
| デスクトップ GUI（既定） | GUI の「ホーム」ウィザード |
| ローカルブラウザー + `--no-gui` | `http://localhost:6999/setup.html` を自動的に開く |
| サーバー / Docker（ヘッドレス） | CLI の `--setup` |

## 方法 1：GUI ウィザード（デスクトップ推奨）

PixivDownloader をインストールして起動すると、GUI の「ホーム」タブに 7 段階のウィザードが表示されます。

### 1. サービスの準備を待つ

バックエンドの起動状態が表示されます。状態が「**実行中**」になるまで（通常 5〜15 秒）待ち、「次へ」をクリックします。

### 2. 管理者アカウントと動作モード

ユーザー名と 6 文字以上のパスワードを入力し、動作モードを選びます。

| モード | 用途 | 特徴 |
|------|----------|----------------|
| **ソロモード** | 個人利用 | ログインが必要。ダウンロード設定をサーバーに保存 |
| **マルチモード** | 他のユーザーと共有 | ゲストはログイン不要。クォータとレート制限に対応 |

ここで設定するアカウントは Pixiv のアカウントとは無関係です。個人利用ではほとんどの場合「**ソロモード**」を選んでください。「セットアップ完了」で次に進みます。

### 3. HTTP プロキシ

Pixiv のダウンロード、更新確認、FFmpeg のダウンロード、一部プラグインの通信は、ホストまたはタスクの経路に応じてこのプロキシを使います。ブラウザー、SMTP、明示的な直接接続は別の経路です。詳細は [ネットワークアクセスとサードパーティサービス](/ja/network-access) を参照してください。

- Clash / V2Ray などを使う場合：通常はホスト `127.0.0.1`、ポート `7890`
- 直接接続する場合：プロキシを無効にする

プロキシ設定はホットリロードに対応しており、GUI の「設定」ページから再起動なしで変更できます。

### 4〜7. ガイド付き確認

ウィザードはブラウザーのダウンロードページ、ギャラリー、高度な機能、完了ページの順に案内します。最後の「完了」まで進むと「ホーム」タブは自動的に隠れ、次回から「ステータス」ページが開きます。

再実行するには `state/gui/` 内の進捗・完了マーカーを削除します。

## 方法 2：ブラウザーのセットアップ

GUI を使わず、同じマシンのブラウザーで設定する場合：

1. `java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui` を起動
2. ブラウザーで `http://localhost:6999/setup.html` を開く
3. 認証情報、モード、プロキシを入力して「セットアップ完了」をクリック

!> `setup.html` は**ローカルブラウザーからの接続だけ**を受け付けます。リモートブラウザーからは開けません。

## 方法 3：CLI（サーバー / Docker）

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

順番にユーザー名、6 文字以上のパスワード、確認パスワード、`solo` / `multi`、HTTP プロキシの有効・無効、プロキシのホストとポートを入力します。完了後は通常どおり起動します。

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
```

自動化する場合は次の形式も使えます。パスワードがシェル履歴やプロセス一覧に残るため、管理された環境だけで使ってください。

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin --password='YourPassword' --mode=solo \
    --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

Docker では先に `docker compose run --rm app --setup`、その後 `docker compose up -d` を実行します。

## セットアップ後

`http://localhost:6999/pixiv-batch.html` を開き、次の [初回ダウンロード](/ja/first-download) に進んでください。

## 後からの管理

パスワード変更：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --change-password
```

パスワードを忘れた場合はサービスを停止して次を実行します。

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

リセット後は既存のセッションが無効になります。再初期化はサービスを停止して `state/setup_config.json` を削除し、再度 `--setup` または GUI ウィザードを実行します。ダウンロード済みファイルとデータベースは削除されません。
