# ネットワークアクセスと外部サービス

各機能の接続先、送信するデータ、プロキシ経路、停止方法を説明します。前半はインストール済みアプリケーションの**実行時リクエスト**です。ビルド、テスト、CI、コミュニティ投稿の通信は[開発・ビルド・公開処理のネットワークアクセス](#開発・ビルド・公開処理のネットワークアクセス)に分けて記載しており、日常的なアプリケーションの実行では発生しません。

?> コードに固定されたデフォルトアドレスと、制御された動的アドレスの範囲を記載しています。外部サービスは DNS、CDN、リダイレクト、一時ダウンロードリンクを経由してファイルを配信する場合があります。管理者が指定するカスタム URL は実際の設定に依存します。

## 実行時の概要とプロキシ経路

- **接続の条件**：AI、翻訳、TTS、プッシュ通知、メール、CAPTCHA、Douyin は、有効かつ呼び出された場合だけ通信します。`notification` プラグインは有効にすると署名付きのお知らせ索引をバックグラウンドで定期取得します。オンライン更新と自動確認が有効なら、ホストは起動準備完了後に GitHub Releases を確認します。デスクトップとヘッドレスサービスは、準備完了後、登録済み作品の不足メタデータを必要に応じてバックグラウンドで補完します。
- **プラグインマーケット**：`plugin-catalog.enabled`、公式・コミュニティリポジトリはデフォルトで有効ですが、アプリケーションの起動だけではリモートのマニフェストを取得しません。管理者ページでのプラグイン更新確認、マーケットの表示・更新、リポジトリのプレビュー、インストール、撤回情報の手動更新時にだけ、対応するリポジトリへ接続します。
- **PostHog アンケート**：レイアウト調査（`download-workbench`）とマルチユーザーモード調査（`multi-mode-decision-survey`）の公開パラメーターは、プラグインのソースの `posthog-config.js` にあります。通常のソースや fork のビルドで生成する `release-activation.js` はデフォルト `false` で、PostHog に接続しません。
- **外向きプロキシの経路**：
  - Pixiv、オンライン更新、FFmpeg の自動ダウンロード、公式・コミュニティプラグインリポジトリ、サーバー側 CAPTCHA 検証、お知らせの同期は、ホストまたはタスク単位のプロキシ設定を使います。
  - AI、翻訳、TTS、Douyin は機能単位またはタスク単位で直接接続・プロキシを選べます。`direct-strict` のカスタムプラグインリポジトリは直接接続を強制します。
  - SMTP は `mail` プラグイン独自の接続と任意の SOCKS プロキシを使います。PostgreSQL JDBC は対象データベースサーバーに直接接続し、HTTP プロキシを経由しません。
  - ブラウザーの同一オリジン API、紹介ページの Google Fonts、ブラウザー側 Web Speech API、CAPTCHA のフロントエンドウィジェット、PostHog、ユーザースクリプトはブラウザーから直接通信し、Java バックエンドのプロキシを経由しません。

## コアホストとデフォルトのリクエスト

- **同一オリジンの認証と国際化**：ログインページは `/api/auth/check`、`/api/auth/login`、`/api/auth/invite-redeem` でセッション確認と認証送信を行います。戻り先は同一オリジン内のパスだけを受け付けます。フロントエンドは `/api/i18n/meta`、`/api/i18n/messages/{namespace}`、同一オリジンの `i18n-static/` から辞書を読み込みます。1 回のタイムアウトは 10 秒で、不足する辞書は最大 3 回、1.5、5、15 秒後に再試行します。外部の翻訳サービスには接続しません。
- **ローカル GUI チャネル**：デスクトップモードは `http://127.0.0.1:{ランダムポート}/api/gui/**` で状態をポーリングして操作を送信し、バックエンド生成の GUI token を付けます。ローカルへの直接接続を強制し、プロキシ、リダイレクト、転送ヘッダーを拒否します。`--no-gui` ではこのポートを作成しません。
- **外部リンクの確認**：アプリケーションの Web ページで外部 HTTP(S) リンクをクリックすると、まず接続先の確認ダイアログが表示され、確認後にブラウザーで開きます。

| 所有者 | 接続先 | 用途と送信内容 | 接続条件と停止方法 |
| --- | --- | --- | --- |
| アプリケーションホスト（更新確認とダウンロード） | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` と `.sig`、nightly の同名マニフェスト、検証済みマニフェストのインストーラー URL（GitHub Release CDN のリダイレクトを含む） | 更新確認と組み込み Ed25519 署名の検証。マニフェスト・署名の上限は 1 MiB / 16 KiB。利用者が更新を確認してからインストーラーを取得し、上限 500 MiB、正確なサイズと SHA-256 を検証。現在のバージョン、Cookie、認証情報は送信しない | `update.enabled` と `update.auto-check` はデフォルトで有効。nightly が有効なら 2 系統を独立確認。ホストのプロキシを使い、公開ネットワークの HTTPS リダイレクトを最大 5 回許可。上記設定の無効化で停止 |
| アプリケーションホスト（紹介ページのフォント） | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | ブラウザーで Noto Sans SC を読み込み、標準的なブラウザー接続情報だけを公開 | `intro.html` を開くと発生。バックエンドのプロキシは経由せず、ドメインをブロックするとローカルフォントに自動切り替え |
| アプリケーションホスト（疎通確認） | `https://www.pixiv.net/` | バックエンドから Pixiv への接続を確認。Pixiv Cookie は付けない | 初期設定ガイドまたは手動の接続確認時。バックグラウンドの定期疎通は行わない |
| `notification` プラグイン | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、隣接する `.sig`、`.../announcements/<message-id>/<locale>.html` | 公式 Ed25519 署名、有効期限、増加する連番、本文 SHA-256 を検証。未取得またはダイジェストが変化したお知らせだけ、多言語の制限付き HTML を取得してローカル保存。Cookie やアプリケーション認証情報は送信しない | 起動後に 0–30 分のランダム待機を経て、以降 6 時間ごと（±15%）に確認。`ETag`/`Last-Modified` の条件付きリクエストとバックオフに対応。ホストのプロキシを使い、`notification` の無効化・アンインストールで停止 |

## Pixiv のダウンロード、閲覧、インポート

| 所有者 | 接続先 | 用途と送信内容 | 接続条件と停止方法 |
| --- | --- | --- | --- |
| アプリケーションホスト（共通クエリーとサムネイル） | `https://www.pixiv.net/ajax/user/{id}?lang=zh`、HTTPS `*.pximg.net`、`embed.pixiv.net` | `/api/pixiv/user/{id}/meta` はリクエストで渡された取得用認証情報を使用。`/api/pixiv/thumbnail-proxy` は上記画像ドメインだけに接続し、Cookie なし、リダイレクトなし、1 回最大 100 MiB | ページが対応するローカル API を呼ぶと発生。該当プレビューの停止で回避可能 |
| アプリケーションホスト（メタデータ補完） | `https://www.pixiv.net/ajax/illust/{id}`、`/ajax/novel/{id}` | 登録済み作品ディレクトリの旧 `.meta.json` とローカルメタデータで先に補完し、なお不足する場合だけ Cookie なしで取得。1 回最大 4 MiB。404 は `data/backfill/unreachable.json` に記録し、年齢制限の応答は自動再試行を保留 | デスクトップ・ヘッドレスの起動準備完了後にバックグラウンドで自動補完、またはデスクトップツール・独立 CLI から手動実行。保存済みの全体プロキシを使い、ツールではその回だけ上書き可能。ツールページまたはサービス停止でキャンセル |
| `download-workbench` プラグイン | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php`、`/ajax/illusts/bookmarks/add` | イラスト・うごイラ・作者・シリーズ・検索・ブックマーク・フォロー新着・リクエストのメタデータ取得。通常 JSON は最大 4 MiB、総ページ数不明のフォロー新着は最大 1,000 ページ。自動ブックマーク有効時はダウンロード完了後に登録。必要に応じて Pixiv Cookie を使用 | プレビュー、クイック取得、ダウンロード、スケジュールタスク、ブックマーク数絞り込みの追加照会（同時最大 6 件）、シリーズ紹介文の追加照会で発生。プラグインまたは自動ブックマークの無効化で対応する通信を停止 |
| `novel` プラグイン | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**`、`/ajax/novels/bookmarks/add` | 小説本文、シリーズ（応答最大 1 MiB）、作者の小説、ブックマーク、検索結果、ダウンロード後の自動ブックマーク。プレビュー直後のダウンロードは、owner と認証情報に紐付く 256 KiB の短期・一回限りのチケットで検証済み応答を再利用 | 小説プレビュー、合本、検索、スケジュールタスクで発生。`novel` の無効化で停止 |
| アプリケーションホスト（共通画像転送） | HTTPS `*.pximg.net`（サムネイルは `embed.pixiv.net` も許可） | 元画像、表紙、サムネイル、うごイラ ZIP、小説の埋め込み画像（最大 512 マッピング）を取得し、Pixiv Referer を付与。非 2xx のエラー本文はバッファーしない | 作品のダウンロード、表紙・サムネイル・小説ファイルの生成時 |
| ルートのユーザースクリプト | `https://www.pixiv.net/ajax/**`、`*.pximg.net`、設定したバックエンドアドレス（デフォルト `http://localhost:6999`） | Pixiv ページのブラウザーログイン状態で作品取得・ローカルダウンロード。バックエンドのダウンロード、キュー、コレクション、小説状態、`/api/i18n/meta`（3 秒タイムアウト）、ツールボックスのセッション・Cookie 同期 API を呼ぶ。小説チケット API には Pixiv Cookie を送らない | Pixiv ページでの操作時にブラウザー・スクリプト管理拡張から直接接続。スクリプトの無効化で停止 |
| `pixiv-batch-downloader-import` プラグインとスクリプト | ローカルの `/api/pixiv-batch-downloader-import/token`、`/api/pixiv-batch-downloader-import`、`/api/pixiv-batch-downloader-import/history`、ブラウザー同一オリジンの `/ajax/illust/{id}`、`/ajax/novel/{id}`。デスクトップ履歴インポートはホスト経由で `https://www.pixiv.net/ajax/{illust,novel}/{id}`、ほかにスクリプト更新元 | 自動インポートはローカルファイルのパスとメタデータを solo のループバックサービスへ送信。ブラウザー履歴インポートは同一オリジン Cookie でブラウザー内からメタデータを取得し、ローカルで検証。デスクトップ履歴インポートは任意入力のその回限りの Cookie で取得。いずれも元の作品ファイルはアップロードしない | プラグインとスクリプトをインストール・有効化した場合、または履歴インポートを明示実行した場合だけ発生。スクリプト・プラグインの無効化、タスクのキャンセルで停止 |

SDK の `DownloadTasks.submit` や管理者の `POST /api/download/tasks` で送信したタスクも、対応するプラグインでメタデータとメディアを取得します。ワークベンチは表示中、同一オリジンの `GET /api/download/tasks` を 2 秒ごとにポーリングします。終了状態は 5 分間保持します。

## サードパーティー・オプション機能プラグイン

### `douyin` プラグイン（必要に応じてインストールするサードパーティー製）

デフォルトの配布パッケージには含まれず、手動でインストール・有効化し、Douyin の操作を実行した場合だけ接続します。

- `https://www.douyin.com/aweme/v1/web/**`：ユーザー作品、いいね、コレクション、検索、音楽、ブックマーク、アカウント、作品詳細を取得します。設定した Douyin Cookie、Cookie から抽出した UIFID、ローカル計算の `x-secsdk-web-signature` を付けます。
- HTTPS `*.douyin.com`、`*.iesdouyin.com`（`v.douyin.com` など）：制限付きのリダイレクトで短縮リンクを解決します。
- HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`：動画、画像集、表紙、ライブフォトを取得します。

経路は「タスク専用プロキシ → 取得元のデフォルト経路 → ホスト全体のプロキシ」の順で選びます。タスクの停止またはプラグインの無効化で停止します。

### `ai` と `translation` プラグイン

AI プラグインは OpenAI 互換プロトコルを使います。接続テスト、小説翻訳、複数キャラクター分析では、選択したベースアドレスの `/chat/completions` にテキスト、プロンプト、モデル名、API Key を送信します。小説翻訳には最大 1,000 件の用語対応表を添えます。デスクトップ設定でモデル一覧を取得すると、編集中のベースアドレスの `/models` に GET を送ります。最初は匿名で、401/403 かつキーが入力済みの場合は Bearer キーを付けて 1 回再試行します。`https://api.anthropic.com/v1` では [Anthropic のプロトコル](https://platform.claude.com/docs/en/api/models/list)に従い、`anthropic-version`、`x-api-key` と `after_id` によるページ分割を使います。すべてのリクエストで Cookie を保存せず、リダイレクトを追跡しません。

| AI プリセット | デフォルトのベースアドレス | AI プリセット | デフォルトのベースアドレス |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | 智譜 | `https://open.bigmodel.cn/api/paas/v4` |
| Anthropic 互換 | `https://api.anthropic.com/v1` | Moonshot | `https://api.moonshot.cn/v1` |
| Gemini 互換 | `https://generativelanguage.googleapis.com/v1beta/openai` | 豆包 / 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` |
| xAI | `https://api.x.ai/v1` | Tencent Hunyuan / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| Mistral | `https://api.mistral.ai/v1` | Baidu Qianfan / ERNIE | `https://qianfan.baidubce.com/v2` |
| Groq | `https://api.groq.com/openai/v1` | iFlytek Spark | `https://spark-api-open.xf-yun.com/v1` |
| DeepSeek | `https://api.deepseek.com` | MiniMax | `https://api.minimax.cn/v1` |
| Alibaba Cloud Bailian / Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenRouter / SiliconFlow | `https://openrouter.ai/api/v1`、`https://api.siliconflow.cn/v1` |
| Ollama（ローカル） | `http://localhost:11434/v1` | LM Studio（ローカル） | `http://localhost:1234/v1` |

`translation` プラグインは手動・自動翻訳の開始時に選択したエンジンを呼び出します。AI は上表の設定を使います。有道は `https://openapi.youdao.com/api` にテキスト、アプリケーション ID、ローカル計算の v3 署名を送り、アプリケーション秘密キーそのものは送りません。LibreTranslate は管理者設定の `{libretranslate-url}/translate` に接続し、任意の `api_key` を付けます。有道と LibreTranslate は接続タイムアウト 30 秒、読み取りタイムアウト 120 秒、1 回最大 1,000 Unicode 文字、応答上限 1 MiB で、`translation.use-proxy` によりアプリケーションのプロキシを使うか選びます。

### `tts`、`push`、`mail` プラグイン

| プラグイン | サービス / チャネル | デフォルトの接続先 |
| --- | --- | --- |
| `tts` | ブラウザーエンジン | ブラウザーからシステム・ブラウザーの Web Speech API を直接呼び出す。バックエンドは経由しない |
| `tts` | Edge TTS とバージョン検出 | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` と同ホストの `/voices/list`。ハンドシェイク 403 によるバージョン更新時は `https://edgeupdates.microsoft.com/api/products?view=enterprise` |
| `tts` | クラウド・自前の TTS | Xiaomi MiMo `https://api.xiaomimimo.com/v1/chat/completions`、Fish Audio `https://api.fish.audio/v1/tts`、MiniMax `https://api.minimax.io/v1/t2a_v2`（中国国内は `https://api.minimaxi.chat/v1/t2a_v2`）、ElevenLabs `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`、Alibaba Cloud Bailian `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`（国際リージョンは `dashscope-intl.aliyuncs.com`）と応答の一時音声 URL、豆包 `https://openspeech.bytedance.com/api/v1/tts`、VoxCPM / CosyVoice `{base-url}/audio/speech`（VoxCPM は `/models` も照会） |
| `push` | プッシュ通知チャネル | Bark `https://api.day.app/push`（自前にも変更可能）、DingTalk `https://oapi.dingtalk.com/robot/send`、Feishu `https://open.feishu.cn/open-apis/bot/v2/hook/{key}`、WeCom `https://qyapi.weixin.qq.com/cgi-bin/webhook/send`、Telegram `https://api.telegram.org/bot{token}/sendMessage`、PushPlus `https://www.pushplus.plus/send`、Server 酱 Turbo `https://sctapi.ftqq.com/{key}.send`、Server 酱³ `https://{uid}.push.ft07.com/send/{key}.send`、カスタム Webhook URL |
| `mail` | SMTP プリセットとカスタム設定 | NetEase `smtp.163.com:465` / `126.com` / `yeah.net` / `qiye.163.com`、Tencent `smtp.qq.com:465` / `exmail.qq.com:465`、Sina `smtp.sina.com:465`、Gmail `smtp.gmail.com:587`、Outlook `smtp-mail.outlook.com:587`、Microsoft 365 `smtp.office365.com:587`、iCloud `smtp.mail.me.com:587`、Yahoo `smtp.mail.yahoo.com:465`、Alibaba Mail `smtp.qiye.aliyun.com:465`、またはカスタム SMTP と任意の SOCKS プロキシ |

認証情報付きの TTS・Push HTTP リクエストは Cookie を保存せず、リダイレクトを追跡しません。対応プラグインの無効化、チャネルの無効化、認証情報の消去で接続を停止できます。

### プラグインマーケット、FFmpeg、データベース、ページエディター、CAPTCHA

- **プラグインマーケットと撤回確認**：
  - 公式リポジトリは `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`（nightly は `nightly-manifest.json`）、必要に応じた履歴分割ファイル `history/<pluginId>-<sha256>.json`、バージョンの Release にあるマーケット文書・スクリーンショットの添付ファイル、パッケージ URL を読み込みます。
  - コミュニティリポジトリは `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/generated/current.json`、世代ディレクトリの分割ファイル、撤回リスト、審査記録、署名済み Release ファイルを読み込みます。
  - カスタムリポジトリは、管理者が取り込んだ `repository.json`（最大 64 KiB）と、それが宣言する `manifest-v1` / `paged-v2` エンドポイント、`revocations.json`、`repository-update.json` を読み込みます。
  - インストール済みプラグインの更新結果は 5 分、一部失敗時は 30 秒キャッシュします。互換性のある旧版の検索はプラグインごとに最大 10 ページ、共有の時間枠は 10 秒です。`plugin-catalog.enabled` または対象リポジトリのスイッチを無効にすると停止します。
- **FFmpeg の自動インストール**：デスクトップの状態ページからのダウンロード、または Windows インストーラーで FFmpeg タスクを選択すると、`https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`、`.sig`、対象プラットフォームの `ffmpeg-*.zip` を取得し、公式 Ed25519 署名、サイズ、SHA-256 を検証して展開します。デスクトップはホストのプロキシ、Windows インストーラーは現在のユーザーのシステム HTTP/HTTPS プロキシを優先します。
- **ユーザースクリプトの更新**：リポジトリからインストールした個別スクリプトは Tampermonkey が `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` にアクセスします。バックエンドからインストールしたものは、そのバックエンドの `/api/scripts/*.user.js` を指します。
- **公式 PostHog 調査プラグイン**：公式配布ビルドで有効化され（`release-activation.js` が `true`）、管理者が有効な調査を含むサイト内メッセージを開くか、ダウンロードページのレイアウト調査を起動した場合だけ、ブラウザーが `https://layout-survey.sywyar.top` と `https://us.posthog.com` に直接接続します。調査の範囲に限定して導出した匿名識別子と回答だけを送り、インストール UUID、アカウント、Cookie、パスは送りません。`posthog` プラグインの無効化で停止します。
- **PostgreSQL データベースプラグイン**：管理者設定の `database.jdbc-url` に JDBC で接続し、データベースの認証情報、業務データ、専用データベースのデータを送信します。デフォルトポートは 5432、接続事前確認のタイムアウトは 10 秒です。対象サーバーへ直接接続し、HTTP プロキシは経由しません。[設定リファレンス](/ja/configuration#主データベースとエンジンの切り替え)を参照してください。
- **固定の外部公開ネットワーク接続先を持たないプラグイン**：`gallery`、`gallery-tools`、`gui-compose`、`gui-swing`、`page-editor`（GrapesJS と CodeMirror をローカル同梱し、CDN とテレメトリーは無効）自体は、現在のインスタンスの同一オリジン API だけにアクセスし、固定の外部公開サービスへはリクエストしません。
- **`captcha` 操作 CAPTCHA プラグイン**：
  - **ALTCHA（SHA-256 / PBKDF2）、文字 CAPTCHA（Hutool）、スライドパズル（Tianai）**：現在のインスタンスとブラウザー内で完結し、外部サービスには接続しません。
  - **Cloudflare Turnstile**：関連付けて公開すると、ブラウザーが `https://challenges.cloudflare.com/turnstile/v0/api.js` と iframe を読み込みます。送信時はサーバーが管理された HTTP 経由で `/turnstile/v0/siteverify` に秘密キーとチケットを送信します。アプリケーションのプロキシを引き継ぎ、リダイレクトと Cookie は禁止します。
  - **Google reCAPTCHA v2**：関連付けて公開すると、ブラウザーが `https://www.google.com/recaptcha/api.js?render=explicit` と `www.gstatic.com`、`recaptcha.google.com` を読み込みます。送信時はサーバーが管理された HTTP 経由で `https://www.google.com/recaptcha/api/siteverify` に秘密キーとチケットを送信します。コンポーネントを除去してページを再公開すると、外部リクエストが停止します。

## 開発・ビルド・公開処理のネットワークアクセス

以下は開発者や CI がソースのビルド、依存関係の解決、テスト、パッケージ作成、署名、公開、コミュニティ投稿ツールを実行したときだけ発生します。**インストール済みクライアントの実行時には、これらのリクエストは発生しません。**

| 工程 | 接続先と用途 |
| --- | --- |
| Maven / Gradle / sbt と npm の依存関係解決 | Maven Wrapper と Java モジュールは、設定された Maven リポジトリ・ミラー（デフォルト `https://repo.maven.apache.org/maven2`、`https://repo1.maven.org/maven2`、`https://plugins.gradle.org`）からビルドツールと依存関係（Tink、PostgreSQL/SQLite/H2 JDBC、Hutool/Tianai/ALTCHA、Cupertino、WebP/JSON Schema/JCS、sbt-launch など）を取得。フロントエンドの検証とプラグインリソースのビルドでは、ロックファイルの npm レジストリ（現在は `https://registry.npmmirror.com`、Web リソース保守時に `https://registry.npmjs.org` を使う場合あり）から依存関係を取得 |
| ブラウザーとデータベースの結合検証 | Playwright のブラウザーインストールは `cdn.playwright.dev` / `playwright.download.prss.microsoft.com` から Chromium を取得。`page-editor-acceptance.mjs` の CAPTCHA 検証を明示実行すると、公式の公開テストキーで Cloudflare Turnstile と Google reCAPTCHA のテストエンドポイントに接続。PostgreSQL コンテナー検証は Docker の `postgres:17` を取得し、`PIXIV_TEST_POSTGRES_URL` の隔離データベースだけに接続 |
| メンテナー一覧、ライセンス、ローカルパッケージ作成スクリプト | アプリケーションリソースのビルド時に、GitHub API（`api.github.com/repos/Sywyar/PixivDownloader`）と `avatars.githubusercontent.com` から許可リスト内の貢献者情報とアバターを取得。システム・環境の HTTP(S) プロキシに従い、タイムアウトは 30 秒、オフラインビルドでは自動スキップ。ライセンス保守スクリプトは必要に応じて `spdx/license-list-data` を参照。`package-local.ps1` のオフラインパッケージ作成は `ffmpeg-stable` のファイルを取得して署名検証。`package-installer-with-plugins.ps1 -PluginSource Catalog` は公式プラグインリポジトリから署名付きマニフェストとパッケージを取得 |
| SDK の検証、公開、リポジトリ間の Pages ビルド | `sdk-legacy-plugin.mjs` は固定の `gui-swing-v1.0.2` ファイルを取得してバイナリー互換性を検証。`sdk-consumer.mjs` は `Sywyar/PixivDownloader-Plugin-Douyin.git` の固定コミットを取得して隔離した利用側を検証。SDK 公開ワークフローは Sonatype Central Portal に 5 つの SDK Maven 座標を公開し、`Sywyar/PixivDownloader-Plugin-SDK` に署名付き Release を作成して Javadoc GitHub Pages をビルド。開発者が SDK プロジェクトで初めて `sdk-prepare` / `sdk-run` を実行すると、SDK Release から固定の実行パッケージを取得して `~/.cache/pixivdownloader-sdk/` にキャッシュ |
| 主リポジトリの CI、Gate Checks、Release、お知らせの署名更新 | GitHub Actions が Quality Gate、配布パッケージ E2E、署名付きプラグイン公開、`Gate Checks` を実行。GitHub API と App token により開いている PR に必須チェックを発行。`PixivDownloader-Remote-Content` の定期ワークフローは `announcements/index.json.sig` を確認して署名を更新し、Pages デプロイを起動 |
| コミュニティプラグイン投稿ウィザード、隔離ビルド、審査と公開 | 投稿入口 `irm https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1 \| iex` が署名済みの `submission-channel.json` と固定コミットのツールを取得。`no_proxy` → `https_proxy` / `all_proxy` → Windows システムプロキシの順で経路を解決し、1 ファイル 60 秒、最大 3 回試行。GitHub CLI / Git でコミュニティリポジトリと投稿者の fork にアクセスし、候補 Release のファイルと文書画像をアップロード。コミュニティ CI は管理された Squid プロキシコンテナーで承認済み Maven / Gradle ソースからだけ依存関係を事前取得し、ネットワークを切断したコンテナーでプラグインを再ビルド。GitHub Actions OIDC / Sigstore（`fulcio.sigstore.dev`、`rekor.sigstore.dev`、`tuf-repo.github.com`）でアーカイブ証明を生成し、審査・マージ・撤回リストの署名更新を実行 |
