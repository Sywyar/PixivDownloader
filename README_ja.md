# PixivDownloader

[简体中文](./README.md) | [繁體中文](./README_zh-Hant.md) | 日本語 | [한국어](./README_ko.md) | [English](./README_en.md)

> [!NOTE]
> この文書でいう「作品」には、イラスト、漫画、うごイラ、小説が含まれます。

### 小説・漫画などにも対応した Pixiv 作品向けローカル一括ダウンローダー

- 作品リンクから作品を一括ダウンロード
- ユーザー ID で作品を一括ダウンロード
- 内蔵検索プロキシを使って作品を一括ダウンロード
- シリーズリンク、またはシリーズ内の作品リンクを入力してシリーズ全体を一括ダウンロード
- Tampermonkey Userscript で Pixiv のページからイラスト、漫画、うごイラ、小説を取得。単一作品ページからの直接ダウンロードにも対応
- 高機能なイラスト・小説ギャラリー

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![GitHub Repo stars](https://img.shields.io/github/stars/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/stargazers)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](../../releases)

## 機能

> [!WARNING]
> **`*` が付いた機能は安定版ではまだ利用できません（ナイトリービルドのみ対応）。**

- 一括ダウンロード用 Web ページ：クイック取得、単一作品一括インポート、User モード、検索モード、シリーズモード
- クイック取得：保存済み Cookie を使い、自分のブックマーク（イラスト・小説、非公開を含む）、自分の作品（非公開を含む）、フォロー中の一覧、コレクションをワンクリックで読み込み、内容を掘り下げてダウンロードキューに追加
- ページ一括ダウンロード Userscript：検索結果、フォロー中のフィード、ランキングなどから作品を取得
- 閲覧体験を拡張するツールボックス（ダウンロード済み作品のマーク、Cookie のワンクリックインポート）
- 検索範囲の選択、フィルター、並べ替え、コレクションに対応した高機能なイラスト・小説ギャラリー
- 小説ギャラリーの本文全文検索（ローカル全文インデックスを使用。年齢区分・タグ・作者フィルターと組み合わせ可能）
- 統計ダッシュボード：概要カード、月別ダウンロード数の折れ線グラフ、ダウンロード数上位の作者、人気タグのワードクラウド。作者やタグをクリックすると絞り込み済みのギャラリー表示へ移動
- 重複候補の検出：知覚ハッシュ（dHash）で大幅に重複しているダウンロード済み画像を検出。しきい値の調整、作品単位・全体の範囲切り替え、手動スキャンによるバックフィルに対応
- `*` プラグイン管理ページ：すべてのプラグインを状態、取得元、バージョン、依存関係とともにカード一覧で表示。外部プラグインのライフサイクル操作にも対応（未公開）
- `*` プラグインマーケットページ：信頼済みリポジトリのプラグインを閲覧、検索、ページ送りしてインストール。公開 HTTPS の `repository.json` を入力し、発行者、接続先ホスト、公開鍵の完全なフィンガープリントを確認して第三者リポジトリを保存できます。インストール前には版を再解決し、サイズ、SHA-256、署名、パッケージ内 descriptor を検証します
- スケジュールタスク：一定間隔または Cron スケジュールでバックグラウンドから新しい作品を自動検出・ダウンロード。3 種類の取得元に対応
- メール・プッシュ通知：手動対応が必要なイベントをメールとプッシュで通知。通知の種類ごとに有効・無効を切り替え可能
- 小説のダウンロードとシリーズ合本（多階層目次と画像埋め込みに対応した TXT / HTML / EPUB）
- 小説 AI 翻訳（LLM の設定が必要）：小説またはシリーズ全体を指定した言語へ翻訳してローカルに保存。原文と翻訳の表示を切り替え可能
- 小説 AI 多役音声読み上げ（ベータ）：LLM が文ごとに話者を割り当て、キャラクターごとに固定音声で合成。追従ハイライト付きで再生し、分析結果をキャッシュして再利用可能

- アニメーション（Ugoira）を WebP に自動変換
- カスタムファイル名テンプレート（11 個の変数）
- ダウンロード状態の検証：古い DB レコードを自動削除し、ディスクから見つからないレコードを再構築して再ダウンロードを回避
- 複数ユーザー環境向けのクォータとレート制限
- ゲスト招待システム（年齢区分・タグ・作者ホワイトリスト）
- 多言語・ダークモード
- オンライン更新に対応したデスクトップ GUI（Swing + FlatLaf）

## スクリーンショット

> [!NOTE]
> スクリーンショットを撮影した一部の端末では HDR が有効になっているため、色の見え方が異なる場合があります。

### ライトモードのスクリーンショット（[英語版](./en-US/md/light-screenshot.md)）

### ダークモードのスクリーンショット（[英語版](./en-US/md/dark-screenshot.md)）

## クイックスタート

### ダウンロード

[Releases](../../releases) から最新バージョンをダウンロードしてください。

| 種類 | 説明 |
|---|---|
| `PixivDownload-*-win-x64-setup.exe` | Windows インストーラー。修復・変更・アンインストール、FFmpeg の任意インストールに対応。Douyin 以外の公式プラグインをすべて事前インストール |
| `PixivDownload-*-java.zip` | Java 標準パッケージ（クロスプラットフォーム）。Java 17 が必要。Windows インストーラーと同じデフォルトプラグイン構成で、Douyin は含まれません |
| `PixivDownload-*-full-offline.zip` | 完全オフラインパッケージ（クロスプラットフォーム）。Java 17 が必要。ユーザー向けの公式プラグインをすべて含み、Douyin にも対応 |

> コアシェルの `PixivDownload-*.jar` は内部ビルド用の入力であり、通常のユーザー向け添付ファイルとしては提供していません。単独で実行すると必須の外部 `download-workbench` プラグインがないため、リカバリー・修復モードになります。

Java 標準パッケージと完全オフラインパッケージは、使用前に**必ず完全に展開**してください。JAR だけを取り出してはいけません。起動スクリプトと `plugins/` ディレクトリの両方が必要です。外部の公式プラグインは起動時に作業ディレクトリの `plugins/` フォルダーから読み込まれます。

### 実行

```bash
# Windows インストーラー
PixivDownload.exe

# Java 標準 / 完全オフラインパッケージ（Windows）
run.bat

# Java 標準 / 完全オフラインパッケージ（Linux/macOS、Java 17 が必要）
sh run.sh

# 任意の引数
--no-gui    # GUI を無効にして CLI 専用モードで実行（サーバー / Docker）
--intro     # 起動時に製品紹介ページを開く
```

初回起動後、ウィザードに従ってセットアップを完了し、`http://localhost:6999/pixiv-batch.html` にアクセスしてダウンロードを開始してください。

### バックエンド設定のプロキシ経由で Web 版 Pixiv にアクセス（システムプロキシ不要）

バックエンドは設定したプロキシ（デフォルトは `127.0.0.1:7890`）経由で Pixiv にアクセスし、システムプロキシには依存しません。システムプロキシを有効にせず、ブラウザーで `pixiv.net` を直接開きたい場合（Userscript を使う場合など）は、内蔵のプロキシ自動設定（PAC）を利用できます。

OS またはブラウザーの「自動プロキシ設定スクリプト（PAC）URL」を `http://localhost:6999/proxy.pac` に設定してください（設定したポートに合わせてください。HTTPS を有効にすると `https://<domain>:<port>/proxy.pac` になります）。その後、Pixiv 関連ドメインだけが同じバックエンド設定のプロキシを経由し、それ以外は直接接続します。このエンドポイントはローカル専用です。ホットリロードを含むプロキシ変更も自動的に反映されるため、システムプロキシを何度も切り替える必要はありません。

ブラウザー・OS ごとの正確な設定場所（Firefox の `about:preferences#general`、Windows の `ms-settings:network-proxy` など）は、[設定 · 同じプロキシ経由で Web 版 Pixiv にアクセス](https://sywyar.github.io/PixivDownloader/#/ja/configuration)を参照してください。

---

## オンラインドキュメント

詳しいインストール手順、利用ガイド、設定リファレンス、開発ガイドについては、[オンラインドキュメント](https://sywyar.github.io/PixivDownloader/#/ja/)を参照してください。各章へすぐに移動できます。

**クイックスタート**

- [📥 インストールと起動](https://sywyar.github.io/PixivDownloader/#/ja/installation)
- [⚙️ 初回セットアップ](https://sywyar.github.io/PixivDownloader/#/ja/first-setup)
- [⬇️ 最初のダウンロード](https://sywyar.github.io/PixivDownloader/#/ja/first-download)

**機能ガイド**

- [⚡ クイック取得](https://sywyar.github.io/PixivDownloader/#/ja/quick-access)
- [📋 URL 一括ダウンロード](https://sywyar.github.io/PixivDownloader/#/ja/batch-download)
- [👤 作者一括ダウンロード](https://sywyar.github.io/PixivDownloader/#/ja/user-download)
- [🔍 検索ダウンロード](https://sywyar.github.io/PixivDownloader/#/ja/search)
- [📖 小説ダウンロード](https://sywyar.github.io/PixivDownloader/#/ja/novel)
- [🖼️ 作品ギャラリー](https://sywyar.github.io/PixivDownloader/#/ja/gallery)
- [⏰ スケジュールタスク](https://sywyar.github.io/PixivDownloader/#/ja/scheduled-tasks)
- [🧩 Userscript](https://sywyar.github.io/PixivDownloader/#/ja/userscripts)

**リファレンス**

- [⚙️ 設定](https://sywyar.github.io/PixivDownloader/#/ja/configuration)
- [🔌 プラグイン管理](https://sywyar.github.io/PixivDownloader/#/ja/plugin-management)
- [💾 保存の原則](https://sywyar.github.io/PixivDownloader/#/ja/storage)
- [❓ FAQ](https://sywyar.github.io/PixivDownloader/#/ja/faq)
- [🛠️ 開発](https://sywyar.github.io/PixivDownloader/#/ja/development)

---

## 免責事項

- 本プロジェクトは個人の学習・研究目的に限り使用してください。商用利用は禁止します。
- 本ツールでダウンロードしたコンテンツの著作権は原作者に帰属します。クリエイターの権利を尊重し、再配布や商用利用を行わないでください。
- 本ツールは利用者が自分で提供した Cookie、または利用者の許可を得て Tampermonkey Userscript から取得した Cookie を使って Pixiv にアクセスします。アカウントのリスクは利用者自身が負担してください。
- 本プロジェクトは Pixiv 公式とは一切関係ありません。本ツールの使用によって生じる結果について、利用者自身が責任を負うものとします。
- Pixiv サーバーに過大な負荷をかけないよう、ダウンロード間隔を適切に設定してください。

---

## 追加メモ

率直に言うと、このツールの multi モードはあまりおすすめしません。すべてのリクエストがサーバーのネットワーク IP を経由するためです。Cookie が異なっていても、大量のリクエストによって IP が ban される可能性があります。multi モードにログイン機能を追加することも検討していますが、それではプロジェクト本来の「シンプルさ」という方針に反してしまいます。現時点では、引き続きこのプロジェクトを改善していきます。

## 友好リンク

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**

シンプルさを重視し、バックエンドプログラムに依存したくない場合は、このスクリプトも試してみてください。

特徴：

- 多数のフィルター機能
- 広告除去、クイックブックマーク、画像ビューアーモードなどの便利な補助機能（Pixiv 補助プラグインとしても使えるかもしれません）
- サードパーティーツールに依存しないダウンロード（このプロジェクトとの最大の違いで、インストールが簡単です）
- 多言語対応

## 開発計画
