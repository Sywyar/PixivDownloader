# プラグイン管理

上部ナビゲーションの「**プラグイン**」ページでは、管理者が全プラグインの状態を確認し、外部プラグインのライフサイクルを操作できます。一般ユーザーには入口がなく、直接アクセスも拒否されます。

任意プラグインの設定レベルの有効 / 無効は [設定](/ja/configuration) の `plugins.<plugin id>.enabled` で行います。`download-workbench` は必須外部プラグインで無効化できません。

## 実行セキュリティ

署名が証明するのは発行者とアーティファクトの完全性です。安全性の審査や実行権限の付与を意味しません。

すべての外部プラグインは `plugin.properties` の `pixiv.execution-mode` を明示し、次のいずれかを指定します。

| 値 | 実行場所 | 境界 |
| --- | --- | --- |
| `host-process-full-trust` | ホスト JVM | ホストプロセスのファイル、ネットワーク、OS 権限を継承 |
| `declarative-process` | 独立 worker JVM | 制限付きプロトコルを通じて宣言的なルートと capability を公開 |

値がない、空、または未知の場合は、プラグインコードを実行する前に拒否します。worker はホストと同じ OS アカウントを使うため、プロセス、プロトコル、リソースの限定的な分離であり、完全な OS サンドボックスではありません。現在、OS サンドボックス provider や、それを必須にする JVM スイッチはありません。本番モードはディレクトリ形式の `declarative-process` を拒否します。明示的な開発モードでは `host-process-full-trust` に降格し、状態とログに実際のモードを表示します。

実行境界を越えて信頼が自動的に拡大することはありません。発行者が同じでも、`declarative-process` から `host-process-full-trust` への更新には管理者の再確認が必要です。SDK のメジャーバージョン変更や信頼の取り消し後も再確認します。ホストが実際に管理者権限で動作している場合、full-trust プラグインもその権限を継承し、管理ページに警告が表示され続けます。

worker の既定値は heap 128 MiB、metaspace 128 MiB、direct memory 64 MiB で、OOM 時に終了します。初期化、コマンド、終了のタイムアウトは 10,000 / 5,000 / 2,000 ms です。異常終了後の再起動は最大 3 回、バックオフは 500 ms から最大 10,000 ms です。stderr は最大 1 MiB 読み取り、末尾 16 KiB を保持します。各 worker は実行中 1 件と待機中 1 件だけを許可します。終了時は、復旧を試す前に対象プラグインのルートと capability を取り下げます。

JVM 起動前に `pixivdownload.plugin-worker.*` の `initialize-timeout-ms`、`command-timeout-ms`、`shutdown-timeout-ms`、`restart-attempts`、`restart-initial-delay-ms`、`restart-max-delay-ms`、`stderr-max-bytes` で対応する値を変更できます。

### パッケージ受け入れ上限

既定値はアーカイブ 192 MiB、48,000 エントリ、実際の展開合計 672 MiB、単一エントリ 64 MiB、descriptor 1 MiB、圧縮比 200（64 KiB 以上のエントリだけを検査）、エントリ名 1,024 文字、パス深度 64 です。次の JVM プロパティに正の整数を指定して変更できます。

- `pixivdownload.plugin.package.max-archive-bytes`
- `pixivdownload.plugin.package.max-entries`
- `pixivdownload.plugin.package.max-total-uncompressed-bytes`
- `pixivdownload.plugin.package.max-entry-uncompressed-bytes`
- `pixivdownload.plugin.package.max-descriptor-bytes`
- `pixivdownload.plugin.package.max-compression-ratio`
- `pixivdownload.plugin.package.max-entry-name-length`
- `pixivdownload.plugin.package.max-entry-depth`

不正な値は既定値へ戻らず、プラグインランタイムの初期化を失敗させます。

## プラグインの種類

- **組み込み**：アプリにコンパイル済み。通常このページでホット切り替えできません
- **必須外部**：`download-workbench`。ダウンロードページ、API、キュー、ユーザースクリプト、Pixiv プロキシ、スケジュールホストを提供します。不足・破損・互換性不一致・検証失敗時はリカバリーモードになります
- **公式任意外部**：配布セットの `gui-compose`、`gui-swing`、`gallery-tools`、`posthog`、`gallery`、`novel`、`notification`、`multi-mode-decision-survey`、`push`、`mail`、`tts`、`ai`。ない場合は自分の機能だけがなくなり、通常はリカバリーになりません。Douyin はカスタムリポジトリまたはローカルパッケージから導入する一般のサードパーティプラグインです
- **未インストールの必須プラグイン**：プレースホルダーとして表示され、追加できます

## 表示される情報

各カードに名前、組み込み / 外部 / 未インストールの区分、バージョン、状態、外部プラグインの実行フェーズ、`core-api` 要件、依存関係、診断情報が表示されます。上部にはインストール済み、有効、外部、必須の集計と検索・絞り込みがあります。

## 実行時操作

管理対象の外部プラグインには、状態に応じて次の操作が表示されます。

| 操作 | 意味 |
| --- | --- |
| Load | アンロード済みプラグインを再登録 |
| Start | サービスを起動 / 再構築 |
| Quiesce | 新規要求を止め、実行中の処理を排出 |
| Stop | サービスを停止 |
| Unload | 停止してレジストリから削除 |
| Reload | 停止後に再起動 |

