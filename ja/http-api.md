# HTTP API とプログラムの権限

外部プログラムは `Authorization: Bearer <プログラム用キー>` を使用できます。セキュリティページの「プログラム用アクセスキー」で、名前、権限、許可する接続元 IP を指定してキーを作成します。キー全体は一度だけ表示され、サーバーにはダイジェストのみが保存されます。既定の有効期限はありません。権限や接続元の編集、キーの失効が可能です。プラグイン全体を選択しても、今後追加される権限は含まれません。

接続元には数値の IPv4、IPv6、CIDR ネットワークを指定できます。制限する場合は1件以上が必要で、「すべての接続元」は明示的に選択します。リバースプロキシを使用する前に、信頼するプロキシを設定してください。キーと管理者セッション、招待認証、GUI 認証を併用しないでください。取得用の認証情報は別の `X-Acquisition-Credential` で送信します。

管理者セッションは引き続き `X-Session-Token` で既存の API を使用できます。プログラム用キーで利用できるのは、権限カタログに明示されたメソッドとパスのみです。その他の API は管理者本人またはローカルの操作手順を維持します。キーの管理と管理者認証情報の変更には、管理者本人のログインが必要です。この API は MCP サーバーを提供しません。

Cookie を使う書き込みは同一オリジンの検証が必要です。ブラウザーのクロスオリジン要求は拒否され、プラグイン管理や定期タスクなどでは明示的な同一オリジン情報も必要です。`/api/gui/**` はローカル端末と GUI token の制約を維持します。リモートプログラムは以下の業務 API を使います。プラグイン機能には活動中のプラグインと利用可能な依存機能が必要です。

## エンドポイント一覧

| 機能 | エンドポイント |
| --- | --- |
| 設定項目・既定値・秘匿済みの現在値 | GET `/api/settings`；GET `/api/settings/presets` |
| 設定の検証と保存 | POST `/api/settings/validate`；POST `/api/settings` |
| プラグインリポジトリ | GET / PUT `/api/settings/repositories` |
| データベース設定と接続テスト | GET `/api/settings/database`、`/providers`、`/status`；POST `/check` |
| データベース移行 | POST `/api/settings/database/migration-previews`、`/migrations`；GET `/migration` |
| 環境アーカイブ | POST `/api/settings/environment/preview`、`/restore`、`/exports`；GET `/restore`、`/exports`、`/exports/{id}/archive`、`/installation` |
| メタデータ補完 | GET `/api/tools/backfill/defaults`、`/tasks/current`、`/tasks/{id}`；POST `/candidates`、`/tasks`、`/tasks/{id}/cancel` |
| メディア保守と記録ファイルのインポート | GET `/api/tools/media`、`/api/tools/file-import`；各エンドポイント配下: POST `/previews`、`/tasks`、`/tasks/cancel`，GET `/tasks/current` |
| メディア機能とインポート完了確認 | POST `/api/tools/media/capabilities`；POST `/api/tools/file-import/completion/acknowledge` |
| ツール履歴とログ添付 | GET `/api/tools/history`、`/api/tools/logs/{fileName}` |
| ディレクトリ参照の確認と修正 | GET / POST `/api/tools/directories` |
| パスプレフィックス | `/api/tools/path-prefixes` |
| 画像分類 | `/api/tools/classifier/settings`、`/folders`、`/images`、`/image`、`/moves` |
| FFmpeg の状態とインストール | GET `/api/tools/ffmpeg`；POST `/api/tools/ffmpeg/tasks` |
| システムとコントロールセンター | `/api/admin/system`、`/api/admin/control-center` |
| AI・メール・プッシュのテスト | `/api/admin/ai/test`、`/models`；`/api/admin/mail/test`、`/test-all`；`/api/admin/push/test`、`/test-all` |
| ソフトウェア更新 | `/api/admin/update/check`、`/last`、`/download`、`/download/progress`、`/install` |
| 個別ダウンロードと履歴 | `/api/download/tasks`、`/api/download/tasks/history` |
| バックグラウンド手動バッチ | `/api/download/batches` |

表内の短縮パスは同じ行の API 系統の共通プレフィックスに続きます。翻訳は既存の `/api/translation/**` を使います。用語集、読み上げ、ギャラリー、コレクション、定期タスク、保守、プラグインマーケットも各 API を使います。ウィンドウ、ファイル選択、ローカルブラウザーの起動はクライアント側の操作です。

## 設定の保存

最初に項目一覧を取得し、返された `owner`、`packageId`、`generation`、`key` で変更を送ります。ホスト項目の owner/packageId は null、generation は 0 です。機密項目は `stored` のみを返し、値や既定の秘密は返しません。`KEEP`、`SET`、`CLEAR` は保持、置換、削除です。プリセットは同じ保存 API で適用する項目値の集合です。`effect` は反映方法を示し、保存だけではバックエンドやプロセスは再起動しません。

POST `/api/settings/validate` または `/api/settings` の本文例：

```json
{
  "changes": [
    {
      "owner": null,
      "packageId": null,
      "generation": 0,
      "key": "server.port",
      "action": "SET",
      "value": "6999"
    }
  ]
}
```

リポジトリ編集には GET が返した `revision` が必要です。追加や信頼・アドレス・ネットワークポリシーの変更には `trustConfirmed:true` を指定します。競合時は再取得し、他の編集を上書きしないでください。接続テストとツールの一時設定は保存されません。

## バックグラウンド手動バッチ

