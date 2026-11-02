# ストレージの仕組み

PixivDownloader は、ダウンロード作品、ホストの実行時ファイル、インストール済み外部プラグインを分けて管理します。パスは JAR の場所ではなく、プロセスの**作業ディレクトリ**を基準にします。配布物の起動スクリプトと Windows ショートカットは通常、配布ディレクトリを作業ディレクトリにします。

## 主要ディレクトリ

| 分類 | 既定パス | 内容 |
| --- | --- | --- |
| 設定 | `config/` | ホスト設定、プラグイン設定、暗号化された認証情報 |
| 状態 | `state/` | セットアップ、キューのチェックポイント、GUI マーカー、復旧可能なプラグイン状態 |
| データ | `data/` | SQLite、ユーザーリソース、キャッシュ、永続プラグインデータ |
| プラグイン | `plugins/` | 外部プラグイン、来歴情報、実行時コピー |
| ログ | `log/` | GUI とバックエンドのログ |
| ダウンロード作品 | `{rootFolder}/` | `download.root-folder` が選ぶ保存先 |

`download.root-folder` の既定値は相対パス `pixiv-download` です。ここには作品ファイルを保存します。作品の登録とページごとのメディア記録は、選択したデータベースに保存します。設定、データベース、プラグイン、状態、キャッシュは含めません。

## 作業ディレクトリ内の構成

### 設定

| パス | 用途 |
| --- | --- |
| `config/config.yaml` | ホスト設定と `plugins.{id}.enabled` |
| `config/plugins/{pluginId}.properties` | プラグイン所有の非機密設定 |
| `config/credentials/{pluginId}.properties` | ホストが暗号化し、そのプラグインだけに注入する認証情報 |
| `config/image_classifier.properties` | 画像分類の対象ディレクトリ |

所有者の違うファイルを交換、統合、改名しないでください。[設定リファレンス](/ja/configuration) も参照してください。

### 状態とデータ

| パス | 用途 |
| --- | --- |
| `state/setup_config.json` | 初回設定、動作モード、ログイン状態 |
| `state/download-workbench/batch_state.json` | 一括キューのチェックポイント |
| `state/download-workbench/layout-feedback-state.json` | レイアウトへのフィードバックの重複防止 |
| `state/{pluginId}/` | `RuntimePathProvider` が提供する owner の状態。必要に応じて作成 |
| `state/gui/` | GUI の案内・プロキシ手順マーカー |
| `state/download_root_marker.txt` | 以前に解決された絶対ダウンロード先 |
| `data/pixiv_download.db` | メイン SQLite データベース（実行中は `-wal` / `-shm` も存在） |
| `data/collection_icons/{id}.{ext}` | コレクションアイコン |
| `data/gallery_thumbs/{artworkId}/p{n}-{edge}.{ext}` | 再構築可能なギャラリーサムネイルキャッシュ |
| `data/tts/chromium-version.txt` | Edge TTS の Chromium バージョンキャッシュ |
| `data/novel/narration-voice/{castId}/{characterId}.{ext}` | 登場人物の参照音声 |
| `data/backfill/unreachable.json` | 補完処理で到達できなかった作品 |
| `data/install_identity.txt` | 初回に生成し、以後再利用するインストール UUID |
| `data/delete-staging/` | 削除・公開処理の永続復旧用バックアップ。キャッシュとして消さない |
| `data/database-migrations/` | データベース移行の報告、設定コピー、移行元・先のバックアップ |
| `data/{pluginId}/` | `RuntimePathProvider` が提供するプラグイン所有データ |

状態を削除すると、再セットアップや再ログインが必要になったり、キューのチェックポイントやプラグイン状態が失われたりします。削除前に所有者を確認してください。バックアップ時は実行中の SQLite の `.db` だけをコピーせず、通常どおり終了して WAL も反映させます。

主庫は作品、パス、履歴、インストール済み機能のデータを保存します。プラグインの私有テーブルの schema とライフサイクルは、そのプラグインが管理します。

### 外部プラグイン

`plugins/*.jar` / `plugins/*.zip` はインストール済みの元アーティファクト、`plugins/provenance/` は署名・ダイジェスト・検証結果、`plugins/runtime/` は実行世代ごとの凍結作業領域です。`.preparing/`、`.staging/`、`.transaction-cleanup/` はインストール取引とクラッシュ復旧用です。

来歴ファイルは `plugins/provenance/<artifact>.pixiv-plugin-provenance`、実行時ディレクトリの lease は `plugins/.pixivdownload-runtime.lock` です。システムプロパティ `pixivdownload.plugins-dir` でルートを変更できます。ディレクトリがない場合は自動生成せず、診断を表示してシェルの復旧処理へ進めます。`plugins/runtime/` は生存する generation ごとのランダムな私有領域で、インストール元ではありません。

実行中に `plugins/` 配下を手動で上書き、移動、削除しないでください。インストール、更新、削除、ロールバックはプラグイン管理から行います。`plugins/runtime/` は検証済みアーティファクトから再構築できますが、別プロセス用のダウンロードキャッシュではありません。

