# HTTP API と権限宣言の作成

メソッド、パス、パラメーターは Spring MVC から取得します。説明にはコンパイル時に保存した Javadoc を使えます。例や手動解析する JSON、応答の詳細は `@Operation`、`@Schema`、OpenAPI のリクエスト本文のアノテーションで補います。権限は有効なルート宣言から取得します。新しい操作は `/openapi` で入力、成功ステータス、対象プラグインの制限、主なエラーを確認してください。非同期処理には結果の取得方法も記載します。

Web、Swing、Compose、CLI の業務機能を追加・変更するときは、同じ変更で HTTP API の対応範囲も確認します。呼び出し方法と一覧は [HTTP API](/ja/http-api)、プラグイン契約は [サードパーティープラグイン SDK](/ja/plugin-development) を参照してください。

コントローラーの HTTP 言語リソースで `guide` にグループ共通のガイド URL を指定します。操作固有のページや節は `<Controller>.<method>.<VERB>.guide` で指定します。直接のガイドは `externalDocs`、関連操作が参照するガイドは重複を除いて `x-related-guides` に出力されます。応答から次のリクエストの引数を渡せる場合は、その `@ApiResponse` に `links` を宣言します。

ルートの権限は `x-program-permission` で確認できます。追加の確認がある操作では `x-program-permissions.route` も同じ値です。`discovery` は取得元トークンを取得する操作、`submit` 配列は送信時の固定権限、ヘッダーに必要な権限、またはカタログの参照方法を示します。`x-schedule-sources` では、スケジュール管理には `schedulePermission`、バッチ送信には `batchSubmitPermission` を使います。固定の取得元に手動バッチを送信するプラグインは、取得元の型を `x-manual-batch-source` に指定します。この操作では呼び出し側によるトークン取得は不要です。 インスタンスが出力する OpenAPI 文書のトップレベルにある `x-program-permission-conditions` に、各段階、フィールド、`request#`、`task#`、`batch#`、`item#` の読み方が記載されています。

条件付きの権限では、`when.keyFrom` が指す JSON の値を `when.value` のリテラルと比較します。たとえばギャラリーのエクスポートには、`request#/deleteAfter` がブール値 `true` のとき削除権限も必要です。

`execution` の `executePermissionsByWorkType` を参照し、実行計画が使う作品の種類に応じて権限を付与します。実行時には取得元の権限も再確認します。不足している権限 ID の位置は `reportedIn.pointer` の JSON Pointer で指定されます。送信時は HTTP 403 の本文、受理後は `core:batches.read` で 202 応答の `Location` を取得し、バッチ結果を確認します。ダウンロード例外からは `DownloadTaskException.programPermission()` で既知の不足権限を取得できます。`auth.program.unsupported` の場合は `x-program-alternative` で代替操作を確認してください。

`x-csrf-origin` が `origin-or-referer` の場合、ネイティブクライアントも同一オリジンの Origin または Referer を送信します。`origin-required` の制約は Origin パラメーターを、`conditional` のブラウザーや Cookie に関する条件はヘッダーの説明を参照してください。

名前付きの JSON SSE イベントでは `text/event-stream` の content にイベント DTO の schema を指定し、content の拡張 `x-sse-data-event` にイベント名を設定します。生成されるメディア型は文字列のままで、`x-sse-events` からイベント名で DTO の schema を参照できます。ブラウザー用操作からプログラムキー用の入口を案内するには、操作の `x-program-alternative` に `POST /api/download/tasks` のようにメソッドとパスを指定します。

## 操作の説明と任意の「詳細情報」を書く

コントローラーの `@HttpApiGroup("core-batch")` は、そのモジュールの `src/main/resources/i18n/http/core-batch.properties` を指定します。説明はこのファイルに、訳文は `core-batch_en.properties` など同じディレクトリの言語別ファイルに記入します。ホストは OpenAPI の生成時に文書の言語に応じて読み込みます。

キーの先頭にはコントローラーのクラス名（パッケージ名を除く）、Java のメソッド名、大文字の HTTP メソッドを順に指定します。マッピングで HTTP メソッドを一つに限定していない場合は `ANY` を使います。たとえば `POST /api/download/batches` のタイトルは `ManualBatchController.create.POST.summary`、概要は `.description`、任意の Markdown 本文は `ManualBatchController.create.POST.details` に記入します。

