# 主リポジトリの開発

メインプログラムと公式外部プラグインの開発環境、モジュールの境界、ビルドとテスト、開発モードのデバッグ、ローカルパッケージ作成、PR のルールを説明します。独立したサードパーティープラグインの開発は[サードパーティープラグイン SDK](/ja/plugin-development)、インストールとトラブルシューティングは[プラグイン管理](/ja/plugin-management)を参照してください。

## 必要な環境

ホスト、SDK、公式プラグインは JDK 25 を使用し、Java/Kotlin のコンパイル対象も 25 に統一します。サードパーティープラグインの開発にも JDK 25 が必要です。Temurin 25 で ProGuard ビルドを実行する場合は、JDK の完全なバージョンと一致する JMOD をダウンロードし、`jmods/` に配置してください。CI は `actions/setup-java` の `java-package: jdk+jmods` で両方を準備し、チェックサムと署名を検証します。

フレームワークの基準は Spring Boot 4.1.2、Spring Framework 7、Jackson 3、SDK 2.0.0 です。プラグインを更新する前に [SDK の移行](/ja/plugin-development)を完了してください。SDK 1.x のバイナリはこのホストに直接読み込めません。

| ツール | 必要なバージョン | 用途 |
| --- | --- | --- |
| JDK | 25 | Java モジュールのコンパイルと実行。ホストの CI テストは 25 を使用 |
| Maven | 3.9+ またはリポジトリ内の `mvnw` | Reactor のビルドとテスト |
| Git | 現在サポートされているバージョン | バージョン管理とブランチ管理 |
| Node.js / npm | 現在の CI と同じ | フロントエンドテスト、i18n ゲート、境界検証 |
| PowerShell | 5.1+ | Windows のローカルパッケージ作成スクリプト |
| Inno Setup | 6.x（任意） | Windows `setup.exe` インストーラーの作成 |

すべての Java コマンドで UTF-8 を明示してください。Windows PowerShell では次のように設定できます。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

### 複数の JDK がある場合の選択

ルート POM は Maven の `validate` フェーズで JDK `[25,26)` を要求します。Maven の現在の JVM が 25 なら再利用し、それ以外は `~/.m2/toolchains.xml` の一致する登録を確認してから、Maven のローカル検出を試みます。コンパイル、テスト、Maven による開発起動、AOT、Maven が呼び出す Gradle / ProGuard はこの選択を共有します。25 が見つからない場合はビルドを停止し、JDK のダウンロードや 17 / 26 への切り替えは行いません。

独自のインストール先は一度登録すると、すべての clone で使えます。以下の `jdkHome` を既存の JDK 25 の絶対パスに置き換え、`~/.m2/toolchains.xml` に保存してください。既存ファイルがある場合は他の登録を残し、`toolchain` 要素を追加します。自動検出は `JAVA25_HOME` などの環境変数と一般的なインストール先に対応しますが、任意のディスク全体は検索しません。古い JDK のローカライズされた出力で検出に失敗する場合も、明示的に登録してください。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">
  <toolchain>
    <type>jdk</type>
    <provides><version>25</version></provides>
    <configuration><jdkHome>C:/path/to/jdk-25</jdkHome></configuration>
  </toolchain>
