# ネットワークアクセスとサードパーティサービス

現在の PixivDownloader がアクセスし得る外部先、担当コンポーネント、用途、発動条件、既定状態をまとめます。固定された既定 URL と、管理者が設定できる動的な宛先を分けて考えてください。DNS、CDN、リダイレクト、一時 URL により、実際の IP や最終サブドメインは変わる場合があります。

?> 管理者が設定するカスタム URL は事前に列挙できません。AI、TTS、プッシュ、メール、Douyin は対応機能を設定して実際に呼び出したときだけ外部アクセスします。`notification` は有効化・起動後に公告インデックスを定期確認する例外です。

## ネットワークアクセスの概要

- 更新確認を有効にすると、起動準備後に GitHub Releases を確認します。頻度はキャッシュ間隔で制限されます。
- 製品紹介ページを開くとブラウザーが Google Fonts を読み込みます。バックエンドや Java のプロキシは経由しません。
- 公式プラグインマーケットは既定で有効ですが、起動時には取得しません。管理者がマーケットを開く、更新する、またはプラグインをインストールした時だけリポジトリへ接続します。
- PostHog 調査は通常ビルドではリリース有効フラグが `false` で、既定ではアクセスしません。

## コアと既定のリクエスト

| 所有者 | 宛先 | 用途と発動条件 |
| --- | --- | --- |
| アプリケーションホスト | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` と `.sig`（Nightly は `/releases/download/nightly/`） | 署名付き更新マニフェストを確認。`update.enabled` と `update.auto-check` が有効なときに実行。マニフェストは埋め込み Ed25519 公開鍵で検証し、最大 1 MiB / 16 KiB |
| アプリケーションホスト | 検証済みマニフェストの現在プラットフォーム用インストーラー URL | 更新が見つかり、ユーザーが明示的に開始した時だけダウンロード。署名内の SHA-256 とサイズに完全一致する必要がある |
| 製品紹介ページ | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | Noto Sans SC の CSS / フォントをブラウザーが取得。ホストプロキシは使わない |
| アプリケーションホスト | `https://www.pixiv.net/` | 初回セットアップまたは明示的な Pixiv 接続確認の疎通テスト。定期ハートビートではない |
| `notification` | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、`.sig`、および署名インデックスが示す `.../announcements/<message-id>/<locale>.html` | 公告インデックスと署名を検証し、未知またはダイジェスト変更の文書だけを取得。Cookie、作品、アカウント情報、ローカルパスは送らない |

`notification` は起動ごとに 0〜30 分のランダム待機後、およそ 6 時間ごと（±15%）に確認します。`ETag` / `Last-Modified`、429 の `Retry-After`、段階的なバックオフを使い、検証・通信失敗時は最後の信頼済みスナップショットを保持します。無効化またはアンインストールで停止します。

## Pixiv の取得と閲覧

アプリケーションホストが Pixiv HTTP トランスポートを提供し、呼び出し元は主に `download-workbench`、`novel`、ユーザースクリプトです。

| 所有者 | 宛先 | 用途 |
| --- | --- | --- |
| `download-workbench` | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php` | 作品、うごイラ、作者、シリーズ、検索、コレクション、フォロー新着、リクエストのメタデータ。認証が必要な場合は Pixiv Cookie を使う |
| `download-workbench` | `https://www.pixiv.net/ajax/illusts/bookmarks/add` | ダウンロード完了後の作品ブックマーク（有効化した場合のみ） |
| `novel` | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**` | 小説本文、シリーズ、作者の小説、ブックマーク、検索結果。プレビュー済み本文は所有者に束縛した短命チケットで再利用し、通常は同じ本文を再取得しない |
| `novel` | `https://www.pixiv.net/ajax/novels/bookmarks/add` | 小説のダウンロード完了後のブックマーク（有効化した場合のみ） |
| アプリ共通の画像トランスポート | HTTPS の `*.pximg.net`、サムネイルでは `embed.pixiv.net` | 原画、表紙、サムネイル、うごイラ ZIP、埋め込み小説画像。通常 Pixiv Referer を付け、URL は Pixiv API の応答から得る |
| ルートユーザースクリプト | `https://www.pixiv.net/ajax/**`、HTTPS の `*.pximg.net` | Pixiv ページ上で作品情報を読み取り、ブックマークまたは直接ダウンロード。ブラウザーの Pixiv ログイン状態を使う |
| ルートユーザースクリプト | 設定済み PixivDownloader URL（既定 `http://localhost:6999`） | ダウンロード送信、キュー、状態、SSE API。リモート URL では小説本文のローカルチケット取込みを無効にし、バックエンドが本文を取得 |