## ダウンロード作品の構成

| パス | 内容 |
| --- | --- |
| `{root}/{artworkId}/` | 単作品、URL 一括、検索のダウンロード |
| `{root}/{artist}/{artworkId}/` | 作者単位のダウンロード（`download.user-flat-folder=true` なら作者階層を省略） |
| `{root}/{artworkId}/{filename}_p0.webp` + `..._p0_thumb.jpg` | うごイラの WebP と先頭フレーム |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | マンガシリーズのカバー |
| `{root}/novel-{novelId}/` | 小説の TXT / HTML / EPUB と関連ファイル |
| `{root}/novel-series-{seriesId}/` | 小説シリーズのカバーと任意の結合物 |
| `{root}/douyin/{owner}/...` | Douyin プラグインの既定出力 |
| `{root}/_archives/{token}.zip` | 一時的なクォータ・ギャラリーのエクスポート |

Pixiv 作品のメタデータとページ別の保存形式は選択したデータベース（既定の SQLite では `data/pixiv_download.db`）に保存され、新しいダウンロードでは `.meta.json` や `.media.properties` を作成しません。取得済みの応答からページ情報、元画像の URL、投稿日時や翻訳を保存するため、Pixiv への追加リクエストは発生しません。バックアップや移行では、データベースとメディアファイルを一緒に保管してください。ギャラリーはファイルだけから作品の登録情報を復元できません。既存の付随ファイルは自動で一括削除されません。Ugoira ZIP を保存する場合は、フレーム時間の `.frames.properties` も保存されます。

Douyin の既定ルートは `DownloadSettings.getRootFolder()` に `douyin` を加え、リクエストの owner ごとに分けます。`douyin.download.directory` が空でなければその場所を使い、旧パス `data/douyin/downloads` は使いません。コレクションも既定ルート以外の保存先を選べます。

サードパーティのダウンロード種別も、通常は `download.root-folder` 下のプラグイン ID ディレクトリか、ユーザーがプラグイン設定で明示した作品ディレクトリに保存します。`state/{pluginId}` と `data/{pluginId}` は補助状態・データ用で、作品の保存先ではありません。

## データベースのパス表現

データベースは長い絶対パスを繰り返さず、次の形式で接頭辞を参照します。

```text
{N}/relative/path
```

`N>0` では `path_prefixes` の絶対パスを示します。`download.root-folder` が相対パスなら `{0}/...` を使い、`{0}` は起動ごとに「現在の作業ディレクトリ + 相対ダウンロード先」として解決されます。配布ディレクトリを `pixiv-download/` と一緒に移動すれば履歴を保てます。

絶対パスの保存先は通常の `{N}` プレフィックスを使います。`state/download_root_marker.txt` は前回解決した場所を保存し、設定だけを変えてファイルを移していない状態の検出に使います。

絶対パスのダウンロード先を移動した場合は、GUI のステータスページから「ダウンロードディレクトリを移行」を使います。この操作は設定とデータベースの参照を更新しますが、ディスク上のファイルは移動しません。

## 移行

以下は作品ディレクトリの移動です。SQLite と PostgreSQL の切り替えは[設定リファレンス](/ja/configuration)の検査・移行が必要です。ディレクトリ移動や接続設定の直接編集ではデータベースをコピーしません。

配布物全体を移動する場合は、相対 `download.root-folder` を保ち、アプリを終了して配布ディレクトリ全体を移動し、新しい場所の起動スクリプトから起動します。

ダウンロード先だけを移動する場合は、アプリを通常終了し、ファイルを移動し、GUI の「ダウンロードディレクトリを移行」で新しい場所を選び、必要なら `config.yaml` も更新します。再起動後に履歴、ギャラリー、新しいダウンロードを確認してください。設定を先に書き換えてもファイルは自動移動されません。

## バックアップと復元

完全なバックアップには `config/`、`state/`、アプリ停止後にコピーした `data/`、署名と来歴を含む `plugins/`、`download.root-folder` とコレクションやプラグインが選んだ追加の作品ディレクトリを含めます。`log/` は通常トラブルシューティング用です。

PostgreSQL では、サーバーの主庫とプラグイン私有 schema もバックアップします。ローカルの `data/` のコピーでは代用できません。`data/database-migrations/` と `data/delete-staging/` の復旧データは、対象トランザクションの状態が判明するまで残してください。`data/gallery_thumbs/` と `plugins/runtime/` は他の永続データから再生成できますが、復元を簡単にするため全体バックアップに含めても構いません。

復元後は管理者ログイン、画像・小説の記録、ファイル、スケジュール設定、新しい作品の取得を確認します。外部インポートは元ファイルを参照するため、そのディレクトリもバックアップしてください。

復元時は相対構成を保つか、絶対パスには移行ツールを使います。暗号化された認証情報は元の認証情報マスターキーにも依存するため、別環境へ移す前にキーの互換性を確認してください。互換性がない場合は対象環境で認証情報を再入力します。
