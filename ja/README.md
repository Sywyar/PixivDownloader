# PixivDownloader 利用ガイド

PixivDownloader は、Pixiv のイラスト、漫画、うごイラ、小説をローカルのパソコンやサーバーにダウンロードし、ローカルギャラリーで管理するアプリです。デスクトップ画面、ブラウザーのダウンロードページ、Tampermonkey スクリプトから操作できます。閲覧制限のある作品をダウンロードするには、自分の Pixiv Cookie を設定する必要があります。

## はじめて使う

1. [インストールと起動](/ja/installation)：Windows インストーラー、Java 配布パッケージ、Docker から方法を選び、パッケージ全体を展開します。
2. [初回セットアップ](/ja/first-setup)：本アプリの管理者アカウントを作成し、バックエンドのプロキシを設定します。
3. [初回ダウンロード](/ja/first-download)：Cookie を保存し、作品を 1 件ダウンロードして、ローカルディレクトリとギャラリーで結果を確認します。

デスクトップ画面の既定は Compose で、配布パッケージには Swing も含まれます。簡体字中国語、繁体字中国語、英語、日本語、韓国語に対応しています。既定のデータベースは SQLite です。PostgreSQL を使う場合は、対応するデータベースプラグインを先にインストールし、設定ページでデータの確認と移行を行ってください。

## 操作から探す

| 操作 | ドキュメント |
| --- | --- |
| 自分のブックマーク、作品、フォロー中の新作、Pixiv コレクションをダウンロードする | [クイック取得](/ja/quick-access) |
| 作品リンクや ID をまとめて貼り付けてダウンロードする | [URL 一括ダウンロード](/ja/batch-download) |
| 指定した作者の作品をダウンロードする | [作者別一括ダウンロード](/ja/user-download) |
| Pixiv をキーワードで検索してダウンロードする | [検索ダウンロード](/ja/search) |
| 小説を保存、EPUB に合本、閲覧、翻訳、読み上げする | [小説と読み上げ](/ja/novel) |
| 新作を定期的に検出してダウンロードする | [スケジュールタスク](/ja/scheduled-tasks) |
| Pixiv のページからダウンロード支援機能を呼び出す | [ユーザースクリプト](/ja/userscripts) |
| PixivBatchDownloader のダウンロードをギャラリーに登録する | [外部ダウンロードのインポート](/ja/pixiv-batch-downloader-import) |
| 登録済み作品の閲覧、絞り込み、エクスポート、削除を行う | [作品ギャラリー](/ja/gallery) |
| 設定、プロキシ、データベース、メディア形式を変更する | [設定リファレンス](/ja/configuration) |
| 作品とデータディレクトリをバックアップ、復元、移動する | [ストレージとバックアップ](/ja/storage) |
| 起動引数、ファイル名テンプレート、保守ツール、招待ゲストを確認する | [CLI と詳細リファレンス](/ja/usage-guide) |
| 外部リクエストの宛先、送信データ、無効化方法を確認する | [ネットワークアクセスと外部サービス](/ja/network-access) |
| 起動、接続、Cookie、ダウンロードの問題を調べる | [よくある質問](/ja/faq) |
| プラグインのインストール、更新、有効化、無効化、修復を行う | [プラグイン管理](/ja/plugin-management) |
| ページのレイアウト、スタイル、カスタムコンポーネントを編集する | [ページエディター](/ja/page-editor) |
| ログインなどのフォームにオンラインまたはオフラインの CAPTCHA を設定する | [操作 CAPTCHA](/ja/captcha) |

## 開発と参加

- [サードパーティープラグイン SDK](/ja/plugin-development)：テンプレートからのプロジェクト作成、拡張契約の利用、署名、投稿を説明します。
- [主リポジトリの開発](/ja/development)：環境の準備、ビルド、テスト、PR の提出を説明します。
- [SDK ダウンロード](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) · [バージョン別 API リファレンス](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

## バージョンと問い合わせ

配布パッケージは [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases) からダウンロードでき、変更履歴は [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md) で確認できます。問題を調べても解決しない場合は、[Issues](https://github.com/Sywyar/PixivDownloader/issues) にバージョン、再現手順、実際の結果、機密情報を除いたログを添えて報告してください。Cookie、パスワード、トークンは含めないでください。

既定の Web アドレスは `http://localhost:6999/` です。利用できるページは有効なプラグインによって変わります。復旧ページが表示された場合は、まず必須プラグインと選択中のデータベースの状態を確認してください。

本プロジェクトは [Sywyar](https://github.com/Sywyar) が管理し、[AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) と付属の[プラグインリンク例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)を採用しています。ダウンロードした作品の著作権は原作者に帰属し、本プロジェクトは Pixiv と提携関係にありません。サービスの利用規約を守り、リクエスト頻度を適切に制御してください。