通常の Pixiv JSON は 4 MiB、小説シリーズ詳細・ページング応答は 1 MiB の上限があります。実際に読み取ったバイト数を常に検査し、超過時は中断します。

## `douyin` プラグイン

オンデマンドプラグインであり、既定の Windows インストーラーと Java 標準版には含まれません。インストール、有効化、利用のすべてが揃ったときだけアクセスします。

| 宛先 | 用途 |
| --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | ユーザー投稿、いいね、検索、音楽作品、コレクション、アカウント、作品詳細。Douyin Cookie とクエリを送る |
| `https://douyin.com` / `*.douyin.com`、`iesdouyin.com` / `*.iesdouyin.com`、`v.douyin.com` | 貼り付けた短縮 URL の解決。限定的なリダイレクト |
| `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com` | 動画、画像、表紙、ライブフォトのメディア取得。URL は API 応答から得る |

タスクの経路またはホストのグローバル経路を使います。プラグインを無効化・アンインストールすれば停止します。

## `ai` プラグイン

OpenAI 互換の `/chat/completions` に、翻訳・処理対象のテキスト、プロンプト、モデル、API キーを送ります。設定保存後の接続テストや AI 機能の実行時だけ発動します。既定プリセットには次があります。

| サービス | 既定ベース URL |
| --- | --- |
| OpenAI | `https://api.openai.com/v1` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` |
| xAI | `https://api.x.ai/v1` |
| Mistral | `https://api.mistral.ai/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| DeepSeek | `https://api.deepseek.com` |
| Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Zhipu | `https://open.bigmodel.cn/api/paas/v4` |
| Moonshot | `https://api.moonshot.cn/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

ベース URL は管理者が任意の互換サービスへ変更できます。API キーを消去、設定を無効化、または `ai` を無効化すると停止します。認証情報を含む要求は Cookie を保存せず、HTTP リダイレクトにも従いません。

## `tts` プラグイン

読み上げ本文、音声・モデル設定、サービス認証情報を送ります。プレビュー、音声一覧更新、接続テスト、実際のナレーション時だけ実行します。

| エンジン | 宛先 |
| --- | --- |
| Edge TTS | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`、音声一覧も同ホスト |
| Edge バージョン確認 | `https://edgeupdates.microsoft.com/api/products?view=enterprise` |
| Xiaomi MiMo | `https://api.xiaomimimo.com/v1/chat/completions` |
| Fish Audio | `https://api.fish.audio/v1/tts` |
| MiniMax | `https://api.minimax.io/v1/t2a_v2`（中国向けは設定変更可） |
| ElevenLabs | `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}` |
| Qwen | `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` |
| Doubao | `https://openspeech.bytedance.com/api/v1/tts` |
| VoxCPM / CosyVoice | 管理者設定の OpenAI 互換 `{base-url}/audio/speech` |

各エンジンは独自のベース URL を持てます。認証付き要求はリダイレクトに従わず、`tts`、エンジン、設定を無効化すれば停止します。

## `push` プラグイン

チャンネル有効化後、通知またはテスト送信時にタイトル・本文とチャンネル認証情報を送ります。

| チャンネル | 既定の宛先 |
| --- | --- |
| Bark | `https://api.day.app/push` |
| DingTalk | `https://oapi.dingtalk.com/robot/send?access_token=...` |
| Feishu | `https://open.feishu.cn/open-apis/bot/v2/hook/{key}` |
| WeCom | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` |
| Telegram | `https://api.telegram.org/bot{token}/sendMessage` |
| PushPlus | `https://www.pushplus.plus/send` |
| ServerChan | `https://sctapi.ftqq.com/{key}.send` または `https://{uid}.push.ft07.com/send/{key}.send` |
| カスタム Webhook | 管理者が入力した任意の HTTP(S) URL |

カスタム `http://` は暗号化されないため、信頼できる宛先だけを使ってください。チャンネルまたは `push` を無効化すれば停止します。

## `mail` プラグイン

テスト送信または通知時に SMTP へ接続し、SMTP 認証情報、宛先、件名、本文を送ります。既定の例は `smtp.163.com:465`、`smtp.qq.com:465`、`smtp.gmail.com:587`、`smtp.office365.com:587`、`smtp.mail.me.com:587` などです。任意の SMTP ホスト / ポートと個別 SOCKS プロキシを設定できます。設定消去、通知無効化、`mail` 無効化で停止します。