カード上部のスイッチは有効化 / 無効化（開始 / 停止）に対応します。組み込みプラグインはボタンがなく、必須プラグインは無効化できません。成功後は状態と上部ナビゲーションが更新されます。

## ローカルプラグインパッケージのインストール

管理者はローカルの `.jar` または対応 `.zip` を選び、必要なら detached `.sig` を添えて外部プラグインをインストールできます。

- 任意 URL のインストールはできません。オンライン取得は [Web プラグインマーケット](#web-プラグインマーケット) を使います
- 署名がある場合は対象 artifact と一致し、適用される信頼ルートで検証できなければなりません。未署名は `LOCAL_UPLOAD / UNSIGNED_ALLOWED` として記録されます。ローカルアップロードは独自の信頼ルートを追加しません
- 非公式ローカルパッケージは本番モードでもインストールできますが、コード実行前にリスク確認が必要です。署名済みは発行者指紋、未署名は対象 artifact の SHA-256 だけを承認します。更新、key 変更、取り消し、実行権限の拡大では再確認される場合があります
- リモートリポジトリのパッケージは常に manifest が宣言した署名を必要とし、ローカル unsigned 扱いには降格しません。独自 key を継続的に信頼する配布にはカスタムリポジトリを使います
- インストールは検証、原子置換、ロールバック、ライフサイクルを含む取引として実行されます。`hot-reload` / `backend-restart` は現プロセスで、`process-restart` は完全再起動後に有効になります

インストール画面はオンライン接続しません。承認前に、宣言された実行モードと確認内容を確認してください。

## Web プラグインマーケット

「**プラグインマーケット**」は、信頼済みリポジトリからプラグインを閲覧・インストールする管理者専用ページです。公式 / カスタムリポジトリの切り替え、カテゴリ・キーワード・互換性による絞り込み、バージョン履歴、依存関係、必要な Core API、サイズ、SHA-256、署名、変更履歴、ホームページ、インストール状態を確認できます。

マーケットは組み込みの `plugin-market` が提供します。`plugins.plugin-market.enabled=false` にするとページ、API、静的リソース、i18n、ナビゲーションがなくなり、直接アクセスは 404 になります。ネットワークの `plugin-catalog.enabled` と公式リポジトリは既定で有効ですが、起動時には接続しません。マーケットを開く / 更新する / インストールするときだけ取得します。

### インストールの安全境界

- 要求にはリポジトリ ID、プラグイン ID、バージョンだけを送る。ダウンロード URL はマニフェストからのみ得る
- `direct-strict` は HTTPS、公開アドレス、リダイレクトなし、アプリプロキシなし
- `proxy-trusted` はコアプロキシと許可された GitHub Release CDN への最大 5 回の再検証付きリダイレクト
- マニフェストとパッケージは同一リポジトリのポリシー、タイムアウト、サイズ上限を使う
- ステージング前にサイズ、SHA-256、署名を検証し、検証者がない場合は失敗を閉じる
- 置換開始後に失敗した場合は一時ファイルを消し、旧プラグインを復元する

任意 URL インストール、自動更新、削除、データ消去は提供しません。マーケット API は `/api/plugin-market/**` を使います。

## リカバリーモード

`download-workbench` などの必須プラグインが不足、破損、非互換、検証失敗になった場合、または起動中にプラグインがクラッシュした場合、アプリはリカバリーモードになります。画面に対象プラグインと診断を表示し、修復・インストール入口を残します。

`gallery-tools`、`gallery`、`novel`、`notification`、`tts`、`ai`、`push`、`mail`、デスクトップ GUI provider などの不足・無効化は、それ自体では通常リカバリーを起こしません。各プラグインの機能だけが利用できなくなり、コアに代替実装はありません。

## デスクトップ GUI

選択中の GUI provider が所有する「プラグイン」ページは、バックエンドから同じ状態（名前、区分、状態、実行フェーズ、必須、バージョン）を読み取り専用で表示します。Compose と Swing はそれぞれの UI を所有し、アプリケーションの業務セマンティクスを共有します。「更新」と「Web プラグイン管理を開く」を使えます。インストール、アンインストール、有効化、無効化は Web ページで行い、GUI がプラグインフォルダーを直接走査したり権限チェックを緩めたりすることはありません。

`gui-compose` は既定 provider、`gui-swing` は自動代替で、どちらも公式配布セットに含まれます。両方とも `process-restart` であり、インストール、更新、有効化 / 無効化、削除、provider 選択の変更にはアプリケーション全体の再起動が必要です。

## ファイルシステム境界

インストールの識別情報は `plugins/` 直下の元 artifact と `plugins/provenance/` sidecar です。`plugins/runtime/` は generation ごとの非公開の凍結作業領域にすぎません。portable 環境では `plugins/` のルート自体をシンボリックリンクまたは Windows junction にできます。ランタイムは実体のルートを先に解決して固定し、その中のリンクされた artifact 候補は個別に拒否します。

対応するファイルシステムでは、`plugins/runtime/` と `plugins/provenance/` の POSIX 権限または Windows ACL を制限します。FAT32、exFAT、SMB などがどちらも提供しない場合は診断を記録し、通常ファイル、`NOFOLLOW`、凍結スナップショット、ハッシュの検査を継続します。
