# 開発ガイド

本体や公式プラグインを変更する場合は、環境を準備してビルド・検証し、PR を作成します。サードパーティプラグインの作者は[プラグイン SDK](/ja/plugin-development)、インストールや障害対応は[プラグイン管理](/ja/plugin-management)を参照してください。

## 必要な環境

| ツール | 条件 | 用途 |
|---|---|---|
| JDK | 17 | コンパイルと実行 |
| Maven | 3.9 以降、または同梱 Wrapper | ビルド・テスト |
| Git | サポート中の版 | バージョン管理 |
| Node.js / npm | 現在の CI に合わせる | JavaScript、i18n、境界検査 |
| PowerShell | 5.1 以降 | Windows パッケージ作成 |
| Inno Setup | 6.x、任意 | Windows インストーラー |

Java は UTF-8 を明示します。Windows PowerShell の設定例：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## モジュールと依存方向

ルートの `pom.xml` は Maven Reactor の集約です。

| ディレクトリ | 責務 |
|---|---|
| `pixivdownload-plugin-api/` | プラグインの安定した拡張契約 |
| `pixivdownload-core-api/` | 安定したホスト機能のポートと値モデル |
| `pixivdownload-plugin-signature/` | プラグイン・マニフェストの署名と検証 |
| `pixivdownload-plugin-runtime/` | PF4J、Spring 子コンテキスト、インストールのライフサイクル |
| `pixivdownload-plugin-*/` | 公式の外部プラグイン |
| `pixivdownload-app/` | ホスト、アダプター、実行可能な Spring Boot JAR |
| `pixivdownload-official-plugins/` | 公式プラグインの集約と開発起動 |
| `plugin-templates/` | 複製して使うサードパーティ向けテンプレート |

プラグインは `plugin-api` に依存し、安定したホスト機能が必要なら `core-api` を追加します。サードパーティプラグインは `pixivdownload-app` やホスト実装クラスに依存してはいけません。

Douyin のコード・リソース・テストは[独立リポジトリ](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)にあり、主 Reactor に含まれません。SDK 互換性検査はその固定コミットを取得し、検査対象 SDK でビルドして、連携、署名あり・なしのインストール、ライフサイクルを確認します。

## Fork と作業ブランチ

GitHub で Fork し、`YOUR_USERNAME` と変更内容を表すブランチ名を置き換えて実行します。

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

PR 前に `upstream/master` の変更を反映し、上流の `master` を宛先にします。

## ビルドと検証

```powershell
# テストを省略して全モジュールをビルド
.\mvnw.cmd package -DskipTests

# Maven の全テスト
.\mvnw.cmd test

# 1 モジュールと Reactor 内の依存を検証
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# JavaScript と Web 標準
npm run test:js
npm run test:web-standards

# 主 JAR が 1 個であることを確認して起動
$appJars = @(Get-ChildItem -LiteralPath pixivdownload-app/target -Filter 'PixivDownload-*-boot.jar')
if ($appJars.Count -ne 1) { throw 'Expected exactly one application JAR' }
java -Dfile.encoding=UTF-8 -jar $appJars[0].FullName
```

`BUILD SUCCESS` と終了コードを確認します。テストを省略したパッケージ作成は、テスト成功を意味しません。Boot JAR の実行だけでは外部プラグインをインストールしません。全機能の開発には次の入口、配置には完全な配布パッケージを使います。

`-am` と `-Dtest=...` を組み合わせる場合は、対象クラスを持たない依存モジュールが誤って失敗しないよう、`-Dsurefire.failIfNoSpecifiedTests=false` も指定してください。

## 公式プラグインの開発起動