## プラグインマーケット、FFmpeg、スクリプト更新

| 所有者 | 宛先 | 発動条件 |
| --- | --- | --- |
| プラグインマーケット | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`、通常は GitHub Releases と CDN | マーケットを開く / 更新する、またはプラグインを明示的にインストール。署名、SHA-256、サイズを検証 |
| カスタムマーケット | 管理者が設定した HTTPS マニフェストとパッケージ | リポジトリを設定して有効化した場合だけ |
| FFmpeg インストーラー | `https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip` | GUI で自動インストールを明示的に選んだ場合だけ |
| Tampermonkey | `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | インストール済みスクリプトの更新確認 |
| オールインワン | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | そのスクリプトをインストールした場合だけ |

## 公式プラグインの任意調査（PostHog）

レイアウト調査は `download-workbench`、マルチモード継続調査は `multi-mode-decision-survey` が所有し、PostHog JavaScript SDK は `posthog` が提供します。通常のソース / フォークビルドではリリース有効フラグが `false` です。調査が有効な公式ビルドで、ユーザーが調査を開いたときだけ、ブラウザーが `https://layout-survey.sywyar.top` と `https://us.posthog.com` に接続します。送信されるのは回答、調査 ID、調査専用匿名 ID、イベント時刻、公開プロジェクトトークンなどで、Cookie、作品、ローカルパス、元のインストール ID は送信しません。ブラウザー要求なのでホストプロキシを経由しません。

`posthog` がない・無効な場合は調査が静かに利用できなくなります。すでに開いているページの JavaScript は更新まで残ります。

## 固定された公開先を追加しない公式プラグイン

`stats`、`duplicate`、`gallery`、`gui-swing`、`gui-compose` は主にローカルデータ、ファイル、デスクトップ文書、ウィンドウ、トレイ、テーマ資源を使います。現在の PixivDownloader への同一オリジン API を呼ぶことはありますが、第三者公開先への固定接続は追加しません。

## ローカル、同一オリジン、管理者定義の宛先

- GUI、Web、プラグインフロントエンドは現在のインスタンスの `/api/**`、静的リソース、SSE を呼びます。
- Ollama、LM Studio、VoxCPM、CosyVoice、ユーザースクリプトのバックエンドはローカルにもできます。URL をリモートへ変更すると、そのサービスがデータ受信者になります。
- 画像分類の `server.url`、Webhook、AI / TTS ベース URL、プラグインリポジトリ、Bark、SMTP、SOCKS、プロキシは管理者定義で、固定ドメインの完全な許可リストにはできません。
- `cors-js-runner.html` は操作者が入力した任意 URL を要求する開発者ツールで、通常ランタイムではありません。

## プロキシの適用範囲

ホストプロキシを設定しても全通信がそこを通るわけではありません。

- Pixiv、更新、FFmpeg、公式リポジトリ、一部プラグインはホストまたはタスク経路からプロキシを選ぶ
- AI、TTS、Douyin は機能 / タスクごとに直接接続を選べる場合がある
- `direct-strict` のカスタムリポジトリは意図的にグローバルプロキシを迂回する
- ユーザースクリプト、Google Fonts、PostHog はブラウザー通信で Java プロキシを迂回する
- SMTP は Mail 固有の設定と任意の SOCKS プロキシを使う

## 開発、ビルド、リリース時

インストール済みアプリの通常ランタイムとは別に、Git / リリーススクリプトは GitHub API と Releases、GitHub Actions は CI / Artifact / Release、Maven は `https://repo.maven.apache.org/maven2`、npm は現在のロックファイルのレジストリ、Docker は設定済み OCI レジストリと Debian パッケージ源、Windows CI は Chocolatey 源へ接続します。ミラー、プロキシ、パッケージマネージャー設定により最終先は変わるため、ソースだけから完全な固定ドメイン一覧は作れません。

## リンクと URL 参照

GitHub、Releases、オンラインドキュメント、Tampermonkey、ライセンスへのリンクは、クリックまたはブラウザーによるリソース読み込み時だけ通信を発生させます。XML 名前空間、POM のスキーマ URL、ライセンス文やサンプルの URL は自動通信先ではありません。

Web UI の外部 HTTP(S) リンクは、ローカル公告 HTML や調査 HTML 内のリンクも含め、サイト全体の確認画面を経てからブラウザーが直接接続します。キャンセル時は通信しません。同一オリジンのアプリリンクは通常どおり開きます。
