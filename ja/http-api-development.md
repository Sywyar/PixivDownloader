# HTTP API と権限宣言の実装

Web、Swing、Compose、CLI の業務機能を追加・変更する際は、対応する HTTP API、プログラムキー権限の宣言、および多言語 OpenAPI ドキュメントの追加・更新が必要かを合わせて確認してください。クライアントからの利用方法と権限一覧は [OpenAPI とプログラムキー](/ja/http-api)、プラグイン拡張契約は[サードパーティープラグイン SDK](/ja/plugin-development) を参照してください。

## HTTP API の提供が必要になる場面

- **業務機能を HTTP で一貫して公開する**：状態照会、設定の読み書き、プリセット適用、ダウンロード、翻訳、インポート、エクスポート、データベース移行、メンテナンス、システム管理はすべて HTTP 経由で実行できるようにします。同等の API がすでにある場合は同じ業務サービスを再利用し、GUI ごとに実行ロジックを複製しないでください。
- **クライアント表示とサーバー業務の分離**：ウィンドウサイズ、フォーカス、テーマのプレビュー、クリップボード、ネイティブファイル選択ダイアログ、ローカルブラウザーの起動はクライアント側の表示処理であり、サーバーで模倣する必要はありません。ファイルを扱う業務フローでは、リモートクライアントのファイルはアップロードで受け取り、サーバー側のパス引数は常にサーバー自身のファイルシステムを指すものとして厳密に区別します。
- **長時間タスクのライフサイクル**：非同期の長時間タスクには開始（`202 Accepted`）、状態照会、実行 ID 指定でのキャンセル、結果照会のエンドポイントを用意し、キャンセル時の終了待ちセマンティクス（キャンセル要求の受理は即時停止を意味せず、タスクが見つからないことは安全な再投入を意味しない）、再起動後の状態、冪等性の範囲を明記します。

## ルートとプログラム権限の宣言

ルートは機能を所有する owner が `PixivFeaturePlugin.routes()` で宣言します。`AccessPolicy` は管理者本人・招待ゲスト・ローカル GUI フローを制御し、プログラムキーによるアクセスは `WebRouteContribution.withProgramPermissions(...)` で HTTP メソッドごとに明示宣言します（未宣言の操作はプログラムキーを拒否し、管理者権限を自動継承しません）：

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

