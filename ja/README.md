# PixivDownloader ドキュメント

PixivDownloader は、Pixiv のイラスト、漫画、うごイラ、小説をパソコンやサーバーに保存するアプリです。デスクトップ画面、ブラウザー、Tampermonkey スクリプトから操作できます。ログインが必要な作品には、自分の Pixiv アカウントの Cookie を使用します。

## はじめて使う

1. [インストール](/ja/installation)で環境に合う配布パッケージを選び、全体を展開します。
2. [初回セットアップ](/ja/first-setup)でアプリの管理者アカウントと必要なプロキシを設定します。
3. [初回ダウンロード](/ja/first-download)で作品を 1 件保存し、保存先を確認します。

デスクトップ画面は Compose が既定で、Swing も選べます。簡体字中国語、繁体字中国語、英語、日本語、韓国語に対応しています。データベースは SQLite が既定です。PostgreSQL を使う場合は、対応プラグインとデータ移行が必要です。

## やりたいことから探す

| 操作 | ガイド |
|---|---|
| ブックマーク、フォロー中の新作、自分の作品を取得する | [クイック取得](/ja/quick-access) |
| 複数の作品 URL をまとめてダウンロードする | [URL 一括ダウンロード](/ja/batch-download) |
| 特定の作者の作品を取得する | [ユーザー一括ダウンロード](/ja/user-download) |
| Pixiv をキーワードで検索する | [検索ダウンロード](/ja/search) |
| 小説を保存・合本・翻訳・読み上げする | [小説](/ja/novel) |
| 保存した作品を閲覧・整理する | [作品ギャラリー](/ja/gallery) |
| 新作を定期的に取得する | [スケジュールタスク](/ja/scheduled-tasks) |
| Pixiv のページから操作する | [ユーザースクリプト](/ja/userscripts) |
| PixivBatchDownloader の今後のダウンロードを登録する | [PixivBatchDownloader インポート](/ja/pixiv-batch-downloader-import) |
| 保存先、同時実行数、プロキシなどを設定する | [設定リファレンス](/ja/configuration) |
| プラグインを追加・更新する | [プラグイン管理](/ja/plugin-management) |
| 外部通信や送信データを確認する | [ネットワークアクセス](/ja/network-access) |
| バックアップやデータ移行を行う | [ストレージ](/ja/storage) |
| ファイル名の変数や操作を確認する | [操作リファレンス](/ja/usage-guide) |
| 起動・接続・保存の問題を調べる | [よくある質問](/ja/faq) |

## 開発者向け

- プラグインを作る：[サードパーティプラグイン SDK](/ja/plugin-development)
- 本体をビルドする：[開発ガイド](/ja/development)
- バージョン別 API：[SDK ドキュメント](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)
- 開発パッケージ：[SDK Releases](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)

## バージョンと問い合わせ

[Releases](https://github.com/Sywyar/PixivDownloader/releases) で配布物を、[変更履歴](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md)で変更内容を確認できます。[Issue](https://github.com/Sywyar/PixivDownloader/issues) には、使用バージョン、OS、起動方法、再現手順、エラーを記載してください。Cookie、パスワード、トークンは公開しないでください。

ガイド中の `http://localhost:6999` は既定のローカルアドレスです。ポートや接続先を変更した場合は読み替えてください。各機能の画面は対応プラグインが有効な場合に利用できます。必須プラグインやデータベースの復旧が必要な場合は、起動時の案内を先に解決してください。

本プロジェクトは [Sywyar](https://github.com/Sywyar) が管理しています。ライセンスは [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) と[プラグイン向けリンク例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)です。Pixiv の公式アプリではありません。作品の著作権とサービスの利用条件を守り、過剰なリクエストを避けてください。
