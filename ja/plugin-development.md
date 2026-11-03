# サードパーティープラグイン SDK

サードパーティープラグインは SDK 開発パッケージの独立したプロジェクトテンプレートから作成できます。必要なのは JDK 17、Node.js、同梱のビルドツールと実行マニフェストだけで、主リポジトリのクローンは不要です。

| 開発内容 | 対応する章 |
| --- | --- |
| 最初のプラグインを作成してローカルでデバッグ | [テンプレートから始める](#テンプレートから始める) · [ローカル開発](#ローカル開発) |
| ダウンロードする作品の種類や独立ギャラリーを追加 | [新しいダウンロード種類を追加する手順](#新しいダウンロード種類を追加する手順) · [プラグイン専用ギャラリーの追加](#プラグイン専用ギャラリーの追加) |
| 設定、暗号化した認証情報、ファイル、専用データベースの管理 | [設定、認証情報、ファイル](#設定、認証情報、ファイル) · [GUI に依存しない認証情報と汎用操作検証](#gui-に依存しない認証情報と汎用操作検証) |
| プラグイン間のサービス契約やページエディターのコンポーネントを宣言 | [独立したサービス契約と組み合わせ](#独立したサービス契約と組み合わせ) · [通常のスロットとページコンポーネント](#通常のスロットとページコンポーネント) |
| SDK 更新、コミュニティマーケットへの投稿、自前リポジトリでの配布 | [外部プロジェクトの保守と SDK 更新](#外部プロジェクトの保守と-sdk-更新) · [マーケット用資料の投稿](#マーケット用資料の投稿) · [署名と公開](#署名と公開) |

以下の `SDK_VERSION`、パッケージ名、プラグイン ID、パス、SHA-256、署名はプレースホルダーです。実際の値に置き換えてください。コード片は対応するテンプレートの中に配置し、import と業務処理を補う必要があります。利用可能な API は、選択した [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) と[バージョン別 Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)を基準にしてください。

参照ソース：[プラグインテンプレート](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates) · [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info) · [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api) · [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api) · [署名ツール](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature) · [Douyin の完全なプラグイン例](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)。Douyin はダウンロード、設定、プロキシ、キュー、スケジュールタスク、専用データベース、独立ギャラリーを示しますが、公式の配布対象には含まれません。新規プロジェクトは `plugin-templates` から始めてください。

## プラグインのライセンスと公開先

PixivDownloader と公式プラグインは [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) を採用し、[プラグインリンク例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)を同梱するバージョンには追加の許諾があります。公開 SDK / API だけを使い、例外条件を満たす独立したサードパーティープラグインは、MIT などのライセンスを選べます。プロジェクトテンプレートをコピー・変更する場合は、上流の著作権表示と例外の本文を保持する必要があります。ホスト、SDK、公式プラグインには引き続き AGPL-3.0 が適用されます。

[公式コミュニティプラグインリポジトリ](https://github.com/Sywyar/PixivDownloader-community-plugins)はオープンソースのプラグインだけを受け付けます。完全な GitHub ソース、オープンソースライセンス、再現可能なビルドを公開し、手動審査を通過する必要があります。

## 信頼と SDK アーキテクチャの境界

- **実行モードと信頼の境界**：`declarative-process` は独立した worker JVM でリソースとプロトコルを分離し、`host-process-full-trust` はホスト JVM で実行します。両方とも現在の OS アカウントを共有し、OS レベルのセキュリティサンドボックスではありません。Ed25519 署名は公開者の識別とバイト列の完全性だけを証明するため、インストール前に作者とソースを信頼できるか判断してください（[プラグイン管理](/ja/plugin-management)を参照）。
- **コンパイル依存の方向**：サードパーティープラグインは単一の `pixivdownload-sdk`（`provided`）依存を宣言します。そこから `pixivdownload-sdk-info`、`pixivdownload-plugin-api`、`pixivdownload-core-api`、ホストが提供する PF4J、Spring、Servlet、Jackson のコンパイル依存が伝播します。3 つの API モジュールと BOM は個別にも使用できます。**`pixivdownload-app`、ホスト内部の実装クラス、公式プラグインの非公開クラス、ホストの `DataSource`、非公開フロントエンドグローバル変数への依存は禁止です。**
- **機能の公開と不在時の対応**：プラグインは `PixivFeaturePlugin` で機能を提供し、`PixivPluginProvider.configurationClasses()` で独立した Spring 子コンテキストの設定クラスを宣言します。無効化、アンインストール、世代交代時には、ホストが publication 単位でルート、静的リソース、i18n、ナビゲーション、ダウンロード種類、キュー、スケジュール機能を撤回します。呼び出し側は機能の不在を処理し、NullPointerException や処理途中のタスクを残してはいけません。
- **デスクトップ UI の境界**：ホストはツールキット非依存の純データ契約（`GuiConfigContribution` など）で設定、ガイド、状態を集約し、`gui-compose`（デフォルト）と `gui-swing`（代替）がそれぞれ画面を描画します。機能プラグインは Swing / Compose のコンポーネントを返しません。

### `PixivFeaturePlugin` の主な提供入口

| メソッド | 提供する内容 |
| --- | --- |
| `id`、`displayName`、`description`、`displayNamespace`、`iconKey`、`colorToken`、`kind` | プラグイン識別情報、i18n 表示キー、制御されたアイコン・色・分類 token |
| `start`、`stop` | プラグイン所有のローカルリソースのライフサイクル。`stop` は冪等であること |
| `privateSchema`、`privateStorageMigrations` | 現在の owner が管理する正式な専用テーブル構造と、順序付きのデータ移行宣言 |
| `schema`、`sharedMainStorage` | ホストの主データベースと協調する管理対象テーブル、公開ストレージ領域の利用申請。他の owner のテーブルにはアクセス不可 |
| `routes`、`staticResources`、`i18n` | Web ルートのアクセスポリシー、classpath の静的リソースマッピング、フロントエンド i18n namespace |
| `navigation`、`startupRoutes`、`landings`、`pageSections`、`uiSlots`、`drilldowns` | ナビゲーション項目、起動時の遷移先、ページ区画、Web UI スロット、詳細へのリンク |
| `guiConfigContributions`、`guiOnboardingSteps`、`guiThemes` | デスクトップ設定の項目・グループ・操作・プリセット、ガイド手順、起動時のテーマ |
| `userscripts`、`scheduledSourceDescriptors`、`downloadTypes` | ユーザースクリプトのリソース、スケジュールタスクの取得元記述子、ダウンロードワークベンチの作品種類記述子 |

## テンプレートから始める

| テンプレート | 用途 | 含まれるもの |
| --- | --- | --- |
| `minimal-feature-plugin` | 宣言型ページ、ナビゲーション、i18n | `declarative-process` worker、PF4J 入口、route/static/i18n、thin JAR テスト。Spring 子コンテキストは持たない |
| `download-type-plugin` | ダウンロード可能な作品種類とギャラリーの追加 | `host-process-full-trust`、ダウンロード種類記述子、5 種類の取得モード、キュー、スケジュール取得元、Vue スロット、独立ギャラリー、フロントエンド・バックエンドテスト |
| `typed-service-plugin` | プラグイン間の型付きサービスとリモート呼び出し | 独立した `api` / `contract` / `provider` / `consumer` のマルチモジュールプロジェクト、Schema・クライアント生成 |

リポジトリ外へコピーしたテンプレートは、主リポジトリの parent を継承しない独立した Maven プロジェクトです。`mvn clean verify` で検証できます。主リポジトリ内では `mvn -f plugin-templates/pom.xml clean verify` を使えます。

Maven の依存宣言は次のとおりです。Java Controller で引数名を明示しない `@PathVariable` / `@RequestParam` を使う場合は、`<maven.compiler.parameters>true</maven.compiler.parameters>` または `-parameters` が必要です。

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

Gradle は `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`、sbt は `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided` を使います。コピー後は Maven `artifactId`、全体で一意のプラグイン ID、Java パッケージ名とクラス名の接頭辞、バージョン、ルート接頭辞、静的リソースディレクトリ、専用テーブル名、i18n namespace を一貫して置き換えてください。

## パッケージ記述子と子コンテキストの構成

JAR のルートには `plugin.properties` が必要です。

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=1.3
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.execution-mode=host-process-full-trust
pixiv.lifecycle-policy=process-restart
```

- `plugin.id` は `PixivFeaturePlugin.id()` と完全一致させます。`plugin.requires` は必要な SDK バージョンを示し、安定版は `requiredMajor == hostMajor && requiredMinor <= hostMinor`、プレリリースは `alpha.N` / `beta.N` / `rc.N` の形式で完全一致を要求します。
- `pixiv.execution-mode` は必須で、`declarative-process` または `host-process-full-trust` を指定します。`pixiv.lifecycle-policy` は `hot-reload`（デフォルト）、`backend-restart`、`process-restart` から選択できます。`pixiv.risk-signals` にはリスクを示す token を宣言できます。
- 外部プラグインの入口は `PixivPluginProvider` を実装し、唯一の `PixivFeaturePlugin` と明示的な `@Bean` 設定クラスを返します。ホストのパッケージ走査に依存してはいけません。

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {
    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }
    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(ExampleDownloadConfiguration.class);
    }
}
```

### Web ルート、静的リソース、i18n、通知テンプレート、アンケートスロット

すべての Controller パス、静的リソース、HTML ページは、同じプラグインの `routes()` で `AccessPolicy` を宣言する必要があります。`publicRoute`、`visitor`、`visitorAndInvitedGuest`、`invitedGuest`、`admin`、`local`、`gui` に対応し、未宣言の `path + method` は 404 を返します。

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}
```

- **業務通知テンプレート**：シナリオの owner が子コンテキストに `NotificationTemplateContributor` Bean を登録し、純値の `NotificationTemplateContribution(scenarioId, medium, locale, titleTemplate, bodyTemplate)` を返します。タイトルは最大 16 KiB、本文は 1 MiB、1 プラグインは最大 256 件 / 8 MiB です。信頼できない値を挿入する前に HTML エスケープが必要です。
- **PostHog アンケートとサイト内メッセージ**：Web プラグインは `plugin.dependencies=posthog?@1.0` を宣言し、`/pixiv-posthog/pixiv-posthog.js` を読み込んで `window.PixivPostHog.createSurveyClient` と `captureSurveyWithAck` を呼べます。管理者のサイト内メッセージに常設する場合は、`new SurveyInboxMessage(...).toUiSlotContribution()` で `notification.inbox` スロットを提供します。

## 新しいダウンロード種類を追加する手順

ダウンロード種類の関連機能は同じプラグインがまとめて提供します。

1. **`DownloadTypeDescriptor` を宣言**：`PixivFeaturePlugin.downloadTypes()` で descriptor を返します。`contractVersion = DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`、全体で一意の `type`、i18n キー、アイコン・色 token、同一オリジンの動作モジュール `moduleUrl`、対応する取得モード `single-import` / `user` / `series` / `search` / `quick`、`cancelSupported`、絞り込み・設定の許可リストを指定します。
2. **フロントエンド動作モジュールを実装**：`moduleUrl` 内で `PixivBatch.queueTypes.registerModule(initializer)` を呼び、必須の `process(item, context)` と、宣言した `import`、`acquisition.*`、`filters`、`settings` を実装します。状態は `context.updateItem(patch)` で更新し、`DownloadTasks` を使う場合は `context.bindTask(taskId)` で実行 UUID に関連付けます。非同期コールバックが書き戻す前に `AbortSignal` / `isActive()` を確認し、`onCleanup()` でリソースを解放してください。
3. **バックエンドで信頼できる利用者識別を解決**：Controller に親コンテキストの `RequestOwnerIdentityResolver` を注入して、現在のリクエストの利用者を解決します。本文やパラメーターで自己申告された owner UUID を信頼してはいけません。ファイルが耐久的に保存され、記録がコミットされてから完了とします。
4. **`QueueOperations` と実際の処理完了待ち（Quiesce / Drain）を実装**：`workKey` は不透明な文字列で、ホストが `POST /api/download/queue/{queueType}/cancel` の JSON 本文に入れて渡します。非同期・バックグラウンドタスクを持つキューは、`prepareQuiesce` をオーバーライドして新規受付を停止し、正の世代番号を持つ実際の `QueueDrain` を返す必要があります。処理中のタスクがゼロになってから drain を完了します。
5. **UI スロットとスケジュール機能（任意）**：`uiSlots()` で `WebUiSlotContribution` を提供します。ホストの `PixivVue.mountUiSlot` を使い、Vue runtime はパッケージに含めません。`scheduledSourceDescriptors()` と、子コンテキストの `ScheduledSourceExecutor` / `ScheduledWorkExecutor` Bean でスケジュール機能を提供します。ホストはスケジューリング、ロック、認証情報、ウォーターマークのコミットを担当します。

## プラグイン専用ギャラリーの追加

ダウンロード種類のプラグインは、自分のギャラリーの HTML、CSS/JS、ルート、i18n、Controller をすべて所有します。`/pixiv-gallery.html` と `/api/gallery/**` は公式 Pixiv ギャラリープラグイン専用で、汎用のギャラリー集約 API はありません。

上部の種類切り替えバーに接続するには、自分のギャラリー HTML の上部に次を配置します。

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

さらに `navigation()` で `NavigationPlacements.GALLERY_TYPE_SWITCH` のナビゲーション項目を提供します。サイドバーの入口には `gallery.sidebar`、`gallery.sidebar.tools`、`novel.sidebar`、`novel.sidebar.tools` を使います。

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

## 設定、認証情報、ファイル

| データの種類 | ディスク上のパス | プラグインからのアクセス方法 |
| --- | --- | --- |
| ホスト設定とプラグインの有効・無効 | `config/config.yaml` | Core API の安定したポート（`DownloadSettings`、`OutboundProxySettings` など）から読み取り専用で取得 |
| プラグインの業務設定 | `config/plugins/{pluginId}.properties` | 子コンテキストの `Environment`（`Binder.get(environment).bind(...)` を推奨）と `GuiConfigContribution` |
| プラグインの機密認証情報 | `config/credentials/{pluginId}.properties` | ホストが暗号化して保存し、この owner が宣言した機密キーだけを子コンテキストの `Environment` または `PluginCredentials` に注入 |
| プラグインの状態と永続データ | `state/{pluginId}/`、`data/{pluginId}/` | `RuntimePathProvider` を注入し、`stateDirectory()`、`dataDirectory()` を呼ぶ |
| ダウンロード作品のファイル | `{rootFolder}/{pluginId}/` またはプラグイン指定のディレクトリ | `DownloadSettings.getRootFolder()` から導出。`state/` や `data/` には書き込まない |

- **データベースアクセス**：新しいプラグインは `PluginDatabase` を注入し、`privateSchema()` と `privateStorageMigrations()` で構造を宣言して、`read(work)` / `transaction(work)` で方言非依存の操作を行います。SQLite と PostgreSQL に対応します。主データベースと協調する場合は `PluginMainDatabase` を注入し、この owner の管理対象テーブルや申請済みの `sharedMainStorage()` にアクセスします。旧 `PluginDataSource` は SQLite のみ対応です。
- **外向き HTTP / WebSocket**：`OutboundHttpClientFactory` / `OutboundWebSocketClientFactory` を注入し、プラグインの Bean が所有して破棄時に `close()` するクライアントを作成します。`exchangeStream` が返す応答ストリームはすべての分岐で閉じる必要があります。独自の `HttpClient`、`ProxySelector` の作成や、ホストの HTTP 設定クラスへの依存は禁止です。

## 独立したサービス契約と組み合わせ

SDK 1.4+ はプラグイン間の型付きサービスをサポートします。`typed-service-plugin` テンプレートを参照してください。

- 契約を保持するプラグインは `pixiv.contract-api` と `pixiv.contract-sha256` で元の純 JDK API JAR を宣言し、`process-restart` を使用します。提供側は `serviceExports()` で実装を公開し、利用側は `serviceImports()` で依存を宣言して `PluginServices` を注入します。
- `find(key)`、`require(key)`、`findAll(key)`、またはライフサイクルに結び付く `bind(keys, activation)` / `bindAll(keys, activation)` で、正確な publication に紐付いたプロキシを取得します。
- `sdk-tools.jar` で JSON Schema、リモート呼び出し adapter を生成し、バイナリー互換性を検査できます。

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

大きな整数は record のフィールドに `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)` を付けます。`UUID`、`Instant`、`Duration`、`float`、`double` は SDK が定める可逆な文字列エンコーディングを使用します。

## GUI に依存しない認証情報と汎用操作検証

- **`PluginCredentials`**：プラグインは `credentialKeys()` でこの owner の接頭辞を持つ機密キーを宣言し、`PluginCredentials` を注入して原子的な `read`、`update`、`delete` を実行できます。最大 128 項目、1 値 64 KiB、1 バッチ 1 MiB で、同名の GUI 項目も機密として指定する必要があります。
- **HTTP Hooks**：`OperationHook` Bean で `id()`、`definition()`、`generation()`、`invoke(...)` を実装します。ホストは Spring の実際のマッピングから owner、HTTP メソッド、ルートテンプレート、照合条件を取得します。通常のコントローラーも動的な外部コントローラーも Hook 注釈や Guard の呼び出しは不要です。承認済みの関連付けでは endpoint または明示的なグループを選択し、`OperationEndpoint` は任意のグループ情報として使用します。
- **設定と検索**：管理者は `/api/web/operation-hooks/catalog`、`definitions`、`configuration` を取得します。既存設定を保持し、同じ接頭辞の `prepare` に `{revision, settings}` を送信します。対象、影響するページ、権限を確認してから `publish` に `{plan, approvals}` を送信します。設定は `groups` と `bindings`、関連付けは `id/hook/operation/endpoint/phase/order/completion/configuration/fields` を持ち、`operation` と `endpoint` は一方を選びます。`resolveTargets` と `getFormEndpoints(pageId, formId, actionId)` は共通のリゾルバーです。未宣言、曖昧、権限不足、利用不可の結果を処理してください。検索によって呼び出しや関連付けの権限は付与されません。
- **実行とデータ**：`BEFORE_INVOKE` はホストの認証・認可、CSRF、受付制限の後、コントローラーの前に `CONTINUE/REJECT/CHALLENGE` を返します。既定ではメタデータのみを渡します。本文、クエリ、結果のフィールドは承認済み `fields` 許可リストで上限付き抽出を行い、認証情報は許可しません。servlet オブジェクトや業務 action は渡しません。`AFTER_COMPLETION` は `SUCCEEDED/FAILED/BLOCKED/TIMED_OUT/CANCELLED` または `ALL` を選択できます。成功は HTTP 状態が 400 未満であることを示し、本文の業務成功、トランザクションのコミット、バックグラウンド処理の完了を意味しません。SSE、ダウンロード、非同期リクエストは完了メタデータを観測し、ストリーム全体を保存しません。
- **チャレンジと終了処理**：ホストの `POST /api/operation-hooks/prepare` と `X-Pixiv-Hook-Ticket` が準備、公開リビジョンの確認、一度限りの消費を行います。DTO に証明フィールドを追加せず、リクエストを無条件に再送しません。必須の前処理 provider が不在、故障、タイムアウトの場合は実行を拒否します。後処理の失敗は HTTP 結果を変えず、永続的な配送も保証しません。タイムアウト後も Java 処理が続く間は同じ世代のリースを保持します。上限はページ当たり 16 関連付けと 128 フォーム関係、関連付け当たり 16 フィールド、実行中 HTTP 64 件、worker 8 個、待機 64 件です。前処理の累積待機と後処理の待機はそれぞれ 10 秒です。
- **ログとローカル呼び出し**：既定の英語ログに相関 ID、呼び出し ID、信頼済み plugin と generation/publication、コールバック入口、関連付け元、handler・メソッド・ルートテンプレート、段階、結果を記録します。証明や本文は記録しません。`OperationVerification` は対話式の前処理 Hook に適応し、`OperationGuard` は明示的なローカル同期操作に使用します。同じ HTTP 入口で重ねて呼び出さないでください。コンポーネントは[操作 CAPTCHA](/ja/captcha)を参照してください。

## 通常のスロットとページコンポーネント

通常のページは `/js/pixiv-ui-slots.js` の `PixivUiSlots.connect(...)` で `data-ui-slot` をマウントします。プラグインモジュールは `registerModule(moduleUrl, mount)` を呼び、`onCleanup` で DOM、リスナー、リクエストを解放します。ページエディターのコンポーネントと編集可能なページの接続方法は[ページエディターとプラグインコンポーネント](/ja/page-editor#サードパーティープラグインのコンポーネントとページを接続する)を参照してください。

## 外部プロジェクトの保守と SDK 更新

### ローカル開発

SDK 開発パッケージでは IntelliJ IDEA（`Developer Mode`）、VS Code（`Run Plugin` / `Debug Plugin`）、Eclipse（`eclipse/Run Plugin.launch`）、またはコマンドラインから、対応するホストと公式プラグイン一式を直接ビルド・起動できます。実行データはプロジェクトの `.dev/` に分離されます。初回は固定の実行パッケージを `~/.cache/pixivdownloader-sdk/` に自動ダウンロードし、検証します。

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

デフォルトのパッケージ形式は、ルートに `plugin.properties` を持ち、ホストの共有クラスを含まない thin PF4J `.jar` です。プラグイン専用のサードパーティー依存がある場合は、`lib/*.jar` に依存を置く PF4J JAR-with-lib を使えます。SDK 更新時は新しい開発パッケージを別ディレクトリに展開し、Javadoc を参照して契約と実行マニフェストを更新し、プラグイン管理ページで有効化・無効化、reload、タスクの drain を確認してください。

## マーケット用資料の投稿

コミュニティ投稿ウィザードでは、プラグインのアイコン、スクリーンショット、外部リンク一覧、多言語の `README`、今回の更新説明、`CHANGELOG.md` を添付できます。`## [v1.2.0]` などのバージョン見出しからの自動抽出にも対応します。すべての文書と画像は同じバージョンの Release とともに署名で固定され、クライアントでは無害化した静的な隔離文書として表示されます。[コミュニティリポジトリの投稿説明](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理)を参照してください。

## 署名と公開

主リポジトリで `pixivdownload-plugin-signature` をビルドした後、`PluginSignatureTool` で Ed25519 キーと署名を管理します。秘密キーはリポジトリとビルドディレクトリの外に保存してください。

```powershell
# 1. キーペアを生成し、公開キーのフィンガープリントを出力
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool keygen --directory "C:\secure\keys"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool public-key --public-key "C:\secure\keys\public-key.pem" --key-id example-2026 --out "public.json"

# 2. プラグインパッケージとリポジトリの manifest.json に署名
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool artifact --artifact "plugin.jar" --plugin-id example-download --version 0.1.0 --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "plugin.jar.sig"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool manifest --manifest "manifest.json" --repository-id example.plugins --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "manifest.json.sig"
```

自前のプラグインリポジトリでは、`manifest.json` + `manifest.json.sig`（`manifest-v1`）を公開するか、`paged-v2` のページ分割エンドポイントを実装できます。最大 64 KiB の公開 HTTPS `repository.json` 記述子を提供すると、利用者がデスクトップ設定からワンクリックで取り込めます。`DIRECT_STRICT` または `GITHUB_RELEASES` のネットワーク方針、`repository-update` のローテーション証明、`plugin-revocations` の撤回リストに対応します。

主リポジトリへ SDK 契約や公式プラグインの修正を寄稿する場合は、[主リポジトリの開発](/ja/development)を先に読んでください。