ルートで実行します。

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode` を明示的に有効にすると、現在の開発ビルドのソース出力と、`plugins/` 内の検証済みパッケージを同時に読み込みます。依存関係は両方を合わせて検査します。同じプラグイン ID が複数の場所にある場合、競合するコピーはすべて読み込まず、復旧画面に場所と対処方法を表示します。ソースをデバッグするときは同名のインストール済みパッケージを移動し、パッケージを使うときは対応するソースモジュールを開発ビルドから外して、再起動してください。

IDE はコミット済みの共有構成を使います。IntelliJ IDEA の `.run/Developer Mode.run.xml`、VS Code の `.vscode/launch.json`、Eclipse の `eclipse/Developer Mode.launch` は、必要なモジュールをコンパイルして `GuiLauncher` を起動します。

必須プラグイン欠落の復旧モードは `Missing Required Plugin` の共有構成、または次のコマンドで確認します。

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## ユーザースクリプトと翻訳リソース

`pixiv-batch.html` は `/api/scripts` から生成済みのスクリプト一覧を読みます。個別の `*.user.js` と `scripts/build-userscript-bundle.ps1` の All-in-One は、Maven の `generate-resources` で `download-workbench` のリソースへコピーします。変更後は Maven のライフサイクルを実行し、IDE に残った古い出力だけで確認しないでください。

言語一覧は `pixivdownload-app/src/main/resources/i18n/locales.json` です。`zh-CN` が開発元、`en-US` が全体のフォールバックです。

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

新しい中国語文言には英訳も含めます。静的リソースを生成したら、アプリだけでなくプラグインを含む実際の owner の出力を確認します。繁体字中国語、日本語、韓国語もあり、言語の状態とフォールバックを保ってください。コマンドの定義は現在の `package.json`、`scripts/i18n/`、CI を確認します。

## Windows パッケージの作成

`scripts/package-local.ps1` はアプリシェル、公式プラグイン、オンライン・オフラインの portable 版、任意の Inno Setup インストーラーを作ります。配布物には全公式プラグインの再検証可能な署名が必要です。`.sig` を含む検証済み入力を `-PrebuiltPluginsDir` に指定するか、ローカルの成果物に `-OfficialKeyId`、リポジトリ外の `-PrivateKeyFile`、任意の `-SignatureToolJar` を指定します。既定の出力は `build/out/` です。

```powershell
# ローカル動作確認専用の未署名インストーラー
.\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins

# プラグインなしの開発・復旧用シェル
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller

# 署名と再検証が済んだ公式入力を使う
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
```

主な引数は `-PrebuiltJar`、`-PrebuiltPluginsDir`、`-SkipPlugins`、`-RunTests`、`-SkipPortable`、`-SkipOfflinePortable`、`-SkipInstaller`、`-RedownloadFfmpeg` です。`-SkipPlugins` の成果物は復旧・補充用です。未署名テスト版は隔離した `build/out-local-unsigned/` に出力し、配布してはいけません。秘密鍵をリポジトリ、出力、ログに入れないでください。

## PR とリリース

変更のリスクに合うテストから始め、影響範囲のモジュールや全体検査に広げます。少なくとも差分を確認します。

```bash
git diff --check
git diff --staged
```

PR には目的、境界、実行した検証と結果を書きます。UI は画像、パッケージは検証した成果物を示してください。`target/`、`build/`、実行設定、認証情報、ダウンロードデータは含めません。

品質検査は Java、JavaScript、i18n、依存と配布境界を確認します。`v*` タグは公式プラグイン、アプリ、Java 配布物、Windows インストーラー、GitHub Release の作成を開始します。`workflow_dispatch` は品質検査後に指定タグのドラフト Release を作ります。ドキュメントは独立した `gh-pages` ブランチで管理します。実際の公開作業前には、現在の `.github/workflows/` と `scripts/` を確認してください。

## 変更時に守る境界

- ユーザー向け文字列は i18n を使い、Web は既存のダークモード、CSS 変数、HTML・CSS・JavaScript の分離に従います。
- HTTP API は明示的な DTO と既存の認証・例外マッピングを使います。
- データベースの作成 SQL を変える場合は、管理対象 schema と移行テストも更新します。
- プラグインの設定、認証、状態、データ、依存をアプリシェルへ戻さないでください。
- 公開拡張契約は `plugin-api` / `core-api` とガードを先に更新し、テンプレート、例、SDK 文書を合わせます。