1. GET `/api/schedule/sources` で現在の `sourceType` と `activationToken` を取得します。定義形式は取得元プラグインが解釈します。
2. POST `/api/download/batches` に `name`、`sourceType`、`activationToken`、`definitionJson`、任意の `proxy` を送ります。`definitionJson` は最大 1 MiB UTF-8 の JSON 文字列で、要求全体は最大 8 MiB です。
3. 返された UUID を使い、GET `/api/download/batches/{id}` と `/{id}/items?offset=0&limit=100` を取得します。項目の識別子はバッチ UUID、`workType`、`id` の組み合わせで、個別ダウンロードの実行 UUID ではありません。
4. POST `/{id}/pause`、`/cancel`、`/resume`、`/retry` は一時停止、キャンセル、走査再開、失敗項目のみの再試行です。実行が実際に終了するまで停止中の状態は終端になりません。既に発見した失敗項目の再試行では未完了の取得元走査は続行されません。
5. 待機中に DELETE `/{id}` で記録を削除します。保持するバッチはプロセスごとに 1 件で、新規作成前に旧バッチを削除します。プロセス再起動後に結果は復元されません。

`explicit-list` はイラストと小説の混在を受け付けます。次のオブジェクトを `definitionJson` にシリアライズします。作品 ID は正の整数の十進文字列で、同じ種類内で重複できません：

```json
{
  "kind": "mixed",
  "source": {
    "acquisitionMode": "single-import",
    "works": [
      {
        "kind": "illust",
        "id": "123"
      },
      {
        "kind": "novel",
        "id": "456"
      }
    ]
  },
  "filters": {
    "content": "safe",
    "aiFilter": "exclude",
    "tagsExact": [],
    "tagsFuzzy": []
  },
  "download": {
    "concurrent": 1,
    "intervalMs": 1000,
    "bookmark": false
  }
}
```

例の作品 ID は対象作品に置き換えてください。他の取得元は作者の投稿、リクエスト、検索、シリーズ、ブックマーク、フォロー新着、コレクションです。フィルターは年齢区分、AI、タグ、イラスト種別、ページ数、小説の文字数、ブックマーク数を扱います。ブックマーク数の条件が有効な場合、不明な数値は通過しません。再開時に完了済み・フィルター除外済みの作品を再実行しません。

上限は 5,000 項目と符号化内容の予約予算 64 MiB のうち先に達した方です。JVM ヒープ使用量の保証ではありません。1 ページは最大 200 項目です。PUT `/{id}/credentials` に `{"expectedRun":1}` を送り、ヘッダーの認証情報で置き換えます。ヘッダー省略は削除です。その実行回のバッチが待機中であることが必要で、再開前に再検証します。POST `/{id}/resolve` は `expectedRun`、`workType`、`workId`、`reasonCode`、`userAction`、`rememberForRun` を受け取り、理由と実行回は現在の保留記録と一致する必要があります。両方とも本文上限は 16 KiB です。

## ツールと再起動操作

メディア保守と記録ファイルのインポートでは一覧を取得し、正確な `identity`（pluginId/packageId/generation/publication）と `options` でプレビューします。同じ識別情報とプレビュー `token` で開始し、キャンセルには `executionId` も送ります。プラグイン更新やデスクトップからの代替タスク開始後、古い token/UUID は新しい処理に作用しません。パスはサーバーのファイルシステムを指します。

POST `/api/tools/backfill/tasks` の本文例:

```json
{
  "delayMs": 0,
  "limit": 100,
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` は全候補、本文は最大 4 KiB です。202 は受理を示すため、実行状態を続けて確認してください。分類による移動はコピー、元ディレクトリ削除、データベース登録を別々に返します。部分完了時は結果を確認してから再試行してください。

データベース移行はプレビュー token で確定します。環境復元にはプレビュー token、選択項目、パスマッピング、パスワードを送り、`RESTORE_PENDING` の後に代替プロセスが実行します。エクスポートはサーバーの絶対 `destination` と `confirmRestart:true` が必須で、`password`、`includeCredentials`、`includeDatabases`、`includeState`、`includeResources` は任意です。リソースにはデータベースが必要で、認証情報・データベース・状態にはパスワードが必要です。既存の出力先は上書きしません。バックエンド停止、エクスポート、完全再起動の要求の順に進み、クライアントは再接続して照会します。再起動に失敗した場合は手動で起動してください。更新のインストールには検証済みパッケージの現在の SHA-256 が必要です。

## エラーと履歴

エラーには機械用 `code` と翻訳された `error` があります。400 は不正入力、401/403 は認証・権限拒否、404 は識別子不明、409 は使用中・競合・操作不可状態、413 は本文超過、503 は機能利用不可です。すべての非 2xx 応答を自動再試行せず、業務状態に応じて処理してください。

GET `/api/download/tasks/history` は既定 50 件、最大 200 件です。返された `beforeTime` と `beforeId` を両方指定して次ページを取得します。保存済み終端結果のみを列挙し、実行中のダウンロードは復元しません。個別ダウンロードの重複排除は現在のプロセスの保持期間内だけです。不明な実行を安全に再送できるとは限りません。ツール履歴とバッチ結果は別々の保持規則を使います。
## プログラムの権限一覧

現在のインストールで有効なカタログは、管理者が GET `/api/security/program-keys/permissions` で取得できます。表は組み込みの宣言です。外部プラグインは現在のカタログを参照してください。`*` はパスの1区間を表します。キーは明示した権限のみを保存し、プラグインやパスのワイルドカード認可は行いません。

