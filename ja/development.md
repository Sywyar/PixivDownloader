# 開発ガイド

このページはメインリポジトリの開発者向けです。サードパーティプラグインは [サードパーティプラグイン SDK](/ja/plugin-development)、実行時の操作は [プラグイン管理](/ja/plugin-management) を参照してください。

## 前提ツール

| ツール | 要件 | 用途 |
| --- | --- | --- |
| JDK | 17 | ビルドと実行 |
| Maven | 3.9 以上、または Maven Wrapper | ビルドとテスト |
| Git | サポート中の版 | バージョン管理 |
| PowerShell | 5.1 以上 | Windows パッケージング |
| Inno Setup | 6.x（任意） | Windows インストーラー |

Java は明示的に UTF-8 で実行します。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## マルチモジュール構成

ルート `pom.xml` が Maven Reactor の集約です。

| ディレクトリ | 役割 |
| --- | --- |
| `pixivdownload-plugin-api/` | サードパーティ拡張の安定契約 |
| `pixivdownload-core-api/` | ホストの安定した意味ポートと値モデル |
| `pixivdownload-plugin-signature/` | アーティファクト / マニフェスト署名ツール |
| `pixivdownload-plugin-runtime/` | PF4J、子 Spring コンテキスト、インストールライフサイクル |
| `pixivdownload-plugin-*/` | 公式外部プラグイン |
| `pixivdownload-plugin-douyin/` | Douyin の公式例 |
| `pixivdownload-app/` | ホストアダプターと実行可能 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 公式プラグイン集約と開発入口 |
| `plugin-templates/` | コピー可能な第三者向けテンプレート |

依存関係は契約へ向けます。プラグインは `plugin-api` に依存し、安定したホスト機能が必要な場合だけ `core-api` を追加します。`pixivdownload-app` やホスト実装クラスには依存しません。

## フォークとブランチ

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

プルリクエスト前に `upstream/master` から最新状態を取り込みます。

## ビルド、テスト、実行

```powershell
.\mvnw.cmd package -DskipTests
.\mvnw.cmd test
.\mvnw.cmd -pl pixivdownload-plugin-api -am test
npm run test:js
npm run test:web-standards
java -Dfile.encoding=UTF-8 -jar pixivdownload-app/target/PixivDownload-*-boot.jar
```

`-am` と `-Dtest=...` を併用する場合は、上流モジュールに対象クラスがなくても失敗しないよう `-Dsurefire.failIfNoSpecifiedTests=false` を追加します。

## 公式外部プラグイン開発モード

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

IntelliJ IDEA の `.run/Developer Mode.run.xml`、VS Code の `.vscode/launch.json`、Eclipse の `eclipse/Developer Mode.launch` には共有設定があります。必要な Reactor モジュールをビルドして `GuiLauncher` を起動します。必須プラグイン欠落の復旧確認には `-Precovery-mode` または対応する共有設定を使います。

## ユーザースクリプト資源

`pixiv-batch.html` は `/api/scripts` のカタログを使います。単体 `.user.js` と `scripts/build-userscript-bundle.ps1` の統合版は Maven の `generate-resources` でコピーされます。ユーザースクリプトを変更したら、少なくとも 1 回 Maven ライフサイクルを実行してください。IDE の古い出力だけでは不十分です。

## i18n の作業

`i18n/locales.json` が言語カタログです。中国語（`zh-CN`）がソース、英語（`en-US`）が全体フォールバックです。

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

新しい中国語文言と英訳は同時に追加します。静的資源を変更した場合は `pixivdownload-app/src/main/resources/static/i18n-static` を生成してコミットします。基準受け入れとフックは [リポジトリの i18n ワークフロー](https://github.com/Sywyar/PixivDownloader/blob/master/docs/i18n-workflow.md) を参照してください。

## Windows パッケージング

`scripts/package-local.ps1` はアプリシェル、公式プラグイン入力、オンライン / オフラインアーカイブ、任意の Inno Setup インストーラーを作成します。正式成果物には各公式プラグインの検証可能な `.sig` が必要です。

```powershell
# 現ソースからの署名なしローカル受け入れ用インストーラー
.\scripts/package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins

# プラグインなしの復旧 / 開発用コアシェル
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller

# 署名済み入力から正式成果物
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
```

`-PrebuiltJar`、`-PrebuiltPluginsDir`、`-SkipPlugins`、`-RunTests`、`-SkipPortable`、`-SkipOfflinePortable`、`-SkipInstaller`、`-RedownloadFfmpeg` などを使えます。`-SkipPlugins` 成果物は復旧専用です。秘密鍵をリポジトリ、出力、ログへ入れないでください。

## コミットとプルリクエスト

影響範囲に合わせて検証し、少なくとも次を確認します。

```bash
git diff --check
git diff --staged
```

PR には目的、範囲、実行した検証コマンドと結果を記載します。UI 変更にはスクリーンショット、パッケージング変更には検証済みアーティファクトを添えます。`target/`、`build/`、実行時設定、認証情報、ダウンロードファイルをコミットしません。

## CI とリリース

Quality Gate は Java、JavaScript、i18n、依存関係、配布境界を検証します。`v*` タグで公式プラグイン、アプリシェル、Java 配布物、Windows インストーラー、GitHub Release を公開します。`workflow_dispatch` は Quality Gate 成功後に指定タグの Draft Release を作成します。ドキュメントサイトは独立した `gh-pages` ブランチで管理します。

## コード境界

- ユーザー向け文字列は i18n を通す
- 既存のダークモード、CSS 変数、HTML / CSS / JavaScript 分離規約を守る
- 公開 HTTP API には明示的 DTO と既存の認証・例外マッピングを使う
- DB DDL 変更時は管理スキーマと移行テストも更新する
- プラグイン所有の設定、認証情報、状態、データ、依存関係をアプリシェルへ戻さない
- 第三者契約を変更する場合は `plugin-api` / `core-api` とガード、テンプレート、例、SDK 文書を同時に更新する
