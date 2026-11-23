# HTTP API とプログラムの権限

## スクリプトやエージェントから API を使う

1. PixivDownloader を実行しているインスタンスの `/openapi` を開きます。ローカルの既定の URL は `http://localhost:6999/openapi` です。管理者としてログインすると、利用できる API、リクエスト例、操作ごとに必要な権限を確認できます。
2. デスクトップの「設定 → アクセス制御 → プログラム用アクセスキー」でキーを作成し、接続元 IP と必要な操作の権限を指定します。スクリプトやエージェントが API 仕様を取得する場合は `core:api-reference.read` も許可します。デスクトップがない環境では、管理者セッションで `/api/security/program-keys` を呼び出してキーを管理できます。
3. スクリプトは `Authorization: Bearer <プログラムキー>` を付けて `GET /api/openapi.json` を呼び出すと、OpenAPI 仕様を JSON で取得できます。エージェントには、キー作成後に「プロンプトをコピー」でインスタンスのドキュメント URL を渡し、キーは別途設定してください。キーに閲覧権限がない場合は、必要な操作の説明を管理者が提供します。

`/openapi` のアクセス方法はログインせずに読めます。JSON の取得には、管理者セッションまたは仕様の閲覧権限を持つプログラムキーが必要です。同じ認証情報で `HEAD /api/openapi.json` を呼び出すと閲覧権限を確認できます。

HTTPS を推奨します。ローカルネットワーク外からの HTTP 接続では、既定でプログラムキーが拒否されます。例外が必要な場合は対象のキーでリスクを確認し、続けて外部 HTTP 接続を許可してください。権限や接続元を編集すると HTTPS が再び必須になります。サーバーが拒否しても、平文で送ったキーは傍受される可能性があります。

キーの全体が表示されるのは作成時の一度だけです。サーバーにはダイジェストが保存されます。既定の有効期限はなく、権限や接続元の変更、キーの失効が可能です。プラグイン全体を選択した場合に付与されるのは、その時点で一覧にある権限のみです。

接続元には数値の IPv4、IPv6、CIDR ネットワークを指定できます。制限する場合は1件以上が必要で、「すべての接続元」は明示的に選択します。リバースプロキシを使用する前に、信頼するプロキシを設定してください。キーと管理者セッション、招待認証、GUI 認証を併用しないでください。取得用の認証情報は別の `X-Acquisition-Credential` で送信します。

管理者セッションは引き続き `X-Session-Token` で既存の API を使用できます。プログラム用キーで利用できるのは、権限カタログに明示されたメソッドとパスのみです。その他の API は管理者本人またはローカルの操作手順を維持します。キーの管理と管理者認証情報の変更には、管理者本人のログインが必要です。この API は MCP サーバーを提供しません。

`programKey` の指定がない操作に有効な Bearer キーだけを送ると、匿名アクセスが可能な操作でも `403 auth.program.unsupported` が返ります。無効なキーや認証情報の併用では `401 auth.program.invalid` が返る場合があります。

Cookie を使う書き込みは同一オリジンの検証が必要です。ブラウザーのクロスオリジン要求は拒否され、プラグイン管理や定期タスクなどでは明示的な同一オリジン情報も必要です。`/api/gui/**` はローカル端末と GUI token の制約を維持します。リモートプログラムは以下の業務 API を使います。プラグイン機能には活動中のプラグインと利用可能な依存機能が必要です。

`GET /api/security/program-keys/self` は現在のキーの `id` と `permissions` を返します。権限の配列は空の場合もあります。Bearer 認証で書き込むときも `x-csrf-origin` を確認してください。`POST /api/schedule/tasks` には同一オリジンの Origin または Referer が必要です。ブラウザーの認証情報を伴わないネイティブクライアントでは、`POST /api/download/tasks` と `/api/download/batches` で両方を省略できます。

キーを切り替えるときは、管理者がデスクトップのセキュリティ画面か管理者セッションで新しいキーを発行します。クライアントを切り替え、`GET /api/security/program-keys/self` で新しいキーの ID と権限を確認してから、管理者が旧キーを失効させます。旧キーで送信したタスクは新しいキーからは参照できません。失効前に終了まで追跡するか、管理者セッションで確認してください。

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
2. POST `/api/download/batches` に `name`、`sourceType`、`activationToken`、`definition`、任意の `proxy` を送ります。 `definition` は JSON オブジェクトとして送信します。UTF-8 にシリアライズした定義は最大 1 MiB、要求全体は最大 8 MiB です。
3. 返された UUID を使い、GET `/api/download/batches/{id}` と `/{id}/items?offset=0&limit=100` を取得します。項目の識別子はバッチ UUID、`workType`、`id` の組み合わせで、個別ダウンロードの実行 UUID ではありません。
4. POST `/{id}/pause`、`/cancel`、`/resume`、`/retry` は一時停止、キャンセル、走査再開、失敗項目のみの再試行です。実行が実際に終了するまで停止中の状態は終端になりません。既に発見した失敗項目の再試行では未完了の取得元走査は続行されません。
5. 待機中に DELETE `/{id}` で記録を削除します。保持するバッチはプロセスごとに 1 件で、新規作成前に旧バッチを削除します。プロセス再起動後に結果は復元されません。