共有設定、メディアツール、ファイルインポートには、`core` の入口権限と対象プラグインの同名権限が必要です。一覧は認可された対象に絞られます。個別ダウンロードには `core:downloads.create` と実際の種類を提供するプラグインの `downloads.create` が必要です。バッチには `core:batches.create` または `core:batches.resume` と、取得元および実行計画内の全作品プラグインの `batches.execute` が必要です。取得元の一覧は `core:schedule.sources.read` で読み取れます。

プログラムが照会、キャンセル、管理できるのは、自身が作成したダウンロードタスクとバッチのみです。管理者はインストール全体を管理できます。バッチはプロセスごとに1件で、キーを増やしても枠は増えません。翻訳、ツール、設定、ログ、データベース、環境の権限は対象プラグインまたはインストールに適用され、キーごとの専用コピーではありません。データベース移行と環境アーカイブは、インストール全体から選択した内容を扱います。

キー管理には管理者セッションが必要です。GET `/api/security/program-keys` は一覧、GET `/permissions` はカタログ、ルートへの POST は作成、PUT `/{id}` は編集、DELETE `/{id}` は失効です。作成には `name`、`permissions`、`anyIp`、`networks` を指定します。編集は `{"revision":1,"policy":{...}}`、失効は `{"revision":1}` を使います。読み取りで得たリビジョンを使用し、競合時は再読み込みしてください。作成時に `key` と一度限りの `secret` が返されます。一覧に秘密やダイジェストは含まれません。

1インストールにつき最大128キーです。名前は80文字、権限は512件、接続元ネットワークは64件までで、管理リクエストの本文は128 KiBまでです。最終使用時刻は、現在のプロセスが確認した認証成功を記録します。失効は新しいリクエストを拒否しますが、接納済みタスクを自動キャンセルしません。

起動キャッシュは GET `/api/tools/startup-cache`、POST `/api/tools/startup-cache/tasks`、POST `/api/tools/startup-cache/tasks/{id}/cancel` を使用します。開始時の本文は `{}` です。接納はトレーニング成功を意味しないため、状態を続けて確認してください。

### 権限の範囲と専用フロー

定期タスクには `core:schedule.*` の対応する操作権限と、取得元プラグインの `schedule.manage` が必要です。アカウントポリシー操作には、そのポリシーの owner の同じ権限が必要です。一覧は許可された取得元に絞られます。ここでの `*` は文書上の分類であり、保存できる権限ではありません。定期タスクはインストールの永続設定で、認可は取得元のスケジュール処理全体を委任します。作成したキーを失効させてもタスクは削除・無効化されません。今後の実行を止めるには、先にタスクを無効化または削除してください。

プラグインのライフサイクル、有効状態、信頼の変更には、対応する `core:plugins.*` 操作と対象の `<plugin>:plugins.manage` が必要です。依存する利用側プラグインがある場合、その認可も確認します。インストール済みの管理可能なプラグインは、未ロードでも選択できます。インストール権限はパッケージと依存関係によるサーバー上のコード実行を許可します。グローバル Hook の公開権限はセキュリティポリシーの変更を許可するため、この影響を踏まえて付与してください。

共有認証情報は `core:credentials.read`、`core:credentials.write`、`core:credentials.use` に分かれます。読み取りでは情報のみを返し、秘密は返しません。`X-Acquisition-Credentials` で保存済み認証情報を参照する場合、業務権限に加えて `credentials.use` が必要です。当該操作の認証情報を直接渡す場合は `X-Acquisition-Credential` を使います。

ギャラリー、小説ライブラリ、コレクション、受信箱、アーカイブ、ページ編集、更新、メンテナンスの権限は共有インストールに適用され、キーが作成したデータだけには限定されません。ダウンロードタスクと手動バッチは引き続きキーの識別情報で分離されます。ローカルメンテナンス、復旧、旧メタデータ移行にはローカル接続元も必要です。権限は接続元、確認、リビジョン、リースの検証を置き換えません。

認証設定、キー管理、GUI token、CAPTCHA、ブラウザー収集チケット、匿名クォータ、ページ組み立ては専用フローを維持します。プログラムはダウンロードと進捗に `/api/download/tasks`、ファイル取り込みに `/api/tools/file-import`、作品管理に `/api/library/artworks/{id}`、ページ操作に `/api/page-editor` を使います。受信箱テストは `/api/admin/notifications/test` または `/test-all` です。旧ブラウザー互換ルートに別のプログラム権限は付与しません。

