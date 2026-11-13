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

| 権限 | 用途 | メソッドとパス |
| --- | --- | --- |
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
| `core:database.check` | データベース接続を確認 | POST `/api/settings/database/check` |
| `core:database.migrate` | インストール全体のデータベースを移行 | POST `/api/settings/database/migrations` |
| `core:database.preview` | インストール全体のデータベース移行をプレビュー | POST `/api/settings/database/migration-previews` |
| `core:database.read` | データベース設定と移行状態を表示 | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | 作品ディレクトリを確認 | GET `/api/tools/directories` |
| `core:directories.write` | 作品ディレクトリの記録を変更 | POST `/api/tools/directories` |
| `core:downloads.cancel` | このキーのダウンロードタスクをキャンセル | POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | ダウンロードタスクを作成 | POST `/api/download/tasks` |
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
| `core:logs.read` | 実行ログを表示 | GET `/api/tools/logs/*` |
| `core:media.cancel` | メディア処理をキャンセル | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | メディア処理を実行 | POST `/api/tools/media/tasks` |
| `core:media.preview` | メディア処理をプレビュー | POST `/api/tools/media/previews` |
| `core:media.read` | メディアツールとタスクを表示 | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | パスの接頭辞を表示 | GET `/api/tools/path-prefixes` |
| `core:paths.write` | パスの接頭辞を変更 | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:repositories.read` | プラグインリポジトリ設定を表示 | GET `/api/settings/repositories` |
| `core:repositories.write` | プラグインリポジトリ設定を変更 | PUT `/api/settings/repositories` |
| `core:schedule.sources.read` | バッチと定期タスクの取得元一覧を表示 | GET `/api/schedule/sources` |
| `core:settings.read` | 設定とプリセットを読み取る | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | 設定を検証・保存 | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | 起動キャッシュのトレーニングをキャンセル | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | 起動キャッシュをトレーニング | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | 起動キャッシュの状態を表示 | GET `/api/tools/startup-cache` |
| `core:system.check` | ネットワーク接続を確認 | POST `/api/admin/system/connectivity` |
| `core:system.read` | システム状態を表示 | GET `/api/admin/system` |
| `core:system.reload` | 設定を再読み込み | POST `/api/admin/system/reload` |
| `core:system.restart` | アプリを再起動 | POST `/api/admin/system/restart` |
| `core:tools.history.read` | ツールの実行履歴を表示 | GET `/api/tools/history` |
| `translation:cancel` | 翻訳タスクを一時停止またはキャンセル | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | 翻訳タスクの記録を削除 | DELETE `/api/translation/jobs/*` |
| `translation:execute` | 小説・シリーズの翻訳を開始または再開 | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | 翻訳の状態・言語・タスクを表示 | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
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