</toolchains>
```

Maven 自体を起動する JVM は、引き続き端末の `JAVA_HOME` または IDE Maven Runner が選びます。IDE ネイティブの `Developer Mode` はプロジェクト SDK で実行・デバッグするため、インストール済みの 25 を IDE で選択してください。AOT スクリプトを直接呼び出す場合は `--java-home` が `JAVA_HOME` より優先されます。Compose の Java/Kotlin ツールチェーンは 25 を要求し、JDK の自動ダウンロードを無効にしています。

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

プラグインは `plugin-api` と、必要に応じて `core-api` に依存します。`pixivdownload-app` やホストの内部実装クラスへの逆方向の依存は禁止です。`douyin` のソースは[独立リポジトリ](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)で管理し、専用テストはそのリポジトリの `Host compatibility` CI だけで、固定したホストコミットに対して実行します。主リポジトリの PR では汎用 SDK の検証を継続します。

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

## 任意の JDK 25 AOT Cache

リポジトリの `scripts/aot-cache.mjs` は、最終 boot JAR 用の JVM 起動キャッシュを作成して使用します。Node.js とインストール済みの JDK 25 が必要です。JDK のダウンロードや Spring AOT の有効化は行いません。インストーラーの起動方法は変わりません。CI の成果物検証では実際にキャッシュを作成し、strict 起動を確認します。信頼できる JAR のみを学習させてください。学習ではアプリケーションを実行しますが、独立した一時設定を使い、現在のインスタンスのユーザーデータは読み込みません。

事前の手動パッケージ作成は不要です。共有設定の `AOT Prepare`、`AOT Packaged Run` を順に実行してください。IntelliJ IDEA は `.run`、VS Code は同名タスク、Eclipse は `eclipse/` の launch 設定を使います。Prepare は Maven Toolchains が選んだ JDK 25 で本体と公式プラグインをビルドし、専用の実行ディレクトリを組み立ててからキャッシュを準備します。Windows はシステムの PowerShell を使用します。Linux / macOS では PATH に PowerShell 7（`pwsh`）が必要です。対応するコマンドは次のとおりです。

```powershell
.\mvnw.cmd -N -Paot '-Daot.command=prepare' validate
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }
.\mvnw.cmd -N -Paot validate
```

既定のディレクトリは `target/aot-runtime` です。`-Daot.runtime.directory=...` で別の場所を指定できます。`artifacts/` には本体と公式配布プラグイン一式（任意プラグインを含み、復旧テスト用プローブを除く）、`cache/` には JAR と JDK の内容ごとのキャッシュ、`instance/` はアプリケーションの作業ディレクトリで、独立した設定とデータを保存します。`mvn clean` は `target/` 内の既定の実行ディレクトリも削除します。データを保持する場合は `target/` 外のディレクトリを指定し、Prepare と Run で同じ値を使用してください。初回の Run では通常の初期設定を行います。リポジトリ直下の既存インスタンスは読み込まず、上書きもしません。コード変更後は Prepare を再実行してください。成功時のみ新しい成果物に切り替え、設定とデータを保持します。同じ成果物は検証して再利用し、本体と JDK が同じならプラグインだけの変更で本体キャッシュを作り直す必要はありません。 単一起動ロックはこの実行ディレクトリの `instance/ipc/` に置かれ、他の開発用・インストール済みインスタンスと分離されます。同時に実行する場合は、異なるサーバーポートを設定してください。

Prepare のビルドでは単体テストを省略します。テストと CI の検証は別途実行します。プラグインは既存のローカル未署名アップロード規則で配置するため、ローカル開発専用であり、公式署名付き配布物として配布できません。Run は再ビルドしません。同じディレクトリで準備や実行が進行中なら、並行操作を拒否します。失敗時は直前の実行可能な記録を保持し、破損した内容は再利用しません。別の実行ディレクトリで準備できます。古い成果物とキャッシュは自動削除されず、ディスクを使用します。関連プロセスの停止後、不要な内容を削除できますが、必要な `instance/` のデータは残してください。

`AOT Prepare` と `AOT Packaged Run` は通常の Run で実行できます。IDE の Debug から起動した場合も、AOT 子プロセスの環境から JDWP と IDEA のデバッグエージェント引数だけを除去し、ヒープサイズ、プロキシ、証明書などの引数は保持します。アプリケーションのブレークポイントデバッグには `Developer Mode` を使用してください。Windows で Prepare またはその起動元を停止すると、その処理が起動したビルドと学習の子プロセスも終了し、ロックが解放されます。他のアプリケーションインスタンスには影響しません。ロックが使用中の場合は、同じ実行ディレクトリを使う Prepare または Run タスクを停止してから再試行してください。`.lock` は削除しないでください。それ以外のロックエラーは、出力に示された原因を確認してください。

ソースの実行やブレークポイントによるデバッグには `Developer Mode` を使用します。JDK 25 AOT Cache は空でないクラスディレクトリから作成できず、現在のリンク済みキャッシュは JDWP デバッグでも使用できません。単体テスト、JaCoCo、Maven、Gradle のビルドプロセスはアプリケーションの AOT キャッシュを使用しません。学習コストは実際の繰り返し起動回数に照らして評価してください。

以下の低水準 CLI は既存の JAR を扱い、CI も直接利用します。ビルドやプラグイン配置は行いません。リポジトリのルートから実行し、`<version>`、JDK のパス、初期設定済みインスタンスのディレクトリを置き換えてください。新しいキャッシュには未作成のディレクトリを使い、有効な既存キャッシュは検証して再利用できます。実行には選択したインスタンスの設定、プラグイン、データを使います。プラグインのないコア JAR は復旧モードのみ提供します。

```powershell
$repo = (Get-Location).Path
$jar = (Resolve-Path 'pixivdownload-app/target/PixivDownload-<version>-boot.jar').Path
$jdk = 'C:/path/to/jdk-25'
$cache = Join-Path $repo 'target/aot-cache'
node "$repo/scripts/aot-cache.mjs" prepare --jar $jar --java-home $jdk --cache-dir $cache
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }

Set-Location 'C:/path/to/initialized-instance'
node "$repo/scripts/aot-cache.mjs" run --jar $jar --java-home $jdk --cache-dir $cache -- --no-gui
if ($LASTEXITCODE -ne 0) { throw 'AOT application failed' }
```

低水準ツールは新規作成時に `AOT prepare: ready`、有効なキャッシュの再利用時に `AOT prepare: reusing verified cache` を表示します。起動前に元の JAR、展開した依存ファイル、JDK、キャッシュの SHA-256 を確認します。破損・未完成のキャッシュは保持し、`--cache-dir` で新しい場所を指定します。一時学習データは終了後に削除します。マニフェストはローカルの整合性確認であり、配布署名やプラグイン受け入れ検証を代替しません。

通常の起動では現在の環境を保持し、キャッシュに互換性がない場合は JVM がフォールバックします。利用を検証する場合は `run` の `--` の前に `--strict` を追加し、JVM の `Using AOT-linked classes: true` 診断を確認してください。正常終了だけでは利用を証明できません。学習コストを回収できるか、同じ JAR、JDK、プラグイン、データ、起動完了条件で繰り返し測定してください。仕組みは [Spring Boot AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html) を参照してください。

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