送信時の 403 応答では、特定できた不足権限を `programPermission` で確認できます。`auth.program.unsupported` の場合は操作の `x-program-alternative` を確認してください。202 応答を受け取ったら `core:batches.read` で `Location` を取得します。実行時に権限が不足した場合はバッチ結果の `programPermission` に権限 ID が入ります。

`explicit-list` はイラストと小説の混在を受け付けます。次のオブジェクトを `definition` に指定します。作品 ID は正の整数の十進文字列で、同じ種類内で重複できません：

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
  "artworkIds": [12345],
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` は全候補、本文は最大 4 KiB です。202 は受理を示すため、実行状態を続けて確認してください。分類による移動はコピー、元ディレクトリ削除、データベース登録を別々に返します。部分完了時は結果を確認してから再試行してください。

データベース移行はプレビュー token で確定します。環境復元にはプレビュー token、選択項目、パスマッピング、パスワードを送り、`RESTORE_PENDING` の後に代替プロセスが実行します。エクスポートはサーバーの絶対 `destination` と `confirmRestart:true` が必須で、`password`、`includeCredentials`、`includeDatabases`、`includeState`、`includeResources` は任意です。リソースにはデータベースが必要で、認証情報・データベース・状態にはパスワードが必要です。既存の出力先は上書きしません。バックエンド停止、エクスポート、完全再起動の要求の順に進み、クライアントは再接続して照会します。再起動に失敗した場合は手動で起動してください。更新のインストールには検証済みパッケージの現在の SHA-256 が必要です。

`POST /api/library/artworks/{artworkId}/recover-metadata` は指定した値で未設定項目を補完します。本文がない、読み取れない、または有効な値がない場合は `400` です。タグやシリーズを取得するには、登録した作品 ID を指定して補完を実行してください。 [インポート後のメタデータ補完](/ja/local-import?id=metadata-backfill)

外部ファイルを参照する作品は `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` で取得できます。`MANAGED` のファイルは作品の削除時に削除されます。EXTERNAL_REFERENCE は現在の登録が外部ファイルを参照していることを示し、削除時も元ファイルが残ります。 削除後も重複判定に使う `deleted=true` の記録が残ります。

`/openapi` では、各操作のレスポンス一覧のすぐ下にある「関連する操作」から移動できます。たとえば、ローカルインポートのプリセット一覧からスキャンのプレビューを開けます。サーバーは文書とリンクを言語別にキャッシュします。プラグインのルート追加などでカタログが変わったら、ページを再読み込みしてください。

操作の `externalDocs` はガイドへのリンクです。レスポンスの `links` は、次のリクエストとそのパラメーターの取得元を示します。`x-related-operations` は宣言済みの代替操作とレスポンスリンクを優先し、続いて説明に含まれるルートと、リソース名またはグループを共有する操作を挙げます。実行前に対象のパラメーターと権限を確認してください。`x-related-guides` は関連ガイドの一覧です。

イラストと小説の一括フィルターは全ページを対象とします。`"mode":"filter"` を指定するときは、`filter` に JSON オブジェクトを渡します。ID 配列に指定できるのは `42` のような正の JSON 整数です。小数の `42.9`、文字列の `"42"`、ゼロ、負数を渡すと HTTP 400 が返り、`field` で修正箇所を確認できます。`"filter":{}` を明示した場合や `filter` がページ指定だけの場合は、その種類の全作品を選択します。削除またはエクスポート後の元作品の削除には `"confirmAll":true` でこの範囲を確認します。プログラムキーには該当ギャラリーの削除権限も必要です。エクスポートで `archiveToken` が返ったら `core:archives.read` で `GET /api/archive/status/{token}` をポーリングします。`ready` になったら `core:archives.download` で `GET /api/archive/download/{token}` を呼び出します。

## エラーと履歴

イラストや小説のメタデータを取得したとき、または `POST /api/download/tasks` でダウンロードを依頼したときの `404 WORK_NOT_FOUND` は、現在のアカウントでは取得元で作品が見つからなかったことを示します。作品 ID と公開範囲を確認してください。タスク ID で照会したときの `404 DOWNLOAD_TASK_NOT_FOUND` は、呼び出し元がそのタスクを参照できないことを示します。

手動バッチをポーリングするときは `state` で状態を判定します。理由を示す `code` は null の場合があり、取得元やアカウントポリシーのプラグインが独自の値を返すこともあります。`batch.work-failed` ではバッチの `items` で失敗した項目を、作者バッチの `schedule.pixiv.discovery-not-found` では作者 ID を確認してください。未知のコードを受け取った場合も `state` に従います。

エラーには機械用 `code` と翻訳された `error` があります。400 は不正入力、401/403 は認証・権限拒否、404 は識別子不明、409 は使用中・競合・操作不可状態、413 は本文超過、503 は機能利用不可です。すべての非 2xx 応答を自動再試行せず、業務状態に応じて処理してください。

`POST /api/download/tasks` が 202 を返したら、`Location` で進捗を確認します。パスの `taskId` は応答の `task.attempt.attemptId` です。

最初は 2 秒間隔で問い合わせます。`revision` が変わらない間は間隔を徐々に 10 秒まで延ばし、更新されたら 2 秒に戻します。`phase` が `COMPLETED`、`FAILED`、`CANCELLED` のいずれかになったら終了します。`Retry-After` が返った場合は、指定された時間を空けてください。

`workType` は `x-download-submissions` から選びます。未公開の型には `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE` が返ります。本文のエラーでは `field` は JSON Pointer、クエリ、パス、ヘッダー、multipart のエラーではパラメーター名またはパート名です。`in`、`field`、`reason` があれば、どの入力を修正するか判断できます。たとえば `/options/pages` と `out_of_range` は作品の範囲外のページを示します。`explicit-list` に `/definition/source/acquisitionMode` がない場合、バッチ作成前に 400 が返ります。

進捗がある場合は `progress.unit`、`completed`、`total` を組にして読みます。値は今回の試行の現在の工程を表します。最終結果には `phase` を使います。`progress` が null の場合は測定値がありません。

| 値 | 取得・送信時の形式 |
| --- | --- |
| Pixiv の作品 ID | ギャラリーの `artworkId` とエクスポートの `ids[]` は JSON 整数です。Pixiv 検索の `id`、タスクの `workId`、バッチの `key.id` は文字列です。変換時は整数の精度を保ってください。他のプロバイダーの `workId` は各実装が定義します。 |
| ダウンロード設定 | 単一タスクの `options` には `{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`、バッチの `definition.download` には `{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}` を渡します。 |
| 時刻とファイル | タスクの `updatedAt` は ISO 8601 です。バッチの `createdTime`、ギャラリーの `time`、スケジュールの `nextRunTime` は Unix 時刻のミリ秒です。`outputPath` は今回の試行の出力予定ディレクトリ、`folder` はギャラリーの登録先です。作品 ID で照合してください。 |
| レスポンスの形 | `GET /api/authors` は配列、`GET /api/authors/paged` は `content` を含むページ、`GET /api/downloaded/statistics` は `success` と件数を含むオブジェクトを返します。 |

| エンドポイント | ページ指定 | 次のページの取得方法 |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | 最初は `page=0` を指定し、次のページでは `page` を 1 増やします。 |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | `offset=0` から始め、取得した件数だけオフセットを増やします。 |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | 最初は両方のカーソルを省略します。次のページには返された値を組にしてそのまま渡します。 |
| `GET /api/pixiv/search` | `page` | 最初のページは 1 です。1 未満の値は 1 として扱います。 |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | 次回の `cursor` には `nextCursor` を渡します。プラグインのバージョン一覧では `nextVersionCursor` を使います。 |

ダウンロードタスクの履歴は既定で 1 ページ 50 件、最大 200 件です。保存済み終端結果のみを列挙し、実行中のダウンロードは復元しません。同じ呼び出し元が同じ `requestId` と同じ内容で再送すると、プロセス内に重複判定用の記録がある間は元の受付結果が返ります。終了結果が保存されると、その記録はタスクの終了から 5 分後に失効する場合があります。それ以降の再送で新しいタスクが作成されることがあります。ツール履歴とバッチ結果は別々の保持規則を使います。

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
| `core:api-reference.read` | HTTP API リファレンスの取得と閲覧権限の確認 | GET, HEAD `/api/openapi.json` |
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
