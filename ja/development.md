# 主リポジトリの開発

メインプログラムと公式外部プラグインの開発環境、モジュールの境界、ビルドとテスト、開発モードのデバッグ、ローカルパッケージ作成、PR のルールを説明します。独立したサードパーティープラグインの開発は[サードパーティープラグイン SDK](/ja/plugin-development)、インストールとトラブルシューティングは[プラグイン管理](/ja/plugin-management)を参照してください。

## 必要な環境

| ツール | 必要なバージョン | 用途 |
| --- | --- | --- |
| JDK | 17 | Java モジュールのコンパイルと実行 |
| Maven | 3.9+ またはリポジトリ内の `mvnw` | Reactor のビルドとテスト |
| Git | 現在サポートされているバージョン | バージョン管理とブランチ管理 |
| Node.js / npm | 現在の CI と同じ | フロントエンドテスト、i18n ゲート、境界検証 |
| PowerShell | 5.1+ | Windows のローカルパッケージ作成スクリプト |
| Inno Setup | 6.x（任意） | Windows `setup.exe` インストーラーの作成 |

すべての Java コマンドで UTF-8 を明示してください。Windows PowerShell では次のように設定できます。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## マルチモジュール構成と依存方向

ルートの `pom.xml` は Maven Reactor の集約器です。

| モジュール・ディレクトリ | 役割 |
| --- | --- |
| `pixivdownload-sdk-info/`、`pixivdownload-plugin-api/`、`pixivdownload-core-api/`、`pixivdownload-sdk/` | SDK バージョンの唯一の定義元、プラグイン拡張契約、ホストの安定した意味的ポート、統一コンパイル入口 |
| `pixivdownload-plugin-signature/` | パッケージ、リポジトリマニフェスト、コミュニティ操作の Ed25519 署名・検証ツール |
| `pixivdownload-plugin-runtime/` | PF4J の読み込み、Spring 子コンテキスト、worker プロセス、インストールトランザクションのライフサイクル |
| `pixivdownload-plugin-*/` | 各公式外部プラグインのモジュール |
| `pixivdownload-app/` | アプリケーションホスト、adapter、実行可能 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 公式外部プラグインの開発用集約と `dev-mode` の起動入口 |
| `plugin-templates/` | コピーして使える独立したサードパーティープラグインのプロジェクトテンプレート |

プラグインは `plugin-api` と、必要に応じて `core-api` に依存します。`pixivdownload-app` やホストの内部実装クラスへの逆方向の依存は禁止です。`douyin` のソースは[独立リポジトリ](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)で管理し、固定コミットを使って SDK の隔離された利用側として互換性を検証します。

## Fork、ビルド、テスト

Fork した後、`upstream/master` を基準に機能ブランチを作成します。`YOUR_USERNAME` とブランチ名は置き換えてください。

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

よく使うビルド・検証コマンド：

```powershell
# テストをスキップして全モジュールをパッケージ化
.\mvnw.cmd package -DskipTests

# Maven の全テストを実行
.\mvnw.cmd test

# 単一モジュールと Reactor の上流依存をテスト（-Dtest=... の併用時は -Dsurefire.failIfNoSpecifiedTests=false を追加）
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# フロントエンドテストと Web 標準の検証を実行
npm run test:js
npm run test:web-standards
```

ビルドの成功は `BUILD SUCCESS` と終了コード `0` で確認します。テストをスキップしたパッケージ作成は、テスト成功を意味しません。`pixivdownload-app/target/PixivDownload-*-boot.jar` だけを実行すると、外部プラグインを含まないコアシェルが起動します。全機能の結合確認には以下の開発モードを使ってください。

## 公式外部プラグインの開発モードと復旧モード

リポジトリのルートから、明示的な `dev-mode` profile で完全な開発環境を起動します。IDE の Maven パネルでこの profile を常時有効にしないでください。

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode` は公式プラグイン各モジュールの `target/classes` と、`plugins/` 内の署名検証済みパッケージの両方を検出します。同じプラグイン ID が両方にある場合は `plugin-conflict` で読み込みを拒否し、復旧ページで競合コピーの除去と再起動を案内します。IDE のデバッグにはリポジトリ付属の共有設定（IntelliJ IDEA `.run/Developer Mode.run.xml`、VS Code `.vscode/launch.json`、Eclipse `eclipse/Developer Mode.launch`）を使ってください。

必須プラグインがない場合や復旧モードを検証するには、`Missing Required Plugin` の共有設定、または次のコマンドを使用します。

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## ユーザースクリプトの同期と i18n の手順

- **ユーザースクリプト**：ルートの `*.user.js` と `scripts/build-userscript-bundle.ps1` が生成する All-in-One は、Maven の `generate-resources` 段階で `download-workbench` のリソースにコピーされます。共有フラグメントやスクリプトを変更したら、`./scripts/sync-shared-snippets.ps1`、`./scripts/build-userscript-bundle.ps1` を実行し、Maven ビルドを 1 回行ってください。
- **i18n 国際化**：唯一の言語カタログは `pixivdownload-app/src/main/resources/i18n/locales.json` です。ソース言語は `zh-CN`、全体のフォールバックは `en-US` で、`zh-Hant`、`ja-JP`、`ko-KR` を含みます。文言の追加・変更後は次を順に実行します。

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

## Windows でのローカルパッケージ作成

- **このマシンでの署名なしインストーラー検証**：現在のソースからプラグインをビルドし、隔離された `build/out-local-unsigned/` に出力します。外部への配布は禁止です。

  ```powershell
  .\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins
  ```

- **プラグインを含まないコアシェルのポータブルパッケージ**：復旧モード・開発の検証専用です。

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller
  ```

- **署名済みプラグインを入力して正式パッケージを作成**：`build/out/` に出力します。秘密キーをリポジトリ、ビルドディレクトリ、ログに置くことは禁止です。

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
  ```

## コミットと PR のルール

1. 変更のリスクに応じて、対象を絞ったテスト、モジュールの回帰テスト、フロントエンド・i18n ゲートを実行します。コミット前に `git diff --check` と `git diff --staged` を確認し、`target/`、`build/`、実行時設定、認証情報、テストデータを含めないでください。
2. 利用者向け文字列は i18n に接続し、実行ログは英語に固定します。HTTP API は明示的な DTO と既存の認可・例外マッピングを使い、プラグイン専用の設定、認証情報、データは各プラグインが所有します。
3. ブランチを最新の `upstream/master` に同期してから `master` 向けの PR を作成し、変更理由、範囲、実際に実行した検証コマンドと結果を記載します。オンライン文書は独立した `gh-pages` ブランチで管理します。