- **権限 ID と対象パス**：完全な権限 ID はホストの登録 owner に基づき `<owner>:<permission>`（例：`example:items.read`）となり、表示ラベルのキーは owner の表示用 i18n リソースに配置します。プログラム権限は明示的な `/api/` ルートと具体的な HTTP メソッドにのみ適用されます（単一パス区間の `*` はリソース ID として使用可。`**`、`OPTIONS`、GUI/LOCAL 専用フローは不可）。
- **権限の粒度と対象リソースの検証**：同一権限を共有するエンドポイントは同じ業務目的とデータ範囲を持つ必要があります。UI でのプラグイン単位の一括選択は現在一覧にある権限だけを選択し、将来の権限に対するワイルドカードは保存しません。共有 API（設定、メディア保守、ファイルインポート、プラグイン管理、スケジュールタスクなど）は `core` の入口権限に加え、副作用を生じる前に対象プラグインや取得元の権限も検証し、読み取り一覧は認可済み対象のみに絞り込みます（業務別の複合権限と専用入口は [OpenAPI とプログラムキー：権限の範囲と専用フロー](/ja/http-api#権限の範囲と専用フロー)を参照）。

| 業務領域 | 権限の分割方針 | 追加の安全検証 |
| --- | --- | --- |
| 設定とプリセット | 読み取り・検証・保存を分離 | 各フィールドの実際の owner を検証。機密値は `stored` フラグのみ返し平文は返さない |
| メディア保守とファイルインポート | 読み取り・プレビュー・実行・キャンセル・完了確認を分離 | 正確なプラグイン publication、プレビュー token、実行 ID を検証 |
| ダウンロードと翻訳 | 照会・作成/再開・キャンセル・履歴削除を分離 | データ範囲、取得用認証情報、タスク作成者の所有権を検証 |
| データベースと環境アーカイブ | 照会・検査・プレビュー・移行・エクスポート・復元を分離 | インストール全体に影響するため、明示確認・プレビュー token・書き込み停止期間を検証 |
| プラグインとシステム管理 | 照会・インストール・有効/無効化・削除・再起動を分離 | コード実行とプロセス再起動を伴うため、対象プラグインと逆依存利用側の両方の権限を検証 |
| キー管理と管理者認証情報 | 管理者本人のログインセッション専用 | プログラムキーによるキー作成・変更・権限昇格を禁止 |

## 多言語ドキュメントと OpenAPI 拡張フィールド

Spring MVC が HTTP メソッド・パス・引数を提供し、コンパイル時に保持される Javadoc、`@Operation`、`@Schema`、リクエストボディ注釈で入力例、手動解析 JSON、応答ステータスを補足します。

### 多言語 API 説明と折りたたみ「詳細情報」

Controller に `@HttpApiGroup("core-batch")` を付けると、そのモジュールの `src/main/resources/i18n/http/core-batch.properties` と各言語ファイル（`core-batch_en.properties` など）が紐付きます。キー接頭辞は `<Controllerクラス名>.<Javaメソッド名>.<大文字HTTPメソッド>`（単一メソッドに限定されないマッピングは `ANY`）です：

| リソースキー | 役割 |
| --- | --- |
| `guide` / `<接頭辞>.guide` | グループ共通または個別操作向けの利用ガイドパス（操作の `externalDocs` に出力され、関連操作のガイドは重複排除のうえ `x-related-guides` に集約） |
| `<接頭辞>.summary` / `<接頭辞>.description` | 操作のタイトルと概要説明 |
| `<接頭辞>.details` | 任意の Markdown 詳細本文（`description` に追記され、`/openapi` では既定で折りたたまれた「詳細情報」として表示） |
| `schema.DownloadSubmission.<workType>.description` | `POST /api/download/tasks` に対して有効な各ダウンロード提供元の種別別説明を補足（操作にも提供元にも詳細がない場合は折りたたみ領域自体を省略） |

執筆時は HTTP 入口から認証・検証・提供元・非同期実行・ディスク保存・結果照会・失敗復旧までの呼び出し経路を通読し、送信時点で判明する結果と非同期完了後に判明する結果を書き分けてください。記述後は各言語で `/api/openapi.json` と `/openapi` を確認し、初期状態で折りたたまれキーボード操作で展開できることを確認します。

### OpenAPI 拡張メタデータ

| 拡張フィールド | 意味と宣言方法 |
| --- | --- |
| `x-program-permission` / `x-program-permissions` | 単一のルート権限は `x-program-permission` に出力。追加検査がある場合は `x-program-permissions` に `route`、`discovery`（取得元トークン取得 API）、`submit`（固定権限、ヘッダー起因権限、カタログ照会条件）、`execution`（`executePermissionsByWorkType` など）を出力。トップレベルの `x-program-permission-conditions` で `request#`、`task#`、`batch#`、`item#` の解決規則と `when.keyFrom` / `when.value` 条件（例：エクスポートで `request#/deleteAfter == true` のときのみ削除権限を要求）、不足権限出力先 `reportedIn.pointer` を定義 |
| `x-schedule-sources` / `x-manual-batch-source` | `x-schedule-sources` は各取得元の `schedulePermission` と `batchSubmitPermission` を列挙。固定取得元の手動バッチを代理送信するプラグイン API は `x-manual-batch-source` で取得元種別を宣言 |
| `x-csrf-origin` | 書き込みリクエストのオリジン検証方針：`origin-or-referer`（ネイティブクライアントも同一オリジンの `Origin` または `Referer` が必要）、`origin-required`、`conditional`（Cookie などのブラウザー信号の有無で区別） |
| `x-program-alternative` | プログラムキー非対応のブラウザー向け API から代替のプログラム用エンドポイント（`<METHOD> <path>` 形式、例：`POST /api/download/tasks`）を案内 |
| `links`（`@ApiResponse`） | 明確な後続呼び出しがあり応答から引数を抽出できる場合に宣言し、`/openapi` の「関連する操作」リンクを生成 |
| `x-sse-data-event` / `x-sse-events` | 名前付き JSON SSE イベントを配信する際、`text/event-stream` の content にイベント DTO schema と `x-sse-data-event` イベント名を指定。生成仕様は文字列転送形式を維持しつつ `x-sse-events` にイベント名ごとの DTO schema を出力 |

## パラメーター検証、エラー契約、テスト検証

- **信頼境界とエラー構造**：パス、認証情報、JSON は信頼境界で検証します。プログラムキーは `Authorization: Bearer` のみで受け取り、URL、ログ、ブラウザーの永続ストレージには保存しません。接続元 IP は `server.trusted-proxy-cidrs` 処理後のクライアントアドレスのみを使用します。エラー応答は安定した機械用 `code` と翻訳済み `error` を返します（ダウンロード送信時の権限不足は `DownloadTaskException.programPermission()` で不足権限 ID を取得可能）。
- `@HttpApiGroup` を付けたコントローラーでは、クラスまたはメソッドに SDK の `@KnownErrorCodes` を付けて、標準 JSON エラーの HTTP ステータスと業務エラーコードを宣言できます。OpenAPI はこの宣言から 4xx または 5xx の `application/json` レスポンスを生成します。独自のフィールドはレスポンススキーマに記載してください。
- **構造化パラメーターエラー（`HttpParameterException`）**：プラグインが HTTP 入口で引数を手動検証する際は、JDK のみに依存する `pixivdownload-core-api` の `top.sywyar.pixivdownload.core.web.HttpParameterException(in, field, reason)` をスローできます（例：クエリ引数 `order` が許容値外のとき `new HttpParameterException("query", "order", "invalid_value")`）。メソッドに `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")` を付けると OpenAPI に出力されます：
  - ホストは HTTP 400、`code = "error.request.param.invalid"`、翻訳済み `error`、および `in`、`field`、`reason` を返します（送信された元の値や認証情報は含めないでください。Spring が処理する必須引数欠落や型変換失敗はホストが自動で位置付けます）。
  - `in` は `query`、`path`、`header`、`part`、`body` のいずれかです。最初の 4 つでは `field` に宣言パラメーター名またはパート名、`body` では JSON Pointer（例：`/options/pages`）を指定し、`reason` には安定した機械値（`required`、`type`、`invalid_value`、`out_of_range` など）を指定します。
- **実経路での検証**：テストは実際のセキュリティフィルターまたは HTTP 入口を通し、権限不足時に業務上の副作用が発生しないことを証明したうえで、HTTP メソッド差、対象プラグイン絞り込み、接続元ネットワーク制限、キー失効、プラグイン世代交代、保存失敗時のロールバックを網羅してください。API 変更時は権限カタログ、呼び出し側、5 言語ドキュメント、回帰テストを同時に更新します。
