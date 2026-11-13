# 管理者 HTTP API

外部プログラムは管理者ログインのセッションを `X-Session-Token` で送信できます。これは人のセッションであり、既存の失効・保守モードの制限が適用されます。独立したアプリケーションキーや MCP サーバーは提供しません。Pixiv の取得用認証情報は `X-Acquisition-Credential` で別に送ります。

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
