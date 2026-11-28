# OpenAPI とプログラムキー

PixivDownloader には対話型 OpenAPI リファレンスと細粒度の**プログラム用アクセスキー**（Program Key）が組み込まれており、スクリプト・自動化ツール・AI エージェントからダウンロード、バッチ、ギャラリー、翻訳、保守 API を安全に呼び出せます（本アプリが提供するのは HTTP API のみで、独立した MCP サーバーは提供しません。新しい API を追加する規約は[HTTP API と権限宣言の実装](/ja/http-api-development)を参照してください）。

## スクリプトやエージェントから API を使う

1. **対話型ドキュメントを確認する**：実行中のインスタンスで `/openapi`（ローカルの既定値は `http://localhost:6999/openapi`）を開きます。アクセス手順はログイン不要で閲覧でき、管理者としてログインすると利用可能な API 一覧、リクエスト例、必要な権限、および各レスポンス一覧の下にある「関連する操作」リンク（`links` / `x-related-operations` / `externalDocs`）から後続操作や利用ガイドへ移動できます。
2. **プログラム用アクセスキーを作成する**：デスクトップの「セキュリティ → プログラム用アクセスキー」でキーを作成します（「設定 → アクセス制御」からも移動できます）。デスクトップがない環境では[コマンドライン](#コマンドラインでキーを管理する)で作成します。許可する接続元 IP/CIDR と必要な操作権限を選択します。スクリプトやエージェント自身が API 仕様を取得する場合は `core:api-reference.read` も選択してください。
3. **API の呼び出しとエージェント用プロンプト**：クライアントは `Authorization: Bearer <プログラムキー>` を付けて `GET /api/openapi.json`（権限確認のみなら `HEAD /api/openapi.json`）を呼び出すことで機械可読の OpenAPI JSON を取得できます。エージェントを使う場合は、キー作成後、またはキー一覧の「エージェント用プロンプト」からプロンプトをコピーしてドキュメント URL をエージェントに渡し、秘密キー本体はエージェントの環境へ別途設定してください。Agent Skills に対応したエージェントには、代わりに[スキルをインストール](#ai-エージェントにスキルをインストールする)すると、関連する作業で自動的に使われます。

### AI エージェントにスキルをインストールする

メインリポジトリでは [Agent Skills](https://agentskills.io/) 形式の `pixivdownloader` スキルを提供しています。インスタンスへの接続、プログラム用アクセスキーの使い方、よく使う API（Pixiv の閲覧、作品のダウンロード、ダウンロード済み作品の検索）、エラーへの対処をエージェントに説明します。インストールすると、Agent Skills に対応したエージェント（Claude Code、Codex、Cursor、GitHub Copilot、Gemini CLI など）が関連する作業で自動的に使うため、毎回プロンプトを貼り付ける必要はありません。

1. スキルをインストールします（Node.js が必要です）。コマンドはインストール先のエージェントを尋ねます。`-g` を付けるとすべてのプロジェクトで使えます：

   ```bash
   npx skills add Sywyar/PixivDownloader --skill pixivdownloader -g
   ```

   新しいバージョンは後から `npx skills update -g` で取得します。
2. エージェントの実行環境で `PIXIV_PROGRAM_KEY` にプログラム用アクセスキーを設定します。インスタンスが `http://localhost:6999` 以外にある場合は、`PIXIV_BASE_URL` にそのアドレスを設定するか、会話でエージェントに伝えてください。キーは会話に貼り付けないでください。
3. 作業に必要な権限をキーに付与します。エージェントは最初に `/self` で付与された権限を確認します。`core:api-reference.read` も付与すると、完全な API カタログを参照できます。

?> スキルに書かれているのは、長く変わらない約束事とよく使う API だけです。フィールド、パラメーター、値はインスタンスの `GET /api/openapi.json` に従います。Pixiv を閲覧する API は `pixiv-content` プラグインが提供します。未インストールまたは無効の場合は、エージェントがインストールか有効化を求めます。

### 作者とタグで Pixiv の投稿を検索する

作品タグごとに `tag` を指定します。例えば `GET /api/pixiv-content/users/28329224/artworks?tag=furry&tag=original` は、この作者の投稿のうち両方のタグが付いた作品を返します。小説のパスは `/users/{id}/novels` です。異なるタグは 10 個まで指定できます。複数タグのすべてで個別の該当作品数が 2048 件を超えると、`422 PIXIV_TAG_SEARCH_TOO_BROAD` が返ります。

ニックネームとタグをまとめて検索するには `GET /api/pixiv-content/search/users-and-tags?word=cat&userPage=1&tagPage=1` を使います。`users` と `tags` はそれぞれ 1 ページ目から個別にページを指定します。Pixiv がログインを求める場合は取得用の認証情報が必要です。

### コマンドラインでキーを管理する

デスクトップがない環境（サーバー、Docker）では、コマンドラインでキーを管理します。コマンドは実行中のインスタンスに接続し、管理者としてログインして後述の管理 API を呼び出し、最後にログアウトして終了するため、サービスを停止する必要はありません。権限カタログは読み込まれたプラグインからインスタンスが生成するため、インスタンスは実行中である必要があります。

| コマンド | 用途 |
| --- | --- |
| `--program-keys` | キーを一覧表示：名前、ID、権限数、接続元、使用日時 |
| `--program-key-permissions` | このインスタンスで付与できる権限と、対象となる API を一覧表示 |
| `--program-key-create` | キーを作成。キー全体は出力に一度だけ表示されます |
| `--program-key-revoke --key-id=<ID>` | 確認後にキーを失効 |

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --program-key-create
java -Dfile.encoding=UTF-8 -jar APP.jar --program-key-create --key-name=agent --key-permissions=core:api-reference.read,core:downloads.read --key-sources=127.0.0.1
```

- `--key-name`、`--key-permissions`、`--key-sources` を省略すると順に入力を求められ、権限は一覧の番号で選べます。`--key-sources` には `any`、またはカンマ区切りの IP アドレス / CIDR を指定します。
- 管理者アカウントは `--username` と `--password` で渡すか、プロンプトで入力します。コマンドラインのパスワードはシェル履歴やプロセス一覧に残るため、普段はプロンプト入力を使ってください。
- インスタンスのアドレスは既定で `config/config.yaml` の `server.port`、`server.ssl.enabled`、`ssl.domain` から導出し、`--server-url=https://<ホスト>:<ポート>` で指定することもできます。コマンドはシステムプロキシを使わず、リダイレクトにも従いません。インスタンスが HTTP を HTTPS へリダイレクトする場合は、表示された HTTPS アドレスを指定してください。自己署名証明書を使う場合は、先に証明書を Java のトラストストアへインポートします。
- Docker Compose では、新しいコンテナーで実行し、サービス名を指定します：

  ```text
  docker compose run --rm app --program-key-create --server-url=http://app:6999
  ```

- インスタンスがログイン時の追加確認（CAPTCHA など）を求める場合、コマンドはログインできず、サーバーが返した理由を表示します。
- 終了コード：`0` 成功、`1` 接続・ログインの失敗またはサーバーによる拒否、`2` 引数の誤りまたはキャンセル。作成や失効のリクエスト送信後に接続が切れた場合は変更が反映されている可能性があるため、再試行する前に `--program-keys` で確認してください。

### キーのセキュリティ、接続元制限、CSRF ルール

- **一度限りのキー表示と上限**：完全なキー文字列は作成成功時に一度だけ表示され、サーバーにはダイジェストのみが保存されます（既定で有効期限はなく、権限や接続元の変更および失効がいつでも可能です。プラグイン全体の一括選択はその時点で一覧にある権限のみを付与し、将来の新規権限を自動付与しません）。1 インスタンスあたり最大 128 キー（名前は最大 80 文字、1 キーあたり最大 512 権限・64 接続元ネットワーク、管理リクエスト本文は最大 128 KiB）を作成できます。
- **HTTPS と接続元 IP 制限**：ローカルループバックまたはローカルネットワーク外からの平文 HTTP によるプログラムキー要求は既定で拒否されます（外部 HTTP を許可するにはキーごとにリスクを確認したうえで明示承認が必要で、後から権限や接続元を編集すると HTTPS 必須に戻ります。拒否された場合でも平文送信されたキーは漏洩した可能性があるため失効させてください）。接続元制限には IPv4、IPv6、CIDR を指定でき、制限モードでは 1 件以上の入力が必要です（「すべての接続元」は明示選択が必要。リバースプロキシ配下では先に `server.trusted-proxy-cidrs` を設定してください）。
- **認証の分離とステータスコード**：同一リクエストでプログラムキーと管理者セッション（`X-Session-Token` / Cookie）、招待コード、ローカル GUI token を併用しないでください（取得用認証情報は `X-Acquisition-Credential` または `X-Acquisition-Credentials` で独立して渡します）。
  - プログラムキー非対応の操作（匿名アクセス可能な操作を含む）に有効な Bearer キーを送ると `403 auth.program.unsupported` が返ります（`x-program-alternative` で代替エンドポイントを確認してください）。無効なキーや認証情報の混在では `401 auth.program.invalid` が返ります。
  - `/api/gui/**`、キー管理、管理者認証情報の変更はローカル GUI または管理者本人のセッション専用です。
- **同一オリジン信号（`x-csrf-origin`）**：Cookie を伴う書き込みは同一オリジン検査を通過する必要があります。Bearer 認証の書き込みでも各操作の `x-csrf-origin` を確認してください。たとえば `POST /api/schedule/tasks`（`origin-or-referer`）は同一オリジンの `Origin` または `Referer` が必須ですが、ブラウザー Cookie を持たないネイティブ Bearer クライアントが `POST /api/download/tasks` や `POST /api/download/batches` を呼ぶ場合は両ヘッダーを省略できます。
- **自己確認、管理 API、キーのローテーション**：
  - `GET /api/security/program-keys/self` を呼ぶと、追加の業務権限なしで現在のキーの `id` と `permissions`（空配列も可）を確認できます。
  - 管理者セッションでは `GET /api/security/program-keys`（一覧、秘密やダイジェストは含まない）、`GET /api/security/program-keys/permissions`（現在有効な権限カタログ）、`POST /api/security/program-keys`（`name`、`permissions`、`anyIp`、`networks` で作成し、`key` と一度限りの `secret` を返却）、`PUT /api/security/program-keys/{id}`（`{"revision": 1, "policy": {...}}` で編集）、`DELETE /api/security/program-keys/{id}`（`{"revision": 1}` で失効）を使用できます。
  - **ローテーション手順**：新キー作成 → クライアントを切り替えて `/self` で確認 → 旧キーを失効。旧キーで作成したダウンロードタスクやバッチは新キーから参照できないため、失効前に完了までポーリングするか管理者セッションで追跡してください（失効は新規要求のみを拒否し、受付済みタスクは自動キャンセルしません）。

## エンドポイント一覧

同じ行の短縮パスは先頭の共通プレフィックスに続きます。翻訳（`/api/translation/**`）、用語集、読み上げ、ギャラリー、コレクション、スケジュール、保守、プラグインマーケットの完全なパスは下部の[プログラムの権限一覧](#プログラムの権限一覧)または `/openapi` を参照してください。

| 機能 | 主なエンドポイント |
| --- | --- |
| 設定項目・既定値・秘匿済み現在値とプリセット | `GET /api/settings`、`GET /api/settings/presets` |
| 設定の検証と保存 | `POST /api/settings/validate`、`POST /api/settings` |
| プラグインリポジトリ設定 | `GET` / `PUT /api/settings/repositories` |
| データベース設定・接続テスト・移行 | `GET /api/settings/database`、`/providers`、`/status`、`/migration`；`POST /check`、`/migration-previews`、`/migrations` |
| 環境アーカイブのエクスポートと復元 | `POST /api/settings/environment/preview`、`/restore`、`/exports`；`GET /restore`、`/exports`、`/exports/{id}/archive`、`/installation` |
| メタデータ補完 | `GET /api/tools/backfill/defaults`、`/tasks/current`、`/tasks/{id}`；`POST /candidates`、`/tasks`、`/tasks/{id}/cancel` |
| メディア保守と記録ファイルのインポート | `GET /api/tools/media`、`/api/tools/file-import`（各配下の `POST /previews`、`/tasks`、`/tasks/cancel`、`GET /tasks/current`）；`POST /api/tools/media/capabilities`；`POST /api/tools/file-import/completion/acknowledge` |
| ローカル作品インポートと起動キャッシュ | `GET /api/local-import/presets`、`POST /previews`、`POST /tasks`；`GET /api/tools/startup-cache`、`POST /tasks`、`POST /tasks/{id}/cancel` |
| ディレクトリ参照とパスプレフィックス | `GET` / `POST /api/tools/directories` · `/api/tools/path-prefixes` |
| FFmpeg の状態・インストール・キャンセル、ツール履歴とログ | `GET /api/tools/ffmpeg`、`POST /api/tools/ffmpeg/tasks`、`POST /api/tools/ffmpeg/tasks/{executionId}/cancel`；`GET /api/tools/history`、`/api/tools/logs/{fileName}` |
| システム・コントロールセンター・更新 | `/api/admin/system`、`/api/admin/control-center`；`/api/admin/update/check`、`/last`、`/download`、`/download/progress`、`/install` |
| AI・メール・プッシュ・サイト内通知テスト | `/api/admin/ai/test`、`/models`；`/api/admin/mail/test`、`/test-all`；`/api/admin/push/test`、`/test-all`；`/api/admin/notifications/test`、`/test-all` |
| 個別ダウンロード・履歴・手動バッチ | `/api/download/tasks`、`/api/download/tasks/history`；`/api/download/batches` |

復元状態に含まれる `jobId` でアプリのログを検索すると、その復元が失敗した理由を確認できます。確定後、復元を実行する前に復元先が変わった場合、`failureCode` は `transfer.target-changed` になります。再度プレビューして確定してください。

`POST /api/tools/path-prefixes` はプレフィックスの変更と登録を同じトランザクションで確定します。`registerPaths` の各要素には、既存ディレクトリの絶対パスを文字列で指定してください。成功時の `registered` は新規登録件数、`alreadyRegistered` は再利用件数、`applied` は変更件数と新規登録件数の合計です。無効な要素があると 400 と `/registerPaths/i`（`i` は 0 始まり）を返します。書き込みに失敗すると `500 PATH_PREFIX_WRITE_FAILED` を返し、すべての変更を取り消します。

Windows のドライブパスと UNC パスは、大文字・小文字や区切り文字が違っても同じ ID を再利用します。パスに単独の `.` が含まれていても同じプレフィックス ID を使います。POSIX パスでは大文字と小文字を区別します。過去の重複登録がある場合も、編集の対象は指定した ID を参照するレコードだけです。`POST /api/tools/path-prefixes/pin` の書き込みに失敗すると `500 PATH_PREFIX_WRITE_FAILED` が返り、元の参照が保持されます。

画像分類ツールの提供は終了しました。次の API は従来どおりアクセス権を確認し、許可されたリクエストには本文のない `410 Gone` を返します。

- `GET, PUT /api/tools/classifier/settings`
- `GET, DELETE /api/tools/classifier/folders`
- `GET /api/tools/classifier/images`
- `GET /api/tools/classifier/image`
- `POST /api/tools/classifier/moves`
- `GET /api/downloaded/by-move-folder`
- `POST /api/downloaded/move/{artworkId}`
- `POST /api/library/artworks/{artworkId}/move`

保存済みタスクを照会またはキャンセルするとき、所有者を確認できても記録を読み取れない場合は `503 DOWNLOAD_TASK_RECEIPT_UNAVAILABLE` を返します。他の利用者のタスクと存在しないタスクはどちらも 404 です。

## 設定の保存

`POST /api/settings/validate` は変更対象の設定と認証情報だけを読み取って入力を検証します。設定の保存や認証情報の移行は行いません。

まず `GET /api/settings` で項目一覧を取得し、返された `owner`、`packageId`、`generation`、`key` を指定して変更を送信します（ホスト設定は `owner` と `packageId` が `null`、`generation` が `0`）。機密項目は真偽値の `stored` フラグのみを返し、平文や既定の秘密値は返しません。`action`（`KEEP`、`SET`、`CLEAR`）で保持・置換・消去を明示します。応答の `effect` は反映条件を示します（保存してもバックエンドやプロセスは自動再起動しません）：

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

プリセットも同じ保存 API で適用します。プラグインリポジトリの変更（`PUT /api/settings/repositories`）には `GET` で取得した `revision` が必要で、リポジトリ追加や信頼・URL・ネットワーク方針の変更時は `"trustConfirmed": true` を指定します（リビジョン競合時は再取得してください）。データベース接続テストとツールの一時オプションはその操作にのみ適用され、設定ファイルには保存されません。

## バックグラウンド手動バッチ

1 プロセスあたり同時に保持できる手動バッチは 1 件です（クライアント切断後もバックグラウンドで継続しますが、プロセス再起動後には復元されません。新規作成前に待機中の旧バッチを削除してください）：

1. **利用可能な取得元の取得**：`GET /api/schedule/sources` で現在の `sourceType` と `activationToken` を取得します（定義形式は取得元プラグインが解釈し、`explicit-list`、作者投稿、リクエスト、検索、シリーズ、ブックマーク、フォロー新着、コレクションに対応）。
2. **バッチの作成**：`POST /api/download/batches` に `name`、`sourceType`、`activationToken`、`definition`（JSON オブジェクト、UTF-8 シリアライズ後で最大 1 MiB、リクエスト全体で最大 8 MiB）、任意の `proxy` を送信します。送信時の `403` では特定できた不足権限が `programPermission` に返り、`202` 受理後は `core:batches.read` で `Location` を参照します（バックグラウンド実行時の権限不足もバッチ結果の `programPermission` に記録されます）。
3. **状態と項目のポーリング**：`GET /api/download/batches/{id}` でバッチ全体の状態（`state` で段階を判定。`code` は任意の理由コードで、`batch.work-failed` なら `items` を確認、作者バッチの `schedule.pixiv.discovery-not-found` なら作者 ID を確認）を取得し、`GET /api/download/batches/{id}/items?offset=0&limit=100` で項目一覧（1 ページ最大 200 件。項目識別子はバッチ UUID + `workType` + `id` で、個別ダウンロードの実行 UUID とは別）を取得します。
4. **制御と再開**：`POST /api/download/batches/{id}/pause`、`/cancel`、`/resume`、`/retry` で一時停止、キャンセル、走査再開、発見済み失敗項目のみの再試行を行います（停止中は実行中の処理が実際に終了してから終端状態に遷移し、完了済みやフィルター除外済みの作品は再開時に再実行されません）。
5. **認証情報の差し替え・保留項目の解決・削除**：
   - 待機中に `PUT /api/download/batches/{id}/credentials`（本文 `{"expectedRun": 1}`、最大 16 KiB。新しい認証情報はリクエストヘッダーで渡し、ヘッダー省略で消去。再開前に再検証）を呼び出します。
   - `POST /api/download/batches/{id}/resolve` で保留項目を解決します（本文に `expectedRun`、`workType`、`workId`、`reasonCode`、`userAction`、`rememberForRun` を指定、最大 16 KiB。理由と実行回が現在の記録と一致する必要あり）。 `DOWNLOAD_PATH_ACTION_REQUIRED` では `TRUNCATE`、`DEFAULT_NAME`、`CANCEL` のいずれかを指定してください。それ以外の値は 400 となり、`/userAction` が示されます。作品は確認待ちのままなので、値を修正して再送できます。
   - 待機中に `DELETE /api/download/batches/{id}` でバッチ記録を削除します。

1 バッチの上限は 5,000 項目または符号化内容の予約予算 64 MiB のうち先に到達した方です。フィルターは年齢区分、AI、タグ、イラスト種別、ページ数、小説文字数、最小ブックマーク数に対応します（ブックマーク数フィルター有効時、ブックマーク数不明の作品は通過しません）。

- **検索のページ取得間隔（`pageDelayMs`）**：`search` 取得元では `download.pageDelayMs` で連続するページ要求開始時刻の最短間隔（ミリ秒。省略時は固定ページ数で `0`、`maxPages = -1` で `10000`。明示的に `0` を指定すると追加待機なし）を指定できます。手動バッチでは `definition` に JSON オブジェクトとして渡し、スケジュールタスクでは `definitionJson` 文字列にシリアライズします（共有ダウンロード設定セットでは `settings.pageDelayMs` を使い、既定値は `null`）：

  ```json
  {"source":{"word":"landscape","maxPages":3},"download":{"pageDelayMs":5000}}
  ```

- **イラスト・小説混在リスト（`explicit-list`）の `definition` 例**（作品 ID は正の整数の 10 進文字列で同一種別内の重複不可。サンプルの ID は実際の作品に置き換えてください。`/definition/source/acquisitionMode` が欠けている場合はバッチ作成前に 400 が返ります）：

  ```json
  {
    "kind": "mixed",
    "source": {
      "acquisitionMode": "single-import",
      "works": [
        {"kind": "illust", "id": "123"},
        {"kind": "novel", "id": "456"}
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

## ツール、ギャラリー一括操作、再起動操作

ツール API のパス引数はすべてリモートクライアントではなく**バックエンドが動作するサーバーのローカルファイルシステム**を指します：

- **メディア保守と記録ファイルのインポート**：先に一覧を `GET` し、その正確な `identity`（`pluginId`、`packageId`、`generation`、`publication`）と `options` でプレビューします。実行開始には同じ `identity` とプレビュー `token`、キャンセルには加えて `executionId` が必要です（プラグインの世代交代やデスクトップ側での代替タスク開始後は古い token/UUID は無効になります）。
- **メタデータ補完と手動復元**：`POST /api/tools/backfill/tasks`（本文最大 4 KiB、`limit: 0` は全候補、`202` は受付完了）：

  ```json
  {
    "delayMs": 0,
    "artworkIds": [12345],
    "dryRun": true,
    "alwaysNetwork": false
  }
  ```

  単一イラストの未設定項目を呼び出し側の値で直接補う場合は `POST /api/library/artworks/{artworkId}/recover-metadata` を使用します（既存の非空値は保持し、本文欠落や有効項目なしは 400。Pixiv からタグやシリーズを取得する場合は作品 ID を指定してデータ補完を実行してください。[インポート後のメタデータ補完](/ja/local-import?id=metadata-backfill)を参照）。
- **ギャラリーのファイル管理絞り込みと一括エクスポート/削除**：
  - `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` で外部読み取り専用参照の作品を絞り込めます（削除時も元ファイルを残して登録のみ削除）。`MANAGED` はアプリ管理ファイルを示します（削除時にファイルも削除し、重複判定用に `deleted=true` を保持）。
  - イラスト・小説の一括 API は `"mode": "filter"`（全ページ対象、`filter` は JSON オブジェクト必須）または `ids` 配列（`42` のような正の JSON 整数のみ。小数・文字列・0・負数は HTTP 400 と `field` を返却）を受け付けます。`"filter": {}` またはページ指定のみの `filter` は全作品選択となるため、削除や「エクスポート後に削除」では `"confirmAll": true` と該当ギャラリーの削除権限が必須です。
  - エクスポートで `archiveToken` が返ったら、`core:archives.read` で `GET /api/archive/status/{token}` をポーリングし、`ready` 後に `core:archives.download` で `GET /api/archive/download/{token}` を取得します。
- **起動キャッシュ、データベース移行、環境アーカイブ**：
  - 起動キャッシュの学習は `POST /api/tools/startup-cache/tasks`（本文 `{}`）で開始し、`GET /api/tools/startup-cache` で状態を確認します。
  - データベース移行と環境復元はプレビューが返した `token` で確定します（環境復元は `RESTORE_PENDING` の後に代替プロセスが実行）。環境エクスポート（`POST /api/settings/environment/exports`）にはサーバーの絶対パス `destination`（既存ファイルは上書きしない）と `"confirmRestart": true` が必須で、認証情報・データベース・状態を含める場合は `password` が必要です（`includeResources` には `includeDatabases` も必要）。エクスポートはバックエンドを停止して完全再起動を要求するため、再起動失敗時は手動で起動してください。オンライン更新のインストールには検証済みパッケージの SHA-256 が必要です。

## エラー処理、ポーリング、ページネーション

エラー応答には機械用 `code` と翻訳済み `error` が含まれます（`400` 不正入力、`401`/`403` 認証・権限拒否、`404` 対象不在、`409` 使用中・リビジョン競合・状態不許可、`413` 本文サイズ超過、`503` プラグインやデータベース機能の利用不可）。すべての非 2xx を自動再試行せず、業務状態に応じて処理してください。

- **単一作品のダウンロード送信とポーリング（`POST /api/download/tasks`）**：
  - OpenAPI の `x-download-submissions` から有効な `workType` を選びます（未公開の型は `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE`、上流のメタデータ照会で作品が見つからない場合は初回送信で `404 WORK_NOT_FOUND`、呼び出し元から参照できないタスク ID の照会は `404 DOWNLOAD_TASK_NOT_FOUND`）。
  - 引数エラー時は `in`、`field`（本文は `/options/pages` のような JSON Pointer、クエリ・パス・ヘッダー・multipart は宣言パラメーター名）、`reason`（`out_of_range` など）で修正箇所を特定できます。
  - `202` 応答後は `Location`（パス引数 `taskId` は受付結果の `task.attempt.attemptId`）をポーリングします。最初は **2 秒**間隔で開始し、`revision` が変化しない間は最大 **10 秒**まで徐々に延ばし、更新があれば 2 秒に戻して、`phase` が `COMPLETED`、`FAILED`、`CANCELLED` になったら終了します（`Retry-After` がある場合は指定時間以上待機）。`progress` が非 null の場合は同一単位（`PAGES`、`IMAGES`、`BYTES`）の `progress.unit`、`completed`、`total` を読み取れます。
  - **冪等性と履歴**：同一呼び出し元が同じ `requestId` で同一コマンドを再送すると、プロセス内の冪等記録が有効な間（終端結果の保存から約 5 分間）は元の受付結果を返します。`GET /api/download/tasks/history` は保存済みの終端結果のみを返し（既定 50 件、最大 200 件）、実行中のダウンロードは復元しません。

| データ項目 | 取得・送信時の形式 |
| --- | --- |
| Pixiv 作品 ID | ギャラリーの `artworkId` とエクスポートの `ids[]` は JSON 整数。Pixiv 検索の `id`、タスクの `workId`、バッチの `key.id` は 10 進文字列（変換時は 64 ビット整数の精度を維持。他プロバイダーの `workId` は各プラグインが定義） |
| ダウンロード設定の型 | 単一タスクの `options` は文字列値（`{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`）、手動バッチの `definition.download` はネイティブ JSON 型（`{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`） |
| 時刻とパス | タスクの `updatedAt` は ISO 8601、バッチの `createdTime`・ギャラリーの `time`・スケジュールの `nextRunTime` は Unix ミリ秒。タスクの `outputPath` は出力予定のサーバーディレクトリ、ギャラリーの `folder` は実際の登録先で、作品 ID で照合 |
| 主なレスポンス構造 | `GET /api/authors` は JSON 配列、`GET /api/authors/paged` は `content` を持つページオブジェクト、`GET /api/downloaded/statistics` は `success` と各件数を持つオブジェクト |

| ページ分割エンドポイント | パラメーター | 次のページの取得方法 |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | 最初は `page=0` を指定し、以降 `page` を `1` ずつ増やす |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | `offset=0` から開始し、返された件数分だけ `offset` を増やす |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | 初回は両カーソルを省略し、次ページでは前回の `beforeTime` と `beforeId` をそのまま渡す |
| `GET /api/pixiv/search` | `page` | `page=1` から開始（`1` 未満は 1 ページ目として扱う） |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | 応答の `nextCursor` を次回の `cursor` に指定（プラグインのバージョン一覧では `nextVersionCursor`） |

## プログラムの権限一覧

現在のインストールで有効な権限カタログは、管理者が `GET /api/security/program-keys/permissions` で取得できます。キーには明示的な権限 ID のみを保存でき、プラグインやパスのワイルドカードは指定できません（下表の `*` は単一のパス区間を表します）：

### 権限の範囲と専用フロー

- **複合権限の検証**：
  - **共有設定、メディア保守、ファイルインポート**：`core` の入口権限と対象プラグインの同名権限の両方が必要です（一覧は認可された対象のみに絞られます）。
  - **個別ダウンロードと手動バッチ**：個別ダウンロードには `core:downloads.create` + 対象ダウンロード種類プラグインの `<plugin>:downloads.create` が必要です。手動バッチには `core:batches.create`（または `core:batches.resume`）+ 取得元プラグインおよび実行計画に含まれる全作品プラグインの `<plugin>:batches.execute` が必要です（取得元カタログ参照には `core:schedule.sources.read`）。
  - **永続スケジュールタスク**：`core:schedule.*` の各操作権限 + 取得元プラグインの `<plugin>:schedule.manage` が必要です（アカウントポリシー操作にはポリシー owner の同名権限が必要。スケジュールはインストール全体の永続設定であり、作成元のキーを失効させても削除・無効化されません）。
  - **プラグインのライフサイクルとセキュリティポリシー**：`core:plugins.*` の各操作権限 + 対象プラグインの `<plugin>:plugins.manage` が必要です（逆依存の利用側プラグインがある場合はその権限も確認。`core:plugins.install` と `plugin-market:install` はサーバー上でのコード実行を許可し、`core:hooks.publish` はグローバル操作保護ポリシーを変更できるため慎重に付与してください）。
  - **取得用認証情報**：`core:credentials.read` は名前と状態のみを返し秘密値は返しません。`X-Acquisition-Credentials` ヘッダーで保存済み共有認証情報を参照する場合は、業務権限に加えて `core:credentials.use` が必要です（その回限りの認証情報を直接渡す場合は `X-Acquisition-Credential`）。
- **データ分離とインストール全体のスコープ**：ダウンロードタスクと手動バッチはキーの識別情報ごとに分離されます（各キーは自身が作成したタスクとバッチのみを照会・キャンセル・管理でき、管理者は全体を参照可能。ただしプロセスあたり 1 件の手動バッチ枠は全キーで共有します）。ギャラリー、小説ライブラリ、コレクション、翻訳、受信箱、アーカイブ、ページ編集、設定、ログ、更新、保守、データベース移行、環境アーカイブは共有インストールに直接作用し、キーごとの専用コピーではありません。ローカル専用の保守・復旧 API は引き続きローカルループバック接続元を必須とします。
- **プログラム専用エンドポイント**：初期認証設定、キー管理、GUI token、CAPTCHA 対話、ブラウザー収集チケット、匿名クォータ、ページ組み立ては専用フローを維持し、旧ブラウザー互換ルートにはプログラム権限を付与しません。プログラムからは `/api/download/tasks`（ダウンロードと進捗）、`/api/tools/file-import`（記録ファイルインポート）、`/api/library/artworks/{id}`（作品管理）、`/api/page-editor`（ページ編集）、`/api/admin/notifications/test` または `/test-all`（サイト内通知テスト）を使用してください。

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
| `core:classifier.configure` | 画像分類設定を変更 (提供終了, HTTP 410) | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 画像分類ディレクトリを削除 (提供終了, HTTP 410) | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 画像分類と画像を表示 (提供終了, HTTP 410) | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 分類した画像を移動 (提供終了, HTTP 410) | POST `/api/tools/classifier/moves` |
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
| `core:ffmpeg.cancel` | FFmpeg のインストールをキャンセル | POST `/api/tools/ffmpeg/tasks/{executionId}/cancel` |
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
| `core:library.move` | 作品の移動記録（提供終了） · HTTP 410 | POST `/api/library/artworks/*/move` |
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
| `<plugin>:schedule.manage` | このプラグインの永続スケジュールと認証情報ポリシーを管理 | `core:schedule.*`。POST `/api/pixiv/user/{userId}/meta/schedule` で、このプラグインを取得元とするタスクの `taskId` を指定する場合も必要です。 |
| `<plugin>:plugins.manage` | このプラグインのライフサイクル、有効状態、信頼の管理を許可 | `core:plugins.*` |