| 権限 | 用途 | メソッドとパス |
| --- | --- | --- |
| `ai:connection.test` | AI 接続をテスト（モデルを呼び出します） | POST `/api/admin/ai/test` |
| `ai:models.read` | 利用可能な AI モデルを取得 | POST `/api/admin/ai/models` |
| `ai:status.read` | AI 設定状況を表示 | GET `/api/admin/ai/status` |
| `core:application.read` | アプリ情報と UI 言語リソースを読む | GET `/api/app/info`; GET `/api/i18n/messages/*`; GET `/api/i18n/meta` |
| `core:archives.create` | イラストのエクスポート用アーカイブを作成 | POST `/api/archive/pack-artworks` |
| `core:archives.download` | このインストールのエクスポート用アーカイブをダウンロード | GET `/api/archive/download/*` |
| `core:archives.read` | このインストールのエクスポート用アーカイブと状態を表示 | GET `/api/archive/list`; GET `/api/archive/status/*` |
| `core:assets.export` | イラストの元ファイルをダウンロード | GET `/api/downloaded/rawfile/*/*` |
| `core:assets.read` | イラストのプレビューと画像を読む | GET `/api/downloaded/image/*/*`; GET `/api/downloaded/thumbnail-file/*/*`; GET `/api/downloaded/thumbnail/*/*` |
| `core:authors.read` | ライブラリ全体の作者を表示 | GET `/api/authors/paged`; GET `/api/authors` |
| `core:authors.refresh` | Pixiv 作者情報を更新 | POST `/api/pixiv/user/*/meta/schedule` |
| `core:backfill.cancel` | 記録補完をキャンセル | POST `/api/tools/backfill/tasks/*/cancel` |
| `core:backfill.execute` | 記録を補完 | POST `/api/tools/backfill/tasks` |
| `core:backfill.read` | 記録補完の候補と状態を表示 | GET `/api/tools/backfill/defaults`; GET `/api/tools/backfill/tasks/*`; GET `/api/tools/backfill/tasks/current`; POST `/api/tools/backfill/candidates` |
| `core:batches.cancel` | このキーのバッチをキャンセル | POST `/api/download/batches/*/cancel` |
| `core:batches.create` | バックグラウンドバッチを作成 | POST `/api/download/batches` |
| `core:batches.credentials` | このキーのバッチ認証情報を更新 | PUT `/api/download/batches/*/credentials` |
| `core:batches.delete` | このキーのバッチ記録を削除 | DELETE `/api/download/batches/*` |
| `core:batches.pause` | このキーのバッチを一時停止 | POST `/api/download/batches/*/pause` |
| `core:batches.read` | このキーのバッチと結果を表示 | GET `/api/download/batches/*/items`; GET `/api/download/batches/*`; GET `/api/download/batches` |
| `core:batches.resolve` | このキーのバッチの確認待ち項目に対応 | POST `/api/download/batches/*/resolve` |
| `core:batches.resume` | このキーのバッチを再開・再試行 | POST `/api/download/batches/*/resume`; POST `/api/download/batches/*/retry` |
| `core:classifier.configure` | 画像分類設定を変更 | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 画像分類ディレクトリを削除 | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 画像分類と画像を表示 | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 分類した画像を移動 | POST `/api/tools/classifier/moves` |
| `core:collections.delete` | コレクションを削除 | DELETE `/api/collections/*` |
| `core:collections.members.write` | コレクションの作品を追加・削除 | DELETE `/api/collections/*/artworks/*`; DELETE `/api/collections/*/novels/*`; POST `/api/collections/*/artworks/*`; POST `/api/collections/*/novels/*` |
| `core:collections.read` | ライブラリ全体のコレクションと所属を読む | GET `/api/collections/*/icon`; GET `/api/collections/novels/of/*`; GET `/api/collections/of/*`; GET `/api/collections`; POST `/api/collections/memberships`; POST `/api/collections/novels/memberships` |
| `core:collections.write` | コレクション、アイコン、保存先を作成・編集 | DELETE `/api/collections/*/icon`; POST `/api/collections/*/icon`; POST `/api/collections`; PUT `/api/collections/*/download-root`; PUT `/api/collections/*/sort-order`; PUT `/api/collections/*` |
| `core:control-center.directory` | サーバーでコントロールセンターのフォルダーを開く | POST `/api/admin/control-center/directory` |
| `core:control-center.read` | コントロールセンターの状態を表示 | GET `/api/admin/control-center` |
| `core:credentials.read` | 取得認証情報のメタデータを読む（秘密を除く） | GET `/api/acquisition/credentials` |
| `core:credentials.use` | 許可されたリクエストで共有の取得認証情報を使用 | HEADER `X-Acquisition-Credentials` |
| `core:credentials.write` | 共有の取得認証情報ライブラリを編集 | PUT `/api/acquisition/credentials` |
| `core:database.check` | データベース接続を確認 | POST `/api/settings/database/check` |
| `core:database.migrate` | インストール全体のデータベースを移行 | POST `/api/settings/database/migrations` |
| `core:database.preview` | インストール全体のデータベース移行をプレビュー | POST `/api/settings/database/migration-previews` |
| `core:database.read` | データベース設定と移行状態を表示 | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | 作品ディレクトリを確認 | GET `/api/tools/directories` |
| `core:directories.write` | 作品ディレクトリの記録を変更 | POST `/api/tools/directories` |
| `core:downloads.cancel` | このキーのダウンロードタスクをキャンセル | POST `/api/download/queue/*/cancel`; POST `/api/download/queue/clear`; POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | ダウンロードタスクを作成 | POST `/api/download/tasks` |
| `core:downloads.extensions.read` | ダウンロード拡張機能を表示 | GET `/api/download/extensions` |
| `core:downloads.read` | このキーのダウンロードタスクを表示 | GET `/api/download/tasks/*`; GET `/api/download/tasks/history`; GET `/api/download/tasks` |
| `core:environment.export` | 環境全体のアーカイブをエクスポート | GET `/api/settings/environment/exports/*/archive`; POST `/api/settings/environment/exports` |
| `core:environment.preview` | 環境全体の復元をプレビュー | POST `/api/settings/environment/preview` |
| `core:environment.read` | インストールと環境移行の状態を表示 | GET `/api/settings/environment/exports`; GET `/api/settings/environment/installation`; GET `/api/settings/environment/restore` |
| `core:environment.restore` | 環境全体を復元 | POST `/api/settings/environment/restore` |
| `core:ffmpeg.install` | FFmpeg をインストール | POST `/api/tools/ffmpeg/tasks` |
| `core:ffmpeg.read` | FFmpeg の状態を表示 | GET `/api/tools/ffmpeg` |
| `core:file-import.acknowledge` | ファイルインポートの結果を確認 | POST `/api/tools/file-import/completion/acknowledge` |
| `core:file-import.cancel` | ファイルインポートをキャンセル | POST `/api/tools/file-import/tasks/cancel` |
| `core:file-import.execute` | ファイルをインポート | POST `/api/tools/file-import/tasks` |
| `core:file-import.preview` | ファイルインポートをプレビュー | POST `/api/tools/file-import/previews` |
| `core:file-import.read` | ファイルインポートのツールとタスクを表示 | GET `/api/tools/file-import/tasks/current`; GET `/api/tools/file-import` |
| `core:hooks.prepare` | 操作フック設定の変更をプレビュー | POST `/api/web/operation-hooks/declarations/prepare`; POST `/api/web/operation-hooks/prepare` |
| `core:hooks.publish` | グローバル操作フックのセキュリティポリシーを公開 | POST `/api/web/operation-hooks/publish` |
| `core:hooks.read` | グローバル操作フックと認可設定を読む | GET `/api/web/operation-hooks/catalog`; GET `/api/web/operation-hooks/configuration`; GET `/api/web/operation-hooks/declarations`; GET `/api/web/operation-hooks/definitions`; GET `/api/web/operation-hooks/targets` |
| `core:legacy-migration.execute` | 旧 JSON メタデータを移行（ローカルのみ） | GET `/api/migration/json-to-sqlite/stream`; POST `/api/migration/json-to-sqlite` |
| `core:library.move` | サーバー上のイラストファイルを移動 | POST `/api/library/artworks/*/move` |
| `core:library.read` | ライブラリ全体のイラスト登録、履歴、パスを表示 | GET `/api/downloaded/by-move-folder`; GET `/api/downloaded/history/paged`; GET `/api/downloaded/history`; GET `/api/downloaded/statistics`; GET `/api/library/artworks/*`; POST `/api/downloaded/batch` |
| `core:library.recover` | イラストのメタデータを復元 | POST `/api/library/artworks/*/recover-metadata` |
| `core:local-import.cancel` | ローカルインポートのスキャンと登録をキャンセル | POST `/api/local-import/previews/*/cancel`; POST `/api/local-import/tasks/*/cancel` |
| `core:local-import.execute` | ローカルイラストをデータベースに登録 | POST `/api/local-import/tasks` |
| `core:local-import.preview` | サーバー上のローカルイラストをスキャン | POST `/api/local-import/previews` |
| `core:local-import.read` | ローカルインポートのプリセットとタスクを読む | GET `/api/local-import/presets`; GET `/api/local-import/previews/*/items`; GET `/api/local-import/previews/*`; GET `/api/local-import/tasks/*/items`; GET `/api/local-import/tasks/*` |
| `core:logs.read` | 実行ログを表示 | GET `/api/tools/logs/*` |
| `core:maintenance.read` | メンテナンス状態を表示（ローカルのみ） | GET `/api/admin/maintenance/status` |
| `core:maintenance.run` | メンテナンスを実行（ローカルのみ） | POST `/api/admin/maintenance/run` |
| `core:media.cancel` | メディア処理をキャンセル | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | メディア処理を実行 | POST `/api/tools/media/tasks` |
| `core:media.preview` | メディア処理をプレビュー | POST `/api/tools/media/previews` |
| `core:media.read` | メディアツールとタスクを表示 | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | パスの接頭辞を表示 | GET `/api/tools/path-prefixes` |
| `core:paths.write` | パスの接頭辞を変更 | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:pixiv.read` | Pixiv のオンラインデータを取得 | GET `/api/pixiv/me/uid`; GET `/api/pixiv/thumbnail-proxy`; GET `/api/pixiv/user/*/meta` |
| `core:plugins.enabled` | 許可されたプラグインの有効状態を変更 | PUT `/api/plugins/*/enabled-preview`; PUT `/api/plugins/*/enabled` |
| `core:plugins.install` | ローカルプラグインをインストール（サーバーでコードを実行可能） | POST `/api/plugins/install` |
| `core:plugins.load` | 許可されたプラグインをロード | POST `/api/plugins/*/actions/load`; POST `/api/plugins/*/load` |
| `core:plugins.quiesce` | 許可されたプラグインの新規処理を停止 | POST `/api/plugins/*/actions/quiesce`; POST `/api/plugins/*/quiesce` |
| `core:plugins.read` | プラグインの状態、依存関係、インストール操作を表示 | GET `/api/plugins/*/impact`; GET `/api/plugins/acquisitions/*`; GET `/api/plugins/acquisitions`; GET `/api/plugins/status`; GET `/api/plugins/updates` |
| `core:plugins.reload` | 許可されたプラグインを再ロード | POST `/api/plugins/*/actions/reload`; POST `/api/plugins/*/reload` |
| `core:plugins.remove` | 許可されたプラグインパッケージを削除 | POST `/api/plugins/*/actions/remove`; POST `/api/plugins/*/remove` |
| `core:plugins.restart` | 許可されたプラグインを再起動 | POST `/api/plugins/*/actions/restart`; POST `/api/plugins/*/restart` |
| `core:plugins.revocations.refresh` | プラグインリポジトリの失効情報を更新 | POST `/api/plugins/revocations/*/refresh` |
| `core:plugins.start` | 許可されたプラグインを起動 | POST `/api/plugins/*/actions/start`; POST `/api/plugins/*/start` |
| `core:plugins.stop` | 許可されたプラグインを停止 | POST `/api/plugins/*/actions/stop`; POST `/api/plugins/*/stop` |
| `core:plugins.trust` | 許可されたプラグインの実行信頼を承認・取り消し | DELETE `/api/plugins/*/trust`; PUT `/api/plugins/*/trust` |
| `core:plugins.unload` | 許可されたプラグインの実行インスタンスをアンロード | POST `/api/plugins/*/actions/unload`; POST `/api/plugins/*/unload` |
| `core:profile.read` | このインストールの表示名を読む | GET `/api/onboarding/profile` |
| `core:profile.write` | このインストールの表示名を変更 | POST `/api/onboarding/profile` |
| `core:recovery.read` | リカバリーモードの診断を表示 | GET `/api/plugins/recovery` |
| `core:repositories.read` | プラグインリポジトリ設定を表示 | GET `/api/settings/repositories` |
| `core:repositories.write` | プラグインリポジトリ設定を変更 | PUT `/api/settings/repositories` |
| `core:schedule.create` | 永続スケジュールを作成（キー失効後も存続） | POST `/api/schedule/tasks` |
| `core:schedule.credentials` | スケジュールの認証情報とアカウントポリシーを管理 | DELETE `/api/schedule/tasks/*/credential`; POST `/api/schedule/credential-policies/actions`; POST `/api/schedule/tasks/*/credential` |
| `core:schedule.delete` | 永続スケジュールを削除 | DELETE `/api/schedule/tasks/*` |
| `core:schedule.pause` | 永続スケジュールを一時停止 | POST `/api/schedule/tasks/*/pause` |
| `core:schedule.pending.write` | スケジュールの保留項目を解決・削除 | DELETE `/api/schedule/tasks/*/pending`; POST `/api/schedule/tasks/*/pending/resolve` |
| `core:schedule.read` | 許可されたソースの永続スケジュールを読む | GET `/api/schedule/tasks/*/pending`; GET `/api/schedule/tasks/*/queue`; GET `/api/schedule/tasks/*`; GET `/api/schedule/tasks` |
| `core:schedule.run` | 永続スケジュールを有効化・実行・再開 | POST `/api/schedule/tasks/*/enabled`; POST `/api/schedule/tasks/*/resume`; POST `/api/schedule/tasks/*/run` |
| `core:schedule.sources.read` | バッチと定期タスクの取得元一覧を表示 | GET `/api/schedule/sources` |
| `core:schedule.write` | 永続スケジュールとプロキシを編集 | POST `/api/schedule/tasks/*/proxy`; PUT `/api/schedule/tasks/*` |
| `core:scripts.read` | インストール済みプラグインのユーザースクリプトを読む | GET `/api/scripts/*.user.js`; GET `/api/scripts/*/install`; GET `/api/scripts/*`; GET `/api/scripts` |
| `core:series.read` | ライブラリ全体のイラストシリーズを表示 | GET `/api/series/*/cover`; GET `/api/series/*`; GET `/api/series/paged`; GET `/api/series` |
| `core:settings.read` | 設定とプリセットを読み取る | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | 設定を検証・保存 | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | 起動キャッシュのトレーニングをキャンセル | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | 起動キャッシュをトレーニング | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | 起動キャッシュの状態を表示 | GET `/api/tools/startup-cache` |
| `core:system.check` | ネットワーク接続を確認 | GET `/api/onboarding/connectivity`; POST `/api/admin/system/connectivity` |
| `core:system.exit` | リカバリーモードのアプリを終了（ローカルのみ） | POST `/api/plugins/recovery/exit` |
| `core:system.read` | システム状態を表示 | GET `/api/admin/system` |
| `core:system.reload` | 設定を再読み込み | POST `/api/admin/system/reload` |
| `core:system.restart` | アプリを再起動 | POST `/api/admin/system/restart`; POST `/api/plugins/backend-restart`; POST `/api/plugins/recovery/restart` |
| `core:tools.history.read` | ツールの実行履歴を表示 | GET `/api/tools/history` |
| `core:updates.download` | アプリの更新をダウンロード | POST `/api/admin/update/download` |
| `core:updates.install` | アプリの更新をインストール | POST `/api/admin/update/install` |
| `core:updates.read` | アプリの更新とダウンロード状況を確認 | GET `/api/admin/update/check`; GET `/api/admin/update/download/progress`; GET `/api/admin/update/last` |
| `download-workbench:page.write` | ダウンロードワークベンチのページを切り替え | POST `/api/batch/page` |
| `download-workbench:pixiv.read` | Pixiv のオンラインデータを取得 | GET `/api/pixiv/artwork/*/meta`; GET `/api/pixiv/artwork/*/pages`; GET `/api/pixiv/artwork/*/ugoira`; GET `/api/pixiv/me/collection/*/works`; GET `/api/pixiv/me/collections`; GET `/api/pixiv/me/follow-latest`; GET `/api/pixiv/me/following`; GET `/api/pixiv/me/illust-bookmarks`; GET `/api/pixiv/search/range`; GET `/api/pixiv/search`; GET `/api/pixiv/series/*`; GET `/api/pixiv/user/*/artworks`; GET `/api/pixiv/user/*/illust-cards`; GET `/api/pixiv/user/*/request-artworks` |
| `download-workbench:profiles.read` | 共有ダウンロードプリセットを読む | GET `/api/batch/profiles` |
| `download-workbench:profiles.write` | 共有ダウンロードプリセットを編集 | PUT `/api/batch/profiles` |
| `download-workbench:state.read` | ソロモードのワークベンチ状態を読む | GET `/api/batch/state` |
| `download-workbench:state.write` | ソロモードのワークベンチ状態を編集 | POST `/api/batch/state` |
| `gallery-tools:duplicates.read` | 重複作品とスキャン状況を表示 | GET `/api/duplicates/groups`; GET `/api/duplicates/scan/status` |
| `gallery-tools:duplicates.scan` | 重複作品をスキャン | POST `/api/duplicates/rescan`; POST `/api/duplicates/scan` |
| `gallery-tools:statistics.read` | ライブラリ全体の統計を表示 | GET `/api/stats/dashboard` |
| `gallery:collections.write` | イラストのコレクション所属を変更 | POST `/api/gallery/artworks/collect` |
| `gallery:delete` | ライブラリのイラストとファイルを削除 | DELETE `/api/gallery/artwork/*`; POST `/api/gallery/artworks/delete` |
| `gallery:export` | ライブラリのイラストファイルをエクスポート | POST `/api/gallery/artworks/export` |
| `gallery:read` | ライブラリ全体のイラストとタグを表示 | GET `/api/gallery/artwork/*/by-author`; GET `/api/gallery/artwork/*/by-series`; GET `/api/gallery/artwork/*/related`; GET `/api/gallery/artwork/*/series`; GET `/api/gallery/artwork/*`; GET `/api/gallery/artworks`; GET `/api/gallery/tags/lookup`; GET `/api/gallery/tags` |
| `mail:templates.send` | すべてのメールテンプレートを送信 | POST `/api/admin/mail/test-all` |
| `mail:test.send` | テストメールを送信 | POST `/api/admin/mail/test` |
| `notification:inbox.delete` | 受信メッセージを削除 | DELETE `/api/notifications/*` |
| `notification:inbox.read` | このインストールの受信箱を読む | GET `/api/notifications/*/content`; GET `/api/notifications/*`; GET `/api/notifications` |
| `notification:inbox.templates.test` | 受信箱にすべてのメッセージテンプレートを送信 | POST `/api/admin/notifications/test-all` |
| `notification:inbox.test` | 受信箱にテストメッセージを送信 | POST `/api/admin/notifications/test` |
| `notification:inbox.update` | 受信メッセージを既読、アンケートを利用不可に設定 | POST `/api/notifications/*/read`; POST `/api/notifications/*/survey-unavailable`; POST `/api/notifications/read-all` |
| `novel:collections.write` | 小説のコレクション所属を変更 | POST `/api/gallery/novels/collect` |
| `novel:content.read` | 小説本文と埋め込み画像を読む | GET `/api/gallery/novel/*/content`; GET `/api/gallery/novel/*/embed/*` |
| `novel:downloads.read` | このキーで送信した小説のダウンロード状況を読む | GET `/api/novel/status/*` |
| `novel:glossary.delete` | 小説の用語集を削除 | DELETE `/api/admin/glossary/*` |
| `novel:glossary.read` | 小説の用語集を読む | GET `/api/admin/glossary/*`; GET `/api/admin/glossary/novel/*/default`; GET `/api/admin/glossary/series/*/default`; GET `/api/admin/glossary` |
| `novel:glossary.write` | 小説の用語集を作成・編集 | POST `/api/admin/glossary`; PUT `/api/admin/glossary/*/entries`; PUT `/api/admin/glossary/*` |
| `novel:library.delete` | 小説とファイルを削除 | DELETE `/api/gallery/novel/*`; POST `/api/gallery/novels/delete` |
| `novel:library.export` | 小説ファイルをエクスポート | GET `/api/novel/*/export`; GET `/api/novel/series/*/merged`; POST `/api/gallery/novels/export/options`; POST `/api/gallery/novels/export` |
| `novel:library.read` | ライブラリ全体の小説とシリーズを表示 | GET `/api/gallery/novel/*/by-series`; GET `/api/gallery/novel/*/cover`; GET `/api/gallery/novel/*/series`; GET `/api/gallery/novel/*`; GET `/api/gallery/novel/series/*/cover`; GET `/api/gallery/novel/series/*`; GET `/api/gallery/novel/translated-titles`; GET `/api/gallery/novels/authors`; GET `/api/gallery/novels/series`; GET `/api/gallery/novels/tags`; GET `/api/gallery/novels`; GET `/api/novel/series/*/novel-ids`; POST `/api/gallery/novels/downloaded-batch` |
| `novel:narration.read` | 朗読のキャストと音声を読む | GET `/api/narration/availability`; GET `/api/narration/cast/voice/reference`; GET `/api/narration/cast`; GET `/api/narration/casts/*/voices`; GET `/api/narration/casts/novel/*/default`; GET `/api/narration/casts`; GET `/api/narration/narrator-presets` |
| `novel:narration.script` | 朗読台本を生成 | POST `/api/narration/script` |
| `novel:narration.synthesize` | 朗読音声と参照音声を合成 | POST `/api/narration/cast/voice/reference/generate`; POST `/api/narration/tts/line`; POST `/api/narration/tts/preview` |
| `novel:narration.write` | 朗読のキャストと参照音声を編集 | DELETE `/api/narration/cast/voice/reference`; POST `/api/narration/cast/voice/reference`; POST `/api/narration/casts`; PUT `/api/narration/cast/voice` |
| `novel:pixiv.read` | Pixiv のオンラインデータを取得 | GET `/api/pixiv/me/novel-bookmarks`; GET `/api/pixiv/novel-search/range`; GET `/api/pixiv/novel-search`; GET `/api/pixiv/novel/*/bookmark-count`; GET `/api/pixiv/novel/*/meta`; GET `/api/pixiv/novel/series/*`; GET `/api/pixiv/user/*/novel-cards`; GET `/api/pixiv/user/*/novels` |
| `novel:series.merge` | 小説シリーズの合本を生成 | POST `/api/novel/series/*/merge` |
| `novel:translation.execute` | 小説とシリーズタイトルを翻訳 | POST `/api/novel/*/translate`; POST `/api/novel/series/*/translate-title` |
| `novel:translation.probe` | 小説の翻訳言語を検出 | POST `/api/novel/translate-lang-probe` |
| `novel:translation.read` | 小説の自動翻訳状況を読む | GET `/api/novel/translate-status/*` |
| `page-editor:components.preview` | コンポーネントをプレビュー | POST `/api/page-editor/components/preview`; POST `/api/page-editor/components/previews/*/close` |
| `page-editor:components.publish` | コンポーネントを公開・無効化 | POST `/api/page-editor/components/*/availability`; POST `/api/page-editor/components/*/publish` |
| `page-editor:components.read` | コンポーネントライブラリを読む | GET `/api/page-editor/components/*/versions/*`; GET `/api/page-editor/components/*`; GET `/api/page-editor/components` |
| `page-editor:components.write` | コンポーネントの下書きを保存 | POST `/api/page-editor/components/save` |
| `page-editor:pages.preview` | ページをプレビュー・検証 | POST `/api/page-editor/documents/*/prepare`; POST `/api/page-editor/documents/*/preview`; POST `/api/page-editor/migrate`; POST `/api/page-editor/styles/parse` |
| `page-editor:pages.publish` | ページを公開・復元 | POST `/api/page-editor/documents/*/publish`; POST `/api/page-editor/documents/*/restore` |
| `page-editor:pages.read` | ページ一覧とドキュメントを読む | GET `/api/page-editor/catalog`; GET `/api/page-editor/documents/*/default`; GET `/api/page-editor/documents/*` |
| `page-editor:pages.write` | ページの下書きを保存 | POST `/api/page-editor/documents/*/save` |
| `plugin-market:install` | マーケットのプラグインと依存関係をインストール（サーバーでコードを実行可能） | POST `/api/plugin-market/*/*/*/install-preview`; POST `/api/plugin-market/*/*/*/install`; POST `/api/plugin-market/operations/*/execute` |
| `plugin-market:operations.discard` | 未実行のプラグイン取得操作を破棄 | POST `/api/plugin-market/operations/*/discard` |
| `plugin-market:operations.prepare` | プラグイン取得操作を準備 | POST `/api/plugin-market/operations` |
| `plugin-market:read` | プラグインマーケットとインストールのプレビューを表示 | GET `/api/plugin-market/*/*/*/install-preview`; GET `/api/plugin-market/catalog`; GET `/api/plugin-market/content/*/*/*/*`; GET `/api/plugin-market/content/*/*/image`; GET `/api/plugin-market/plugins/*/*/*/facts`; GET `/api/plugin-market/plugins/*/*`; GET `/api/plugin-market/repositories` |
| `plugin-market:repositories.preview` | インポート前にプラグインリポジトリを確認 | POST `/api/plugin-market/repositories/import/preview` |
| `plugin-market:repositories.trust` | プラグインリポジトリを信頼してインポート | POST `/api/plugin-market/repositories/import/trust` |
| `push:templates.send` | すべての通知テンプレートを送信 | POST `/api/admin/push/test-all` |
| `push:test.send` | テスト通知を送信 | POST `/api/admin/push/test` |
| `translation:cancel` | 翻訳タスクを一時停止またはキャンセル | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | 翻訳タスクの記録を削除 | DELETE `/api/translation/jobs/*` |
| `translation:execute` | 小説・シリーズの翻訳を開始または再開 | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | 翻訳の状態・言語・タスクを表示 | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
| `tts:synthesize` | 読み上げ音声をオンラインで合成 | POST `/api/tts/edge/synthesize` |
| `tts:voices.read` | オンライン読み上げの音声を表示 | GET `/api/tts/edge/voices` |
| `<plugin>:settings.read` | 設定とプリセットを読み取る | `core:settings.read` |
| `<plugin>:settings.write` | 設定を検証・保存 | `core:settings.write` |
| `<plugin>:media.read` | メディアツールとタスクを表示 | `core:media.read` |
| `<plugin>:media.preview` | メディア処理をプレビュー | `core:media.preview` |
| `<plugin>:media.execute` | メディア処理を実行 | `core:media.execute` |
| `<plugin>:media.cancel` | メディア処理をキャンセル | `core:media.cancel` |
| `<plugin>:file-import.read` | ファイルインポートのツールとタスクを表示 | `core:file-import.read` |
| `<plugin>:file-import.preview` | ファイルインポートをプレビュー | `core:file-import.preview` |
| `<plugin>:file-import.execute` | ファイルをインポート | `core:file-import.execute` |
| `<plugin>:file-import.cancel` | ファイルインポートをキャンセル | `core:file-import.cancel` |
| `<plugin>:file-import.acknowledge` | ファイルインポートの結果を確認 | `core:file-import.acknowledge` |
| `<plugin>:downloads.create` | ダウンロードタスクを作成 | `core:downloads.create` |
| `<plugin>:batches.execute` | バッチによるこのプラグインの取得元と作品処理の利用を許可 | `core:batches.create`, `core:batches.resume` |
| `<plugin>:schedule.manage` | このプラグインの永続スケジュールと認証情報ポリシーを管理 | `core:schedule.*` |
| `<plugin>:plugins.manage` | このプラグインのライフサイクル、有効状態、信頼の管理を許可 | `core:plugins.*` |