ホストが本文を OpenAPI 操作の `description` に追記すると、`/openapi` には「詳細情報」が折りたたまれて表示されます。見出しを選ぶと全文が開きます。`POST /api/download/tasks` では、有効なダウンロードプロバイダーも `schema.DownloadSubmission.<workType>.description` で種類ごとの説明を追加できます。操作にもプロバイダーにも本文がない場合、この節は表示されません。

執筆前に HTTP の入口から到達可能な処理を最後までたどり、該当する認可、入力検証、プロバイダーの呼び出し、非同期処理、ファイルの書き込み、結果の取得、失敗時の復旧を確認してください。受付時に分かる結果とタスク終了後に分かる結果を明記します。各言語の `/api/openapi.json` を確認し、`/openapi` で該当するメソッドとパスを開いて「詳細情報」が最初は閉じていることを確認し、キーボードで開いて本文を確認してください。

## API が必要な機能

業務状態の取得、設定の読み取り・保存、プリセットの適用、ダウンロード、翻訳、インポート、エクスポート、移行、保守、システム管理には HTTP API を用意します。同等の API があれば業務サービスを再利用し、GUI ごとに実行処理を複製しません。

ウィンドウサイズ、フォーカス、テーマのプレビュー、クリップボード、ファイル選択、ローカルブラウザーの起動はクライアント側の操作です。サーバーで模擬せず、業務処理を分離します。遠隔クライアントのファイルはアップロードが必要です。サーバーパスはサーバーのファイルシステムを指します。

長時間のタスクには開始、状態取得、実行 ID によるキャンセル、必要な結果取得を用意します。202 は受け付けを意味し、キャンセル要求は終了を意味しません。タスクが見つからなくても、安全に再送できるとは限りません。再起動後の状態と冪等性の範囲を明記します。

## 権限の宣言

実際の機能 owner が `PixivFeaturePlugin.routes()` でルートを宣言します。`AccessPolicy` は人間、ゲスト、ローカル操作の規則を維持します。プログラムのアクセスは `WebRouteContribution.programPermissions` で HTTP メソッドごとに明示します。未宣言のアクセスは拒否され、管理者権限を継承しません。

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

owner はホストの登録情報で決まります。`example` の場合、完全な権限 ID は `example:items.read` と `example:items.create` です。ラベルは owner の表示名前空間の i18n リソースに置きます。明示した `/api/` ルートとメソッドのみが対象で、`**`、OPTIONS、GUI/LOCAL 専用フローは使えません。パスの一区間全体の `*` はリソース ID を表せます。

| 業務 | 分離する権限 | 追加の確認 |
| --- | --- | --- |
| 設定・プリセット | 読み取り、検証・保存 | 各フィールドの owner、秘密値を返さないこと |
| メディア保守・ファイルインポート | 読み取り、プレビュー、実行、キャンセル、完了確認 | 正確な publication、プレビュートークン、実行 ID |
| ダウンロード・翻訳 | 取得、作成・再開、キャンセル、記録削除 | データ範囲、資格情報、タスクの所有者 |
| データベース・環境 | 取得、検査、プレビュー、移行、エクスポート、復元 | インストール全体への影響、確認、書き込み停止期間 |
| プラグイン・システム | 取得、インストール、起動・停止、削除、再起動 | 実行コードとプロセスへの影響 |
| キー・管理者資格情報 | 人間の管理者専用 | プログラムによるキー作成や自己権限の昇格を禁止 |

同じ権限のエンドポイントは業務目的とデータ範囲を揃えます。プラグインの選択は現在表示中の権限をまとめて選ぶ操作で、将来の権限を含みません。権限カタログは現在の宣言と能力から生成されます。管理者セッションは GET `/api/security/program-keys/permissions` で ID、owner、ラベル、エンドポイントを取得できます。

共有 API は対象リソースも確認します。設定、メディア、ファイルインポートには core の入口権限と対象プラグインの対応権限の両方が必要です。一覧は対象ごとに絞り込み、一括書き込みは副作用の前に全対象を検査します。データベース移行と環境アーカイブはインストール全体が対象です。単一プラグインの操作とは扱えません。撤回・交代した publication を再利用せず、別 owner の同名ルートから権限を得ないようにします。

## 入力、エラー、検証

