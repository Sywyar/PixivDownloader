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

`download.root-folder` の既定値は相対パス `pixiv-download` です。ここには作品、メタデータのサイドカー、一時的なエクスポートアーカイブだけを置きます。設定、データベース、プラグイン、状態、キャッシュは含めません。

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
| `state/gui/` | GUI の案内・プロキシ手順マーカー |
| `state/download_root_marker.txt` | 以前に解決された絶対ダウンロード先 |
| `data/pixiv_download.db` | メイン SQLite データベース（実行中は `-wal` / `-shm` も存在） |
| `data/collection_icons/` | コレクションアイコン |
| `data/gallery_thumbs/` | 再構築可能なギャラリーサムネイルキャッシュ |
| `data/{pluginId}/` | `RuntimePathProvider` が提供するプラグイン所有データ |

状態を削除すると、再セットアップや再ログインが必要になったり、キューのチェックポイントやプラグイン状態が失われたりします。削除前に所有者を確認してください。バックアップ時は実行中の SQLite の `.db` だけをコピーせず、通常どおり終了して WAL も反映させます。

### 外部プラグイン

`plugins/*.jar` / `plugins/*.zip` はインストール済みの元アーティファクト、`plugins/provenance/` は署名・ダイジェスト・検証結果、`plugins/runtime/` は実行世代ごとの凍結作業領域です。`.preparing/`、`.staging/`、`.transaction-cleanup/` はインストール取引とクラッシュ復旧用です。

実行中に `plugins/` 配下を手動で上書き、移動、削除しないでください。インストール、更新、削除、ロールバックはプラグイン管理から行います。`plugins/runtime/` は検証済みアーティファクトから再構築できますが、別プロセス用のダウンロードキャッシュではありません。

## ダウンロード作品の構成

| パス | 内容 |
| --- | --- |
| `{root}/{artworkId}/` | 単作品、URL 一括、検索のダウンロード |
| `{root}/{artist}/{artworkId}/` | 作者単位のダウンロード（`download.user-flat-folder=true` なら作者階層を省略） |
| `{root}/{artworkId}/{filename}_p0.webp` + `..._p0_thumb.jpg` | うごイラの WebP と先頭フレーム |
| `{root}/{artworkId}/{artworkId}.meta.json` | 取得済みデータから生成した構造化 Pixiv メタデータ |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | マンガシリーズのカバー |
| `{root}/novel-{novelId}/` | 小説の TXT / HTML / EPUB と関連ファイル |
| `{root}/novel-series-{seriesId}/` | 小説シリーズのカバーと任意の結合物 |
| `{root}/douyin/{owner}/...` | Douyin プラグインの既定出力 |
| `{root}/_archives/{token}.zip` | 一時的なクォータ・ギャラリーのエクスポート |

サードパーティのダウンロード種別も、通常は `download.root-folder` 下のプラグイン ID ディレクトリか、プラグイン設定で選択した作業ディレクトリに保存します。`state/{pluginId}` と `data/{pluginId}` は補助状態・データ用で、作品の保存先ではありません。

## データベースのパス表現

データベースは長い絶対パスを繰り返さず、次の形式で接頭辞を参照します。

```text
{N}/relative/path
```

`N>0` では `path_prefixes` の絶対パスを示します。`download.root-folder` が相対パスなら `{0}/...` を使い、`{0}` は起動ごとに「現在の作業ディレクトリ + 相対ダウンロード先」として解決されます。配布ディレクトリを `pixiv-download/` と一緒に移動すれば履歴を保てます。

絶対パスのダウンロード先を移動した場合は、GUI のステータスページから「ダウンロードディレクトリを移行」を使います。この操作は設定とデータベースの参照を更新しますが、ディスク上のファイルは移動しません。

## 移行

配布物全体を移動する場合は、相対 `download.root-folder` を保ち、アプリを終了して配布ディレクトリ全体を移動し、新しい場所の起動スクリプトから起動します。

ダウンロード先だけを移動する場合は、アプリを通常終了し、ファイルを移動し、GUI の「ダウンロードディレクトリを移行」で新しい場所を選び、必要なら `config.yaml` も更新します。再起動後に履歴、ギャラリー、新しいダウンロードを確認してください。設定を先に書き換えてもファイルは自動移動されません。

## バックアップと復元

完全なバックアップには `config/`、`state/`、アプリ停止後にコピーした `data/`、署名と来歴を含む `plugins/`、`download.root-folder` とコレクションやプラグインが選んだ追加の作品ディレクトリを含めます。`log/` は通常トラブルシューティング用です。

復元時は相対構成を保つか、絶対パスには移行ツールを使います。暗号化された認証情報は元の認証情報マスターキーにも依存するため、別環境へ移す前にキーの互換性を確認してください。互換性がない場合は対象環境で認証情報を再入力します。