メソッド、パス、権限、対象範囲、入力フィールドと上限、非同期状態、主なエラーを記載します。信頼境界でパス、資格情報、JSON を検証します。キーは `Authorization: Bearer` のみで送り、URL、ログ、ブラウザーの永続ストレージに保存しません。送信元 IP は信頼済みプロキシ処理後のクライアントアドレスを使い、生の転送ヘッダーを信用しません。既存の CSRF、復旧モード、プラグインリースは引き続き適用します。

エラーには安定した `code` とローカライズされた `error` を含めます。不正入力、拒否、対象なし、リビジョン競合、上限超過、能力利用不可、結果不明を区別します。通信中断後は結果を確認し、書き込みを自動再送しません。

プラグインが HTTP の入力を自分で検証する場合は、SDK の `top.sywyar.pixivdownload.core.web.HttpParameterException` を使用できます。このクラスは JDK のみに依存する `pixivdownload-core-api` に含まれます。たとえばクエリの `order` が許可された値に該当しない場合は `new HttpParameterException("query", "order", "invalid_value")` をスローします。ホストは HTTP 400 とともに `code=error.request.param.invalid`、リクエストの言語に応じた `error`、`in`、`field`、`reason` を返します。既存のプラグインは従来のエラーレスポンスも引き続き使用できます。 このエラーと各フィールドをインスタンスの OpenAPI に掲載するには、対象の操作に `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")` を付けます。

`in` には `query`、`path`、`header`、`part`、`body` のいずれかを指定します。最初の四つの場合、`field` はリクエストに使うパラメーター名またはパート名です。JSON 本文では `/options/pages` などの JSON Pointer を使います。`reason` には `required`、`type`、`invalid_value` などの機械処理用の値を指定します。これらの三つのフィールドにリクエストの入力値を含めないでください。Spring がバインド時に検出したパラメーターの不足や型変換エラーはホストで位置を特定します。

実際のフィルターまたは HTTP 入口で、権限不足時に業務処理が実行されないことをテストします。メソッド、対象プラグイン、送信元ネットワーク、失効、交代、保存失敗を確認します。API と同時に権限カタログ、呼び出し元、五言語の文書、関連回帰を更新します。宣言やコンパイルだけでは操作・実行の検証になりません。

## 権限の範囲と専用フロー

定期タスクには `core:schedule.*` の対応する操作権限と、取得元プラグインの `schedule.manage` が必要です。アカウントポリシー操作には、そのポリシーの owner の同じ権限が必要です。一覧は許可された取得元に絞られます。ここでの `*` は文書上の分類であり、保存できる権限ではありません。定期タスクはインストールの永続設定で、認可は取得元のスケジュール処理全体を委任します。作成したキーを失効させてもタスクは削除・無効化されません。今後の実行を止めるには、先にタスクを無効化または削除してください。

プラグインのライフサイクル、有効状態、信頼の変更には、対応する `core:plugins.*` 操作と対象の `<plugin>:plugins.manage` が必要です。依存する利用側プラグインがある場合、その認可も確認します。インストール済みの管理可能なプラグインは、未ロードでも選択できます。インストール権限はパッケージと依存関係によるサーバー上のコード実行を許可します。グローバル Hook の公開権限はセキュリティポリシーの変更を許可するため、この影響を踏まえて付与してください。

共有認証情報は `core:credentials.read`、`core:credentials.write`、`core:credentials.use` に分かれます。読み取りでは情報のみを返し、秘密は返しません。`X-Acquisition-Credentials` で保存済み認証情報を参照する場合、業務権限に加えて `credentials.use` が必要です。当該操作の認証情報を直接渡す場合は `X-Acquisition-Credential` を使います。

ギャラリー、小説ライブラリ、コレクション、受信箱、アーカイブ、ページ編集、更新、メンテナンスの権限は共有インストールに適用され、キーが作成したデータだけには限定されません。ダウンロードタスクと手動バッチは引き続きキーの識別情報で分離されます。ローカルメンテナンス、復旧、旧メタデータ移行にはローカル接続元も必要です。権限は接続元、確認、リビジョン、リースの検証を置き換えません。

認証設定、キー管理、GUI token、CAPTCHA、ブラウザー収集チケット、匿名クォータ、ページ組み立ては専用フローを維持します。プログラムはダウンロードと進捗に `/api/download/tasks`、ファイル取り込みに `/api/tools/file-import`、作品管理に `/api/library/artworks/{id}`、ページ操作に `/api/page-editor` を使います。受信箱テストは `/api/admin/notifications/test` または `/test-all` です。旧ブラウザー互換ルートに別のプログラム権限は付与しません。
