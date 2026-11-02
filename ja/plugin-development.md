# サードパーティプラグイン SDK

SDK 開発パッケージの独立プロジェクトで例をビルドして起動し、その後にページ、ダウンロード種別、業務処理を追加します。JDK 17、Node.js、選択したパッケージのビルドツールとランタイムマニフェストを用意してください。

| 作業 | 参照先 |
| --- | --- |
| 最初のプラグインを作る | [テンプレート](#テンプレートから開始)、[ローカル開発](#ローカル開発) |
| ダウンロードとギャラリーを追加する | [種別の追加](#ダウンロード種別を追加する流れ)、[独立ギャラリー](#プラグイン所有ギャラリー) |
| 設定・認証情報・データを保存する | [所有権と保存先](#設定、認証情報、ファイル) |
| 既存プロジェクトを更新する | [SDK 更新](#外部工程の保守と-sdk-更新) |
| 配布する | [マーケット文書](#マーケット文書の提出)、[署名と公開](#署名と公開) |

例の `SDK_VERSION`、パス、ID、ハッシュ、署名を実際の値に置き換えてください。Java / JavaScript の断片はテンプレート内で使うインターフェース例です。import と業務実装を補い、選択したリリースの Javadoc とテンプレートで API の有無を確認します。

- [サードパーティプラグインテンプレート](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info)、[Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)、[Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [Douyin の実装例](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)、[署名ツール](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)
- [SDK ダウンロードとリリース履歴](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)、[バージョン別 Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

Douyin はダウンロード、設定、プロキシ、キュー、予約タスク、専用データと独立ギャラリーを含む SDK の実装例です。公開契約だけに依存し、公式配布には含まれません。新規プロジェクトではサイト固有の処理まで引き継がないよう、`plugin-templates` を起点にしてください。

## ライセンスと配布条件

PixivDownloader と公式プラグインは AGPL-3.0 を使います。[Plugin Linking Exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt) を同梱するバージョンでは、その追加例外も適用されます。公開 SDK/API または文書化された拡張インターフェースを使う独立したサードパーティプラグインは、例外の条件を満たす場合に MIT License を選べます。

プロジェクト作成のテンプレートを独立プラグイン用にコピー・変更することも例外に含まれます。配布時には該当する著作権表示と例外本文を残してください。ホスト、SDK/API、ランタイム、公式プラグイン実装とその変更には該当する AGPL の義務が続き、外部依存には各ライセンスが適用されます。例外を含む開発パッケージは原文を `licenses/pixivdownloader/` に、Maven SDK/API JAR は `META-INF/` に収録します。自分のライセンスを明示し、コピー元の通知も保持します。文書の更新だけで例外のない旧リリースに許可が追加されることはありません。

[公式コミュニティリポジトリ](https://github.com/Sywyar/PixivDownloader-community-plugins) はオープンソースのみを受け付け、完全な公開 GitHub ソース、オープンソースライセンス、再現可能なビルド、人による審査を要求します。公開バイナリや SPDX 表現だけでは不十分です。ライセンス条件、コミュニティへの登録、パッケージ検証と利用者の信頼確認はそれぞれ必要です。

## 最初に信頼境界を理解する

`declarative-process` は別 worker JVM でリソースとプロトコルを分離し、`host-process-full-trust` はホスト JVM で実行します。どちらにも OS セキュリティサンドボックスはありません。コードは現在のアカウントが読めるファイル、ネットワーク、計算資源を利用できます。

Ed25519 署名が証明するのは信頼鍵からの出所とバイトの完全性で、コードの安全性ではありません。利用者は発行者、ソース、リポジトリ運営者を信頼する必要があります。Cookie、トークン、プロキシ、作品ディレクトリと専用データを適切に使う責任は作者にあります。

ホストは構造、サイズ、パス、バージョン、依存関係、SHA-256、署名、来歴を検証し、同じ固定バイトをロードします。具体的なインストール動作は[プラグイン管理](/ja/plugin-management)を参照してください。

## SDK の境界

薄い `pixivdownload-sdk` エントリーは標準 POM 依存関係で 3 つの API モジュールとホスト提供のコンパイル依存を公開します。各 API と BOM の個別利用も可能です。`sdk-info` が完全な SDK バージョン、プレリリースの識別、互換性規則の基準で、アプリ版とは独立しています。`plugin-api` は入口、contribution、ホスト制御、owner 単位の保存機能を、`core-api` は安定した業務ポート、値モデル、中立なアルゴリズムを提供します。

```text
Third-party plugin
  └─ pixivdownload-sdk        provided: one compile dependency
      ├─ pixivdownload-sdk-info    SDK version and compatibility information
      ├─ pixivdownload-plugin-api  Entry points, contributions, paths, and private data source
      ├─ pixivdownload-core-api    Stable download and proxy settings ports
      └─ Host-provided PF4J, Spring, Servlet, Jackson and other compile dependencies

Do not depend on: pixivdownload-app, host implementation classes,
plugin-runtime/installer/signature internals, private services/mappers/controllers
from official plugins, the host DataSource, or private frontend globals
```

ホストは信頼済みプラグイン・パッケージ ID、generation、publication に能力を登録します。特定の外部 ID や作品種別を認識するホスト分岐を追加しません。停止、アンロード、破損、非互換時にはルート、静的資源、i18n、ナビゲーション、ダウンロード種別、キュー、予約機能が撤回されます。利用側は能力の不在を処理し、空白ページ、null エラー、途中だけ完了したタスクを残さないでください。

### 提供できる機能

未使用の `PixivFeaturePlugin` 入口は既定の空リストにします。

| メソッド | 機能 |
| --- | --- |
| `id`, `displayName`, `description`, `displayNamespace` | ID と i18n 表示キー |
| `iconKey`, `colorToken`, `kind` | 許可されたアイコン、色、分類トークン |
| `start`, `stop` | 直接所有する資源のライフサイクル。`stop` は冪等であること |
| `schema` | ホスト協業用の管理テーブル、追加列、パス列。通常の専用保存用途ではない |
| `sharedMainStorage` | 公開されたホスト論理保存領域の要求 |
| `privateSchema` | 現 owner が管理する正本の専用テーブルすべて |
| `privateStorageMigrations` | 起動時・オフライン移行の順序付きデータ変換 |
| `routes`, `staticResources`, `i18n` | アクセス規則、classpath と URL の対応、Web 名前空間 |
| `navigation`, `startupRoutes`, `landings` | 配置、起動先、利用者種別ごとの業務ページ |
| `pageSections`, `uiSlots` | ページ section と制御された Web UI 挿入位置 |
| `guiThemes` | 起動時の GUI テーマ。通常は `process-restart` |
| `guiConfigContributions`, `guiOnboardingSteps` | 設定項目・グループ・section・action・preset、初期案内 |
| `drilldowns`, `userscripts` | 意味に基づくリンク、安定したスクリプト ID と正確な classpath 資源 |
| `scheduledSourceDescriptors`, `downloadTypes` | 純データの予約ソースとダウンロード種別 |

`PixivFeaturePlugin` から Spring Bean を返しません。`PixivPluginProvider.configurationClasses()` で設定クラスを宣言すると、ホストが各有効プラグイン用の子 `ApplicationContext` を作ります。

### 宣言的デスクトップ UI の境界

ホストはツールキット非依存の安定した契約で業務状態、設定の意味、操作を提供します。`gui-swing` と `gui-compose` はそれぞれ完全なページツリー、レイアウト、コンポーネント、対話状態、ウィンドウ、トレイ、テーマ、プラットフォーム連携を所有します。SDK に `DesktopUiDocument` / `DesktopUiNode` などの共通ビュー AST はなく、同じページ実装を共有する要件もありません。

機能プラグインは `GuiConfigContribution` などの純データで業務上の意味を宣言し、ホストが信頼済み owner ごとに統合・検証・保存します。GUI provider が各自の UI で表示します。Swing/Compose コンポーネントやホスト最上位ウィンドウを返さず、ホストも provider ID でページ構造を選びません。

公式配布は Compose と Swing の両方を含み、既定は Compose、代替は Swing です。両者は `process-restart` のため切り替え、インストール、更新、無効化、削除にアプリ全体の再起動が必要です。Compose の Kotlin/Compose と JAR-with-lib は Gradle Wrapper がビルドし、Maven reactor が公式ビルド・署名・配布へ接続します。

## テンプレートから開始

| テンプレート | 用途と内容 |
| --- | --- |
| `minimal-feature-plugin` | 宣言型ページ、ナビゲーション、i18n。別 worker、PF4J 入口、feature、ルート・資源・thin-JAR テスト。Spring 子 コンテキスト はない |
| `download-type-plugin` | 新しいダウンロード種別。5 つの取得モード、キュー、予約ソース、Vue slot、独立ギャラリー、フロント・バックエンドテスト |

リポジトリ内の検証コマンドです。

```powershell
mvn -f plugin-templates/pom.xml clean verify
mvn -f plugin-templates/pom.xml -pl minimal-feature-plugin -am verify
mvn -f plugin-templates/pom.xml -pl download-type-plugin -am verify
```

外へコピーしたテンプレートはルート parent を継承しない独立 Maven プロジェクトです。そのディレクトリで実行します。

```powershell
mvn clean verify
```

### SDK アーティファクトを取得する

開発パッケージは単一の `pixivdownload-sdk` 依存、独立 Maven プロジェクト、Gradle/sbt 例を提供します。固定ランタイムマニフェストを含むパッケージの Run/Debug は現在のプラグインをビルドし、対応ホストと公式プラグイン一式を準備します。実行データは各プロジェクトの `.dev/` に置きます。選択した SDK Release の README で対応機能を確認してください。

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

名前を省略した `@PathVariable` / `@RequestParam` を使う コントローラー では、コンパイル結果に引数名が必要です。Maven の `<properties>` に `<maven.compiler.parameters>true</maven.compiler.parameters>` を設定するか、コンパイラーに `-parameters` を渡します。既存プロジェクトも確認し、パッケージ化した エンドポイント をホスト経由で呼んで binding を検証してください。メソッドを直接呼ぶテストだけでは確認できません。

Gradle は `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`、sbt は `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided` です。Ivy の コンパイル 設定例は次のとおりです。

```xml
<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk"
            rev="SDK_VERSION" conf="compile->default"/>
```

Ivy の ランタイム 設定でこの コンパイル 設定を継承しないでください。公開 API と PF4J、Spring、Servlet、Jackson などはホストが提供します。共有クラスを含めない thin PF4J JAR を作り、テストフレームワークは別途宣言します。

`SDK_VERSION` を統合エントリーのある公開版へ置き換えます。5 座標はエントリー、3 API、BOM です。`developmentRuntime` を持つ版は固定ホストを含み、過去のパッケージは同梱説明に従います。公開 Java API または Maven 利用時の意味が変われば SDK の新しい識別が必要です。公開座標や添付ファイルを上書きしません。

### コピー後にすべての ID を変更する

| テンプレート値 | 変更先 |
| --- | --- |
| `example-download-plugin` | Maven `artifactId` |
| `example-download` | 一意のプラグイン ID、キュー種別、URL prefix、i18n 名前空間 |
| `com.example.pixivdownload.downloadtype` | Java package と対応ディレクトリ |
| `ExampleDownload` | Java 型名 prefix |
| `0.1.0` | アーティファクトと `plugin.version` |
| `plugin.requires=1.3` | 対応 SDK major.minor |
| `plugin.provider=Example Developer` | 発行者 |

ルート、静的パス、フロント定数、専用テーブル、テスト、翻訳も揃えます。記述子 だけ変えると feature・publication の ID と一致せず拒否されます。

## パッケージと入口

`plugin.properties` は JAR ルートに置きます。これは現在のソーステンプレートの SDK 1.3 要件の例です。別パッケージでは実際の最小契約に合わせてコンパイル版と requires を揃えます。宣言だけ変更しても互換性は得られません。

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

| フィールド | 規則 |
| --- | --- |
| `plugin.id` | 一意の小文字 kebab-case。`PixivFeaturePlugin.id()` と一致 |
| `plugin.version` | プラグインアーティファクト版 |
| `plugin.requires` | アプリではなく SDK 要件 |
| `plugin.class` | `PixivPluginProvider` を実装する PF4J メインクラス |
| `plugin.provider`, `plugin.description` | 発行者と説明 |
| `plugin.dependencies` | 任意の PF4J 依存式 |
| `pixiv.*` 表示フィールド | i18n 名前空間・キーと制御された表示トークン |
| `pixiv.replaces` | 置換するプラグインの任意 ID |
| `pixiv.execution-mode` | 必須の `declarative-process` または `host-process-full-trust`。未指定・空白・非対応値は拒否 |
| `pixiv.lifecycle-policy` | 大小文字を区別する `hot-reload`, `backend-restart`, `process-restart`。既定 `hot-reload` |

新しい SDK プレリリースは `alpha.N`, `beta.N`, `rc.N` を使い、N は先頭ゼロなしの正整数です。過去の compact 表記は元の公開 ID を保ちます。比較は channel と数値順なので `rc2 < rc.10`、`rc2` と `rc.2` は同順位です。表記変更による再公開はできません。座標・タグ・パス・ランタイム マニフェスト を選択した Release と正確に合わせてください。旧ツールは同梱手順に従い、新表記の利用には対応開発パッケージが必要です。

安定 SDK は `requiredMajor == hostMajor && requiredMinor <= hostMinor` で承認し、PATCH は承認判定に影響しません。プレリリースは完全な SDK 版の一致が必要です。破壊的契約変更は MAJOR、互換追加は MINOR、互換修正は PATCH を上げます。

## PostHog ブラウザークライアントの再利用

調査を公開する Web プラグインは公式 `posthog` に依存し、`/pixiv-posthog/pixiv-posthog.js` を自身の script より先にロードできます。調査 ID、質問 スキーマ、トリガー、状態、文言、privacy filter と 4 つのプロジェクト値は公開側が所有します。

```properties
plugin.dependencies=posthog?@1.0
```

```js
const posthog = Object.freeze({
  projectToken: 'phc_...',
  surveyId: '...',
  apiHost: 'https://example.invalid',
  uiHost: 'https://us.posthog.com'
});

const client = await window.PixivPostHog?.createSurveyClient({
  ownerKey: 'example-plugin.feedback',
  posthog,
  distinctId: '',
  beforeSend(event) {
    return allowedSurveyEvent(event) ? event : null;
  }
});
if (!client) return; // dependency missing, invalid parameters, SDK failure, or conflict

await window.PixivPostHog.captureSurveyWithAck(
  'example-plugin.feedback',
  'survey sent',
  surveyProperties,
  submissionId
);
```

`ownerKey` は全体で安定した値にします。同じページの同じ owner は、4 パラメーター、`distinctId`、`beforeSend` 関数オブジェクトが変わらない場合だけ クライアント を再利用します。違いがあれば拒否し、別 owner は別プロジェクトを使えます。

`submissionId` は調査 ID、キャンペーン 版、調査単位の匿名 ID から作る安定 UUID です。同じ回答の再試行では同じ値を使い、キャンペーン 更新時だけ変えます。アダプター はイベント最上位の `uuid` に設定し、固定 ingestion エンドポイント の 2xx 後にのみ Promise を解決します。UUID の不足・不正は送信前に拒否します。固定された同梱 SDK をロードし、既定収集を無効化して名前付き分離 インスタンス を作りますが、調査、匿名 ID、回答フィールドは決めません。無効化しても開いたページの JavaScript は撤回できません。更新後に資源がなくなった場合は クライアント 不在として処理します。

### 受信箱に調査を表示する

slot モジュール を持たない `notification.inbox` contribution を追加できます。

```java
new SurveyInboxMessage(
        "example-plugin.feedback-survey",
        "campaign-v1",
        "/example-plugin/survey.html",
        "example-plugin",
        "survey.inbox-title",
        "survey.inbox-body",
        100
).toUiSlotContribution()
```

`messageKey` は安定した一意値、`instanceKey` は キャンペーン 更新時のみ変更します。URL は同じ origin にあるプラグイン所有の絶対パスとし、名前空間 とタイトル・要約の i18n キーも公開します。管理者限定なら ルート も `ADMIN` を宣言します。HTML は自分のページに残し、生 HTML、Bean、ClassLoader を契約で渡しません。不正な調査 contribution は拒否・記録されます。wrapper の代わりに metadata を手作業で組まないでください。

この表示は任意の best-effort 能力です。notification 不在でも調査ページと PostHog 送信は独立しています。ホストは純粋値の active スナップショット を持ち、quiesce 中に発行元を呼びません。起動同期または slot 変更後の次の受信箱要求で冪等保存します。同じ インスタンス は既読状態と unavailable tombstone を再利用し、別 インスタンス は新しい未読になります。contribution 不在、無効化、unload、publication 置換時は表示を隠し、同 インスタンス の復旧用状態を残します。

ホストが URL に `notificationId` と `lang` を追加します。ページは `pixiv-content-height` で高さを通知できます。遠隔調査の恒久終了・削除を確認したときだけ、同 origin の親へ `{type: 'pixiv-survey-unavailable', notificationId}` を送ってください。一時的な接続失敗では送りません。同じ インスタンス を再公開しても閉じたままになるローカル印を作るためです。

### PF4J と Spring 子 コンテキスト

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

外部パッケージは 記述子 と同 ID の null でない feature をちょうど 1 個返します。設定クラスの `@Bean` で明示的に組み立てます。

```java
@Configuration(proxyBeanMethods = false)
public class ExampleDownloadConfiguration {

    @Bean
    ExampleDownloadPlugin exampleDownloadPlugin() {
        return new ExampleDownloadPlugin();
    }

    @Bean
    ExampleDownloadController controller(
            ExampleDownloadQueue queue,
            RequestOwnerIdentityResolver ownerResolver) {
        return new ExampleDownloadController(queue, ownerResolver);
    }
}
```

ホスト ルート package の scan やプラグイン内の無制限 scan に頼らないでください。子 コンテキスト に注入できるのは親が明示公開した Plugin API、Core API、JDK、標準依存で、app 実装ではありません。

## 通知テンプレート

業務シナリオを所有するプラグインが、自身の子 コンテキスト で `top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor` Bean を公開します。mail/push 輸送側の責任ではありません。`NotificationTemplateContribution` は `scenarioId`, `medium`, `locale`, `titleTemplate`, `bodyTemplate` の値です。

```java
@Bean
NotificationTemplateContributor notificationTemplates() {
    return () -> List.of(new NotificationTemplateContribution(
            "example.completed",
            "mail",
            Locale.US,
            "Example completed",
            """
            <!doctype html>
            <html><body><p>{{summary}}</p></body></html>
            """));
}
```

テンプレート公開だけでは新シナリオ登録や送信を行いません。安定した シナリオ/dispatcher 契約が認める ID を使い、他 owner の tuple を上書きしません。輸送側は読み取り専用 `NotificationTemplateCatalog` を使い、設定・render 検査・配信・失敗処理をそれぞれ持ちます。

ホストは publication 準備時に呼び出して不変 スナップショット へコピーし、stop/reload/unload でその publication だけを撤回します。重複 `(scenarioId, medium, locale)` は即時失敗です。検索は正確な locale、次に同言語の決定的 fallback を使います。

HTML は同じ JVM 内の制限付き `String` で渡します。UTF-8 でタイトル 16 KiB、本文 1 MiB、1 publication あたり 256 件・合計 8 MiB です。`InputStream`, `Path`, Spring `Resource`, `ClassLoader`, Bean、遅延 コールバック は寿命や I/O を持つため渡せません。外部値を HTML placeholder に入れる前に escape します。上限を超えるデータや binary が必要なら、寿命と quota を持つ別のホスト所有 streaming/blob-handle 契約を提案し、上限拡大やプラグイン間の一時ファイル交換で代用しません。

## Web ルート、静的資源、i18n

全 コントローラー mapping、静的ディレクトリ、最上位 HTML を同じ owner の `routes()` で宣言します。他プラグインの広い prefix では認可されません。未宣言の `path + HTTP method` は 404 です。UI の非表示は認証の代わりになりません。

| factory | アクセス |
| --- | --- |
| `publicRoute` | 認証不要。solo/multi 共通 |
| `visitor` | multi の未ログイン訪問者。solo は session 必須、招待 guest は不可 |
| `visitorAndInvitedGuest` | multi 訪問者と招待 guest の読み取り |
| `invitedGuest` | 管理者・招待 guest。monitor 保護も適用 |
| `admin` | 管理者のみ |
| `local` | ローカルプロセス手順の例外 |
| `gui` | 信頼済み ローカル リクエスト と GUI トークン の両方 |

HTTP 制限は `WebRouteContribution` コンストラクターの明示 `HttpMethod` 集合を使います。異なる owner の パス と メソッド が重なる場合は同じ `AccessPolicy` が必要です。異なる規則は両 owner と パターン を示して登録に失敗します。同 ポリシー の 名前空間 共有や、同 owner による広い prefix の具体化は可能です。

独立した管理者ページの完全な宣言例です。

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}

@Override
public List<StaticResourceContribution> staticResources() {
    return List.of(
            new StaticResourceContribution(
                    "classpath:/static/", "/example-download-gallery.html", true),
            new StaticResourceContribution(
                    "classpath:/static/example-download-gallery/",
                    "/example-download-gallery/"));
}

@Override
public List<I18nContribution> i18n() {
    return List.of(new I18nContribution(
            "example-download", "i18n.web.example-download"));
}
```

HTML、CSS、JavaScript を分け、表示文言は専用 名前空間 に置きます。外部データは DOM API と `textContent` で描画し、不明な文字列を `innerHTML` に連結しません。

## ダウンロード種別を追加する流れ

現在の タスク 契約は送信 handler、option hook、event observer、要求重複排除、特定 attempt の取消、再接続 スナップショット を提供します。SDK base クラス の継承は必須ではありません。対象契約を含む SDK とホストを使い、現在のソース API が旧版にも存在すると仮定しないでください。

```text
plugin.properties + provider
        ↓
DownloadTypeDescriptor ──→ the workbench discovers the type and acquisition modes
        ↓
same-origin behavior module → import, discovery, queueing, status, filters, and settings
        ↓
plugin controller/service ─→ parses requests and performs real domain work
        ↓
QueueOperations ─────────→ cancellation, clearing, and lifecycle drain
        ├─ WebUiSlotContribution (optional)
        ├─ ScheduledSourceDescriptor + executor (optional)
        └─ plugin-owned independent gallery (optional)
```

### 1. 種別を宣言する

公開 `QueueTypeContribution`、`independentPage`、gallery capability bag、記述子 内 `uiSlots` はありません。種別、キュー、UI slot、予約 ソース、独立ページは個別の契約です。

```java
@Override
public List<DownloadTypeDescriptor> downloadTypes() {
    return List.of(new DownloadTypeDescriptor(
            DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
            "example-download",
            "example-download",
            "batch.kind",
            900,
            "download",
            "green",
            "/example-download/example-download-type.js",
            List.of(
                    DownloadAcquisitionMode.SINGLE_IMPORT,
                    DownloadAcquisitionMode.USER_PROFILE,
                    DownloadAcquisitionMode.SERIES_COLLECTION,
                    DownloadAcquisitionMode.SEARCH,
                    DownloadAcquisitionMode.QUICK),
            true,
            List.of("example-ready-filter"),
            List.of("example-output-setting"),
            "example-download"));
}
```

| フィールド | 条件 |
| --- | --- |
| `contractVersion` | 現在の `CURRENT_CONTRACT_VERSION`、値は 1 |
| `type` | 一意値。通常 `QueueOperations.queueType()` と同じだが registry は一対一を仮定しない |
| `displayNamespace`, `displayI18nKey` | 名前空間と種別名の素のキー |
| `order` | 安定した表示順 |
| `iconKey`, `colorToken` | ホスト許可トークンのみ。URL/HTML/任意 CSS は不可 |
| `moduleUrl` | 同じ owner の静的 contribution に属する同 origin 絶対 `.js` パス、必須 |
| `acquisitionModes` | `single-import`, `user`, `series`, `search`, `quick` の部分集合 |
| `cancelSupported` | true なら キュー item 最上位に `cancelWorkKey` が必要 |
| `filters`, `settings` | 動作 モジュール が実装する許可済み契約 ID |
| `i18nNamespace` | モジュール の状態・エラー文言用 |

### 2. フロントエンド動作を実装する

ホストの実際の `<script>` 評価期間に `PixivBatch.queueTypes.registerModule(initializer)` を呼びます。Vue の複製やホストの `state`, `saveQueue`, `renderQueue`, `updateStats`、内部 DOM ID の直接操作は禁止です。

管理者ページは `DownloadTasks` スナップショット を既存 キュー に統合します。`process(item, context)` は バックエンド が返した実行 UUID で `context.bindTask(taskId)` を呼びます。作品 ID は実行 ID ではありません。他経路の タスク は「他の経路」と表示し再送信しません。終端 スナップショット は 5 分で失効し、保存済み キュー 記録は残ります。実行を確認できない行は未確認のまま保ち、再読込で送信し直しません。

| 入口 | 役割と省略条件 |
| --- | --- |
| `process(item, context)` | 必須。プラグイン API に送り `context.updateItem(patch)` で許可状態を反映 |
| `import` | URL 判定、単一作品 item と cancelWorkKey。single-import 未宣言なら省略 |
| `acquisition.user` | ユーザー 入力、ページ探索、表示、キュー metadata |
| `acquisition.series` | series URL、ページ、順番、metadata |
| `acquisition.search` | 検索・範囲取得・表示・キュー 同期 |
| `acquisition.quick` | quick action と `context.publishWorks(payload)` による結果公開 |
| `filters`, `settings` | 記述子 内 ID のみ。空なら追加機能なし |
| `slots` | 同 origin モジュール の宣言的断片。独立動的 slot は `WebUiSlotContribution` |

各 acquisition はその モード を未宣言なら省略します。initializer は `AbortSignal`, `isActive()`, `assertActive()`, `onCleanup()` を受け取ります。非同期結果の反映前に publication の有効性を確認し、リスナー・タイマー・コンポーネント を解放します。

### 3. 要求の owner を解決する

コントローラー は親の `RequestOwnerIdentityResolver` で現在の要求から owner を取得します。

```java
RequestOwnerIdentity identity = ownerResolver.resolve(request);
queue.submit(command, identity);
```

JSON、query、独自 ヘッダー の owner UUID は信頼しません。記述子 の `pluginId/packageId/generation/publicationId` は公開単位の現行性だけを表し、認証済み ユーザー ではありません。実ダウンローダーはファイルの永続化と履歴・ソース 関係などの成功事実を commit してから完了を報告します。テンプレートのメモリ上の完了は決定的なテスト fixture です。

### 4. QueueOperations と drain

```java
public final class ExampleQueue implements QueueOperations {
    @Override public String queueType() { return "example-download"; }
    @Override public void cancel(String workKey, String ownerUuid, boolean admin) { /* ... */ }
    @Override public int clearAll() { /* ... */ }
    @Override public int clearForOwner(String ownerUuid) { /* ... */ }
}
```

`workKey` は当該 キュー 内の安定した不透明文字列で、数値限定ではありません。URL パス segment に入れません。ホストは `POST /api/download/queue/{queueType}/cancel` の JSON で元の 鍵 と publication 識別情報 を送ります。フロントエンド は自身で制御要求を組まず ホスト bridge を使います。

バックグラウンド処理のない完全な同期実装だけが generation-0 の完了 drain を使えます。キュー、executor 引渡し、遅延 コールバック など呼出しより長寿命の処理がある場合は次を実装します。

1. `prepareQuiesce(registeredQueueType)` で新 タスク の受付を原子的に止め、正 generation の実 `QueueDrain` を返します。
2. ホスト が drain を保存した後に `cancelQuiescedTasks()` で協調取消を送ります。
3. 全 active タスク の終了後だけ drain を完了します。
4. prepare 再呼出しでは同じ `queueType + generation`、新 インスタンス では新 generation を返します。

完了 sentinel で非同期終了を装わないでください。executor、scheduler、HTTP/WebSocket クライアント は子 コンテキスト が所有し、閉鎖時に解放します。

### 5. UI slot

slot は ダウンロード 記述子 と独立して公開します。

```java
@Override
public List<WebUiSlotContribution> uiSlots() {
    return List.of(
            new WebUiSlotContribution(
                    "example-download.settings-card",
                    "settings-card",
                    "/example-download/example-download-type.js",
                    900),
            new WebUiSlotContribution(
                    "example-download.quick-actions",
                    "quick-actions-mine",
                    "/example-download/example-download-ui-slot.js",
                    900));
}
```

動的 slot は `PixivVue.mountUiSlot` と owner 範囲の `context.supports(type, mode)`, `context.dispatchQuickAction(action)`, `context.onCleanup(...)` を使い、Vue ランタイム を同梱しません。

### 6. 予約タスク

feature は純データの `ScheduledSourceDescriptor` を返し、`ScheduledSourceExecutor` と `ScheduledWorkExecutor` は子 コンテキスト Bean として提供します。ブラウザー モジュール の `capture` は スキーマ/バージョン 付き定義を保存し、`restore` は editor へ復元、`summary` は制御された表示構造を返します。

ホストは tick、永続状態、並行実行制御、管理 API、終了時の処理完了待ちを担当します。取得元と作品実行機能は各プラグインが公開・撤回し、ダウンロードワークベンチへの依存は不要です。認証情報の管理には `plugin.api.schedule.management.ScheduledTaskCredentials` を使い、ホストの `ScheduleService` は参照しません。管理画面は `NavigationPlacements.DESKTOP_SCHEDULE_MANAGE` でデスクトップの入口を提供できます。

入力へのアクセスは publication 単位の `context.acquisitionInput(mode)` と `context.restoreAcquisition(mode, value)` のみです。現在の中立な第三者 アダプター は `single-import` だけを公開します。ホスト DOM や内部 モード globals で別 モード を模倣しません。

ソース executor は定義 スキーマ、探索、checkpoint、work executor は payload スキーマ と同期処理を所有します。ホスト は claim、lease、認証情報、guard、pending、取消、checkpoint CAS を担当します。`COMPLETED` / `ALREADY_COMPLETED` はファイルと成功事実の永続 commit 後だけ返します。プラグイン/executor 不在時は保存 タスク を停止したまま保ち、削除や checkpoint の先行更新をしません。

## プラグイン所有ギャラリー

独立ページは `independentPage` という API ではなく、自身の HTML/CSS/JS、ルート/リソース/i18n、コントローラー/API・可視性・モデル、必要な navigation を所有する構成です。管理者ページ例で実装すれば停止時に publication ごと撤回され、ホスト に種別専用分岐は不要です。

上部に空の切替 slot を置きます。

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

`navigation()` には自分の入口だけを登録します。

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

ページに Pixiv、Douyin など別 プラグイン ID を列挙せず、ホスト が active publication から統合する項目を使います。項目は hot lifecycle に追随します。

- `/pixiv-gallery.html` は公式 Pixiv の主 gallery で、外部種別用の汎用 shell ではありません。
- 汎用 gallery provider/registry/broker、`/api/gallery/unified/**`、`unifiedGallery` ABI/wire は削除済みで、互換面はありません。
- gallery/novel の内部実装の複製、ホスト DB や app 実装への直接依存を避けます。
- asset 配信、削除、可視性、検索、collection、統計は自身の API とデータモデルが担当します。必要な協業が SDK にない場合は中立な公開契約を提案します。

Douyin の `/pixiv-douyin-gallery.html`、詳細ページ、`/api/douyin/gallery/**`、`gallery.type-switch` が全体の実装例です。新規開発は `download-type-plugin` の独立 gallery を基準にします。

## ギャラリーのナビゲーションスロット

`NavigationContribution.placements` の `gallery.sidebar` は プラグイン・ダウンロード・gallery など主ページ、`gallery.sidebar.tools` は招待管理・統計・重複などの道具です。小説は `novel.sidebar` / `novel.sidebar.tools` を使います。各 グループ は独自の順序と要求 識別情報 filter を持ち、撤回で項目が消え、空 グループ は非表示になります。ページは ID 分類なしに `data-nav-slot` を宣言します。

## 設定、認証情報、ファイル

| 内容 | パス | アクセス方法 |
| --- | --- | --- |
| ホスト 設定と プラグイン 有効化 | `config/config.yaml` | SDK の読取専用 semantic ポート で必要最小限を取得。直接読み書きしない |
| プラグイン 業務設定 | `config/plugins/{pluginId}.properties` | 子 `Environment`, `@Value`, `@ConfigurationProperties`。直接管理は `RuntimePathProvider` |
| 認証情報 | `config/credentials/{pluginId}.properties` | ホスト が暗号 envelope を管理し、宣言済みの現 owner 値だけを復号・注入 |

業務キーは プラグイン ID を prefix にします。

```properties
example-download.download.directory=
example-download.proxy.mode=inherit
example-download.download.include-cover=false
```

```java
@Bean
ExampleSettings settings(Environment environment) {
    return new ExampleSettings(
            environment.getProperty("example-download.download.directory", ""),
            environment.getProperty("example-download.proxy.mode", "inherit"));
}
```

GUI 項目は `GuiConfigContribution` で宣言し ホスト が owner ごとに保存します。機密値や `PASSWORD` は通常 properties に書きません。プラグイン は注入値だけを読み、envelope の読取・復号・書換えをしません。owner 別ディレクトリや暗号化も同 JVM の悪意あるコードを隔離するものではありません。

### 安定したパスとデータベース

注入される `RuntimePathProvider` は owner に束縛済みなので caller が ID を指定しません。

```java
Path config = runtimePathProvider.configFile("properties");
Path state = runtimePathProvider.stateDirectory();
Path data = runtimePathProvider.dataDirectory();
```

`state/{pluginId}` は再構成可能な状態、`data/{pluginId}` は管理データと キャッシュ に使い、作品を置きません。専用 DB は選択した provider を使うため データ ディレクトリから固定 DB ファイルを仮定できません。

切替可能な DB を使うには owner-bound `PluginDatabase` を注入し、`privateSchema()` に正本となる全専用テーブル、`privateStorageMigrations()` に順序付き変換を宣言します。provider は子 コンテキスト 起動前に表作成、安全な列追加、構造検証を行います。offline 移行も同じ宣言を使います。空宣言で既存 DB を捨てたり無視したりはできません。

| API | 契約 |
| --- | --- |
| `PluginDatabase.read(work)` | 同 スレッド の読取専用 コールバック。書込を拒否。返却で session 失効、実体化した結果のみ返す |
| `PluginDatabase.transaction(work)` | 現 owner の専用 DB への原子的書込。ホスト Spring transaction とは別。失敗後は rollback のみ |
| `available()` | 現在の利用可否。空 DB や書込成功の証明ではない |
| `databaseId()` | 診断 ID。SQL 方言の分岐に使わない |

中立な関係演算を使い、方言・接続・認証情報は provider が所有します。能力不在・故障は SQLState `08003` の `SQLException` です。読取失敗を空結果にせず、書込失敗後に副作用を続行しません。同梱 driver や SQLite 固有 SQL は自動変換されません。

`PluginMainDatabase` は自分の管理 main テーブル と `sharedMainStorage()` で明示要求した公開論理領域だけを提供します。他 owner の テーブル・専用 DB は使えず、未知 ドメイン は組立を拒否します。read/transaction は同期・同 スレッド、書込は ホスト transaction に参加してファイル公開と共同 commit できます。DB 不在、閉じた コンテキスト、不正宣言は拒否され、失敗した transaction は commit できません。

既存の `PluginDataSource` は `data/{owner}/plugin.db` の SQLite 専用です。別方言では SQLState `0A000` となり、別 SQLite fallback を作りません。caller が JDBC 互換性を管理し、close、unwrap、main DB アクセスをしません。エンジン間移行には完全な 専用 スキーマ と変換宣言も必要です。インストール成功だけで判断せず移行報告を確認します。

作品の既定保存先は `DownloadSettings.getRootFolder()` を継承して自分のサブディレクトリを使います。

```java
Path defaultOutput = Path.of(downloadSettings.getRootFolder())
        .resolve("example-download")
        .normalize();
```

Douyin は `{rootFolder}/douyin` の下を owner ごとに整理し、`config/plugins/douyin.properties` に設定された保存先があれば優先します。サブディレクトリ、名前、移行処理は プラグイン 自身の責任です。

app の `RuntimeFiles`, `DownloadConfig`, `ProxyConfig`, mapper, `SqlSessionFactory`, main `DataSource`、executor Bean 名に依存しません。パスと保存は Plugin API、ダウンロード/proxy の意味は `DownloadSettings` / `OutboundProxySettings` を使います。[保存原則](/ja/storage)と[設定参照](/ja/configuration)も確認してください。

## 外向き HTTP と WebSocket

純粋な JDK 型の安定 factory を使います。

```java
@Bean(destroyMethod = "close")
OutboundHttpClient exampleHttpClient(OutboundHttpClientFactory factory) {
    return factory.open(OutboundHttpClientProfile.standard(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            OutboundHttpRoute.inherit()));
}
```

`exchangeStream` の live response は全経路で close します。`exchange` は全体を バッファ に読み自動 close します。non-2xx も response として返るので業務 caller が判断します。

WebSocket は `OutboundWebSocketClientFactory.open(profile)` を使い、クライアント Bean を子 コンテキスト とともに閉じます。プラグイン は timeout、redirect、cookie、pool、中立 ルート profile を指定し、ホスト は transport、global/タスク proxy と `ProxySelector` を管理します。独自 `java.net.http.HttpClient` / `ProxySelector` を作ったり Apache 型や app HTTP 設定に依存したりしません。サイト固有の認証 ヘッダー や プロトコル message は業務処理側に置きます。Douyin が全体の実装例です。

## 独立サービス契約と組み合わせ

以下の API には SDK 1.4.0 が必要です。`typed-service-plugin` テンプレートを使い、API、契約キャリア、提供者、利用者を別モジュールにします。業務バージョンは契約側で管理します。キャリアは `pixiv.contract-api` と `pixiv.contract-sha256` で元の API JAR を指定し、`process-restart` を使用します。提供者と利用者はキャリアへの依存を宣言し、API クラスを重複して同梱しません。

`PixivFeaturePlugin.serviceImports()` に利用する key、`serviceExports()` に実装 Bean を宣言し、child context に `PluginServices` を注入します。コンストラクターでは参照の保存や binding の登録ができますが、業務呼び出しには利用者の有効な publication が必要です。インターフェースと record/enum は同じ契約 classloader とコードの出所に属する必要があります。契約パッケージ間の依存は未対応です。境界 DTO は明示的に変換してください。

`find(key)` は不在時に空、`require(key)` は `UNAVAILABLE` を返します。複数実装がある場合は両方とも `AMBIGUOUS` です。`findAll(key)` は変更不能な `List<ServiceProvider<T>>` を返し、空集合も有効です。上限は 128 メンバーです。`service()` は利用者と提供者の正確な publication に固定されたプロキシ、`publication()` は信頼された識別情報です。公開順は業務上の優先順位ではありません。選択とソートは利用者が定義し、撤回されたプロキシは代替実装へ転送しません。

一意な依存には `bind(keys, activation)`、空集合を含む実装集合には `bindAll(keys, activation)` を使います。各回の `scope.require(key)` または `scope.findAll(key)` から取得し、`scope.acquire(...)` と `scope.guard(...)` でリソースと補助コールバックを管理します。メンバー変更時は旧回の受付を閉じ、実際の終了と清掃を待ってから新しい回を開始します。activation で業務コマンドを再実行してはいけません。`FAILED` と `CLOSE_FAILED` は明示的に `retry()` でき、清掃完了を示すのは `CLOSED` だけです。

単純な配信には独立した `EventSink.onEvent(record)` 契約を定義し、`findAll` を順番に呼び出して `ServiceAccessException` を契約に従って処理します。best effort は補助的な副作用に限ります。必須検証は書き込み前に完了し、不在や失敗時は書き込みを拒否します。集合 API は永続メッセージ、自動再試行、背圧、配信保証を提供しません。

長時間タスクは業務契約内の `start → jobId → query/cancel/result` という純値プロトコルで扱います。`PluginRuntimeTaskRegistrar` に仕事を登録し、`PluginResources` で実行器と資源を所有し、提供者自身がキャンセルに応答します。ハンドルのキャンセルは終了証明ではなく、宿主は実際の drain を待ちます。再起動後の復旧、checkpoint、冪等性はデータ owner の私有 DB で定義します。Future、スレッド、コールバックをサービス値として渡せません。

`binding.diagnostic()` は有界な純値の理由、状態、依存、publication を返します。管理画面のサービス関係と停止影響は同じ登録情報を使い、宣言された import と drain 中の binding を含みます。待機、曖昧、activation 失敗、実行器の拒否、清掃失敗を区別できます。`truncated` は表示の省略を意味し、依存がないという意味ではありません。診断の読み取りで業務の再実行や状態変更は起こりません。

## GUI フィールドに依存しない認証情報と操作検証

`credentialKeys()` で `my-plugin.token` のような owner 接頭辞付きのキーを宣言し、`PluginCredentials` を注入します。`read(key)` は `Optional<String>`、`update(Map)` は原子的な差分更新です。省略したキーは保持し、空白値は削除します。`delete(key)` は冪等です。宣言・更新は最大 128 項目、値ごとに UTF-8 64 KiB、更新全体で 1 MiB です。未宣言キーと旧 generation のハンドルは拒否され、別 owner は指定できません。

認証情報のキーを通常の GUI フィールドとしても宣言することはできません。同じキーのフィールドは機密扱いが必要です。競合すると宿主はその owner の認証情報とデスクトップ設定のスナップショットを拒否し、検出済み機密キーのマスクは保持します。GUI の認証情報スナップショット、書き込み、失敗時の復元は一つの owner ロック内で実行します。読み込みと再読み込みでも、認証情報の取得と移行判断を同じロック内で行い、古いスナップショットや平文で成功済みのプログラム側の更新を上書きしません。認証情報を含む環境エクスポートは GUI のない宣言済みキーも含み、暗号化が必須です。認証情報を除外すると値は出力されません。復元時も owner とインストール世代を確認します。

暗号化とファイルロックは宿主の既存処理を使います。既存 GUI の機密フィールドも同じ宣言・マスク対象です。保存しても既存の `@Value` や設定オブジェクトは自動で再バインドされません。必要時に読み取るか、既存の再起動方針に従ってください。平文を DTO、例外、ログ、診断に含めないでください。

プラグインの子 context に `OperationVerification` Bean を登録します。`policyId()` は安定したポリシーキーです。副作用のない `policy()` はルールのリビジョン、コンテキスト schema、対話プロトコル、構造化リソース profile を返します。ルールやリソースを変更した場合はリビジョンを変更し、ページを再公開します。`generation()` は認証情報の世代を表し、古いチャレンジを無効にします。`verify()` は必要な純粋値と不透明な proof のみを受け取り、`APPROVED`、`REJECTED`、`CHALLENGE` を返します。パスワード、Cookie、リクエスト全体、業務コールバックは渡されません。

業務 owner は `serviceImports()` で `OperationVerification.IMPORT` を宣言し、自分に結び付いた `OperationGuard` を注入します。既存の本人情報を使用する業務 API では、本人確認、権限、CSRF、レート制限、入力を検証してから `execute(operationId, subject, context, token, proofs, action)` を呼び出します。操作 ID は同じ owner に属し、通常業務の subject は信頼できる本人確認結果から取得します。ログイン準備では形式検証済みのユーザー名を使いますが、認証済みとは扱いません。すべての必須ポリシーが通った場合のみ、owner 内で action を一度実行します。リースは業務処理の実際の終了まで保持されます。不在、時間超過、世代変更時は拒否し、業務失敗を自動再試行しません。補助コールバック用の `ServiceBindingScope.guard()` をユーザー操作に使用しないでください。

準備エンドポイントは `OperationGuard.prepare(operationId, subject, context)` を呼び、業務を実行しません。送信時は引き続き `execute` で再検証します。ブラウザーの `createOperationTarget` の第 3 引数はキャンセルシグナルを受け取るフォーム所有者の準備コールバックです。`ready()` が true の場合だけ `request()` で送信データを取得できます。準備と送信は同じ主体とコンテキストに結び付けます。入力変更時は `reset()`、認証無効時は `invalidate(id)` で準備をキャンセルし、証明を消去します。業務は自動再送しません。操作方法は[ページエディター](page-editor.md)を参照してください。

ページ owner は `EditablePage.targets` に `PageInteraction.Target` を宣言し、`PageComponent` の参加宣言でポリシーとプロトコルを指定します。互換対象が一つなら自動で関連付け、複数なら選択が必要です。対象がなければ下書きのみ保存できます。コンポーネントはまず `context.interaction.prepare()` を呼び、`context.interaction.onState()` で自分のチャレンジを受け取り、`respond(id, proof)` で応答します。状態消去や破棄時は Worker、リクエスト、リスナーを終了します。フォーム owner は `/js/pixiv-pages.mjs` の `createOperationTarget` を使い、ページのマウントに対象を渡します。SDK の型は `page-interaction.d.ts`、`operation-verification.d.ts`、`page-components.d.mts` にあります。通常の `PagePort` 接続は DAG のままです。

ログイン送信はレート制限、検証、パスワード照合、セッション作成の順です。`setup.login-verification-policies` は最大 16 件の静的必須ポリシーです。空値で消えるのは静的要求だけで、ページ公開は設定済みの静的要求を解除できません。`POST /api/auth/login/prepare` はユーザー名と rememberMe だけを送り、ログインのレート制限を共有します。準備成功時は 200 と `token`、`revision`、ポリシー別 `challenges` を返します。すべての証明を取得してから、ログイン要求に `operationToken`、`operationProofs` と認証情報を送ります。証明なしで直接送信した場合は 409 のチャレンジを返します。拒否は 403、必須能力の利用不可は 503、古いチャレンジは 409 で、失敗時に Cookie は作成しません。チャレンジは主体、操作、コンテキスト、ルール、認証情報、provider、正式リビジョンに固定し、5 分で失効、一度だけ消費します。チャレンジ/proof は各 UTF-8 16 KiB、合計 64 KiB、保留中は最大 256 件です。

固定の `login.verification` UiSlot も同じ参加プロトコルを宣言し、インスタンス専用の対話チャネルを受け取ります。クライアントの成功イベントは権限を付与しません。`PagePublications` がページ、関連付け、操作要求、リソース承認を同じリビジョンで保持します。事前検査は外部サービスを呼ばず、コミット時に再検証して対象操作の終了を待ちます。リソースの接続元は管理者の明示承認が必要です。関連付けのないページやカスタムコンポーネントのサンドボックスには権限を継承しません。[ページエディター](page-editor.md) と [独立 CAPTCHA サンプル](https://github.com/Sywyar/PixivDownloader/tree/master/examples/captcha-plugin)を参照してください。サンプルには Turnstile、ALTCHA SHA-256、PBKDF2 が含まれます。

## 通常のスロットとページコンポーネント

`/js/pixiv-ui-slots.js` を読み込み、`data-ui-slot` を設置し、`PixivUiSlots.connect({targetPrefix, properties, regions, onEvent, i18n})` の後に `refresh()` を呼びます。既定の一覧は従来のルート権限を保持します。公開ページには対象を限定した公開一覧が必要です。モジュールはスクリプト評価時に `registerModule(moduleUrl, mount)` を呼び、必要に応じて `update(properties)` と `destroy()` を返します。

`mount(root, context)` は純値の属性、イベント、明示的 region を使い、他ページの私有グローバルを読みません。`context.fetch` は発信元 publication に固定され、宛先 API の認可も維持します。`signal`、`on`、`interval`、`onCleanup`、`loadScript`、`loadStyle` はインスタンスと共に清掃されます。一覧は 2 秒ごとに更新し、非表示中は新しい更新を停止します。複数インスタンスでモジュール読み込みを共有しても、一つの終了は他を撤回しません。非同期処理後に signal を確認し、遅い応答で旧 DOM を再生成しないでください。離脱時にインスタンス、リスナー、タイマー、リクエストを解放します。型は `META-INF/pixivdownload/web/ui-slots.d.ts` を参照してください。

同じ target の要素は一覧の `order` に従い、再有効化や追加時は新しいノードを所定の位置に挿入し、変更のないインスタンスとフォーカス、選択範囲を保持します。既存ノードの移動では `moveBefore` の対応を確認し、入力欄と編集可能なテキストのフォーカス、選択範囲を保持します。未対応ブラウザーには互換処理を使います。遅い一覧応答は新しいインスタンスを撤回できません。`root` 外にノードを作成した直後に、そのノード参照を解放する処理を `onCleanup` に登録してください。コントローラー生成時には破棄処理を別に登録し、スクリプトの失敗や読み込み中の撤回でも資源を解放します。

ダウンロード作業台のキュースロットは既存の Vue/キュー契約を維持します。通常のスロットは Swing/Compose 共通のデスクトップ表示規約ではありません。小説とシリーズのページは properties、regions、イベントを AI/TTS スロットへ渡し、業務モデルは双方で定義します。

エディターの端口は `PagePort(name, contractId, schema)` です。接続する両端のバージョン付き契約識別子と解析済み Schema は一致する必要があります。Schema は SDK 生成器の対応サブセットです。不明な端口、重複辺、有向循環は保存時に拒否し、値は実行時にも検証します。mount 中のイベントは全インスタンスの mount 後に順次配送し、A→B→C を通過できます。後から追加したインスタンスへの履歴再送はありません。ページの待ち行列は 256 件・16 MiB、連続配送は 4096 回までで、超過は明示的に失敗します。保存するのはプロジェクトデータ、純値、制御されたスタイルだけです。業務呼び出しには生成クライアントとインスタンス fetch を使います。

## 複数サービス生成・値の符号化・契約の更新

独立 API モジュールで Schema を生成してから、利用モジュールの全サービスを一度に指定します。以下のディレクトリー、classpath、クラス名は置換してください。classpath の区切りは Windows がセミコロン、Unix がコロンです。

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

同じ単純名でも完全修飾名により Java アダプターと `web/generated/<完全修飾名>.mjs`、`.d.mts` を分離します。`remote` は最大 128 サービス、空リストは全生成物の撤回です。サービス削除時は旧 class・JS・宣言を清掃します。失敗時は管理対象生成物と完了マーカーを撤回するため、パッケージ化は `target/remote-generation.complete` を確認してください。全体のコンパイル後にマーカーを公開しますが、複数ファイルの書き込みは単一ファイルシステムトランザクションではありません。出力全体は 16 MiB、各ファイルは 1 MiB までです。

通常の JSON 整数は Java の型範囲と JS の安全整数範囲の共通部分に限ります。long 全域を使う record 属性には `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)` を明示します。UUID、Instant、Duration、float、double は固定の可逆文字列、小文字 UUID、`秒:9桁ナノ秒`、IEEE 754 の固定長小文字 16 進表現です。非正規表現、範囲外、非有限浮動小数を拒否し、負のゼロとナノ秒を保持します。JS Date による精度の切り捨ては避けてください。生成クライアントの `wireValues.int64/uuid/instant/duration/float32/float64` で型付き値を作成できます。その他の型は独立契約内で対応 DTO に変換し、任意 serializer は登録しません。

`contract-evolution` はクラスをロードせず classfile を読み、ソース互換性、バイナリリンク、新宿主から旧実装への呼び出しを別々に報告します。業務動作は常に `NOT_ASSESSED` です。抽象インターフェースメソッドの追加は旧リンクを維持しても、新しい呼び出しで未実装メソッドに到達する可能性があります。再コンパイル、旧プラグインの元バイト列でのロード、実呼び出しを別に検証してください。終了コード 0 は動作互換性の証明ではありません。出力ファイルは未作成である必要があります。`verify-sdk-usage` は最低中央 SDK 宣言を検査する別の検査です。


## 外部工程の保守と SDK 更新

プレリリースは例として properties の `plugin.requires==7.2.3-rc.4` のように完全版を要求します。安定版は `MAJOR.MINOR` です。コンパイル 依存・記述子・ランタイム を揃え、RC 間または RC と安定版間の互換性を仮定しません。過去の major/minor 宣言は コンパイル 時の RC を特定できないため、検証して新 プラグイン 版を公開し、旧添付を上書きしません。

元プロジェクトと `.dev/` を保持し、新 SDK を別途展開します。ソース・Javadoc を比較し、tools、契約資源、ランタイム マニフェスト を一緒に更新して ビルド/IDE 設定を統合します。専用 DB 移行は プラグイン の責任です。未知形式や移行失敗では旧データを残して該当機能を停止します。ホスト パッケージ transaction は業務データを rollback しません。

Windows は `mvnw.cmd clean verify`、他環境は `./mvnw clean verify`、続いて同 wrapper の `verify exec:exec@sdk-run` を使います。管理画面で開始・無効化・再開始・ポリシー が許す reload を検証します。ルート 撤回、旧 publication 無効化、本当の タスク drain、データ・未完 タスク 保持を確認します。`process-restart` の置換は完全終了後に版を確認し、開発 インスタンス は `exec:exec@sdk-stop` で止めます。開発ディレクトリの full-trust 実行だけでは packaged worker を検証できません。

`DownloadObserver` は永続 replay のない同期 best-effort 観察です。`DownloadAdmissionPolicy` は副作用前の許可/拒否のみで要求を書き換えません。両方とも任意の full-trust Bean として ホスト が公開・撤回します。`WorkFileImporter` は元ファイルへの読取専用参照を登録し、type owner 不在なら成功にしません。対象 Release の Javadoc を確認してください。

投稿失敗は失敗した手順から調べます。ダイジェスト 不一致は同じ版を再取得、candidate 不在は既定 branch の現 CI と `tools/candidate-projects.json`、認証・push 失敗は現 アカウント・リポジトリ・権限を確認します。審査事実、所有権、リモート 状態が変われば元要求を再読込し、応答喪失で新規投稿を自動作成しません。共有診断には ID・版・ソース SHA・SDK 識別情報・エラー コード を残し、認証情報・秘密鍵・個人パスを除きます。

## ビルド、テスト、デバッグ、インストール

### 必要なテスト

テンプレートにある 記述子/provider/feature の一致、明示 コンテキスト・コントローラー 組立、ルート/リソース/i18n/スキーマ/ダウンロード 公開、キュー owner・opaque 鍵・clear・drain、フロントエンド `node --check` と動作テスト、JAR ルート 記述子 と thin-JAR 境界を保持します。

```powershell
mvn -f plugin-templates/pom.xml clean verify
```

フロントエンド 契約は Java の文字列検索だけで検査せず、Node で実行してください。

### ローカル開発

JDK 17 と Node.js を用意し、`java` と `node` を `PATH` に置きます。IDE import は依存解決だけです。明示 Run/Debug が現在の プラグイン をビルドし、固定 ランタイム を準備して全アプリを起動します。ビルド 失敗時は止まります。Maven Wrapper が固定 Maven を取得するため ホスト checkout や JAR の手動コピーは不要です。最初のアプリ設定は ホスト setup に従います。

| IDE | import | Run | Debug |
| --- | --- | --- | --- |
| IntelliJ IDEA | ルート `pom.xml` を開く | `Developer Mode` の Run | 同じ設定の Debug |
| VS Code | ディレクトリ を開き推奨 Java Extension Pack を入れる | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin` |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` グループ |

IntelliJ は native Application 設定です。先に Maven `test-compile exec:exec@sdk-prepare` を実行し、IDE が直接 JVM を起動して ホスト、公式 プラグイン と現プロジェクト `target/classes` をロードします。`ExampleMinimalPlugin.java` の `routes()` に breakpoint を置いて Debug してください。リモート 接続や固定 ポート は不要です。`src/test/java/sdk/DevelopmentLauncher.java` は プラグイン JAR に入りません。設定は同梱 tools を classpath に足し、Spring Boot の nested JAR 解決を可能にします。

この設定は開発 モード を明示的に有効にし、現ソースを `host-process-full-trust` で実行して status にも表示します。ソース 記述子 は変えず、Run/Debug ごとに再compileします。`Stop Plugin` または Application session の終了で止めます。

VS Code、Eclipse、CLI は現 JAR を インストール し、宣言された モード を保って package を検証します。既定 debugger `127.0.0.1:5005` は declarative worker または full-trust ホスト に接続します。`Stop Plugin` または グループ 全体を止めてください。debugger の切断だけではアプリは残ります。

Windows:

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

Linux/macOS:

```bash
./mvnw verify exec:exec@sdk-run
./mvnw verify exec:exec@sdk-debug
./mvnw exec:exec@sdk-stop
```

`sdk-debug` は IDE attach を待ち、debugger を開きません。`clean verify` は検証、`exec:exec@sdk-prepare` は ランタイム 準備だけです。既定 成果物 は `target/example-minimal-plugin-0.1.0.jar`。現 JAR の ビルド 成功後には直接 tools を呼べます。

```text
java -jar tools/sdk-tools.jar run <absolute-project-path> <absolute-current-plugin-JAR> --no-gui
java -jar tools/sdk-tools.jar debug <absolute-project-path> <absolute-current-plugin-JAR> --debug-port=5005
java -jar tools/sdk-tools.jar stop <absolute-project-path>
```

`--debug-connect` は既に待機する IDE へ接続します。run/debug は プロジェクト 内で `.dev/` 外の 成果物 を受け取り、宣言 モード を保ち、現 SHA-256 を確認して ローカル インストール します。公式 プラグイン の署名・来歴確認は開発時にも package 検証時にも維持されます。公式 プラグイン と重複しない ID にしてください。

### ランタイム、キャッシュ、プロジェクトデータ

`sdk-project.json` と release 側 `sdk-release.json` は同じ SDK、ホスト、公式 マニフェスト、ランタイム ZIP の 識別情報・サイズ・SHA-256 を持ちます。別 Release 添付の ZIP は明示準備で取得し、`~/.cache/pixivdownloader-sdk/` の検証済みバイトを再利用します。キャッシュ を消しても選ぶバイトは同じで、欠落・ハッシュ 不一致は失敗します。

毎起動で専用 ランタイム コピー を作り ホスト と各公式 プラグイン を検証します。キャッシュ にアプリ状態はありません。`.dev/` は 設定、DB、ログ、ダウンロード、run コピー を持ち、ランタイム ZIP ハッシュ ごとに 設定/状態 を分離します。ホスト・公式 プラグイン 自動更新は無効で、通常のインストールデータとは別です。

終了後 `.dev/` を削除すると、そこに保存した ダウンロード も含めて開発データを初期化します。直接呼出しでは JVM `-Dpixivdownload.sdk.cache-dir=<directory>` で キャッシュ を選べますが、プロジェクト ランタイム は分離されたままです。offline には ランタイム 準備と ビルド 依存 キャッシュ の両方が必要です。Maven `-o`、Gradle `--offline` を使い、不足なら失敗します。SDK 更新では対応 マニフェスト を含む新パッケージへソースを移します。

import、コンパイル、完全起動は別の検証です。使う IDE で起動・ソース breakpoint・正常終了を確認してください。Maven 成功は IDE GUI の確認ではありません。Windows の packaged worker 起動で深い パス による エラー 267 が出た場合は短い場所へ移します。他 OS も各環境で検証が必要です。

Gradle/sbt は `runPlugin` / `debugPlugin` を使います。Gradle は `stopPlugin`、sbt は ビルド process を実行が占有するため、別 terminal の example ディレクトリ で `java -jar ../../tools/sdk-tools.jar stop .` を使います。

主 リポジトリ の公式 プラグイン 開発 モード は各 モジュール の現 `target/classes` をロードします。

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

Douyin は別 リポジトリ の SDK プロジェクト です。この モード は外部 プロジェクト を自動発見しません。公式開発には commit 済み IntelliJ、VS Code、Eclipse の `Developer Mode` 共有設定も使えます。

確認時は `/plugin-manage.html` の `STARTED` と generation、停止後の ルート 撤回と再開、現 成果物 の JS/CSS/i18n を調べます。`log/` に ルート 競合、重複 ID、コンテキスト、版、署名、drain エラーがないことも確認します。終了した非同期処理が ファイル 書込、ページ コールバック、旧 classloader 保持を続けてはいけません。

### アーティファクト形式

既定は ルート `plugin.properties` を持つ thin PF4J JAR です。`BOOT-INF/`、`lib/*.jar`、プラグイン-api/core-api/PF4J/Spring/Jackson/Servlet/ホスト クラス のコピーは含めません。

専用の外部依存が必要なら PF4J JAR-with-lib を使えます。ルート に 記述子・プラグイン クラス・リソース、`lib/*.jar` に専用依存を置きます。共有契約は shade/bundle しません。構造テストと分離 classloader の ロード テストを追加してください。公式の既定配布形式は ZIP ではなく `.jar` です。

## マーケット文書の提出

文書対応 SDK tools は candidate の固定 ソース commit にある `CHANGELOG.md` を読み、`## [v8.2.6] - 2031.3.2` のような level-2 heading で正確な プラグイン 版を探します。`### Features` / `### Bug Fixes` などの分類を保ちます。一致なし・重複時は別 ファイル、手入力、明示省略を選びます。README は リポジトリ、ローカル ファイル、複数行入力の UTF-8 Markdown/HTML を受け付けます。完全 CHANGELOG と release notes は別添付です。

選択した既定言語を使い、preview 確認後にバイトを固定します。相対画像は選択 ルート 内に限定し、外部画像の ダウンロード は明示確認します。リンク は用途 preset、言語別 ラベル、空状態を持つ編集可能一覧です。[コミュニティ手順](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README_en.md#submissions-and-version-management)で入力と復旧方法を確認できます。

版の任意 `content` は `readme`, `changelog`, `releaseNotes` の locale map です。document は `format`, `asset`, 任意 `sourcePath` / `sourceUrl`、画像 `resources` を記録します。リポジトリ 文書の sourceUrl は固定 commit に束縛して相対 リンク を解決します。ローカル ファイル、直接入力、フィールド のない旧記録では出所を推定しません。安全な HTML anchor と Markdown heading で内部移動し、外部 リンク は新 ウィンドウ です。asset は `name`, `url`, `mediaType`, `size`, `sha256`。market `links` 欠落は homepageUrl fallback、空配列は リンク なしを意味します。完全な契約は同 SDK の community Schema を参照します。

文書・画像・package を同じ バージョン Release に置き、署名 catalog が参照を束縛します。クライアント は必要時に サイズ/ハッシュ を検証し、sanitize した HTML を分離 static document に表示します。JAR 署名は文書を覆わず、審査参照と catalog が結び付けます。community tools の SDK pin 更新は公開バイト検証後に行い、ホスト にも文書対応が必要です。market metadata 追加だけで既存 requires を上げる必要はなく、SDK 1.1 ホスト は `requires=1.0` を受け付けます。

## 署名と公開

### 発行者の鍵とコミュニティ操作の署名

CLI は `pixivdownload-plugin-signature` にあり JDK 17 で動きます。ソース ルート で ビルド し、実際の生成 JAR パス に置き換えて呼びます。

```text
./mvnw -pl pixivdownload-plugin-signature package
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool <command>
```

- `keygen --directory <new-directory>` は PKCS#8 `private-key.pem` と SPKI `public-key.pem` を生成します。ディレクトリ/秘密鍵のアクセスは現 ユーザー に限定し、既存 ディレクトリ を置換しません。ソース リポジトリ・temporary ディレクトリ 外を選んで両方を backup します。
- `public-key --public-key <public-key.pem> --key-id <id> --out <public.json>` は canonical SPKI Base64 と SHA-256 指紋を出力します。対応 公開 ファイル を読み、秘密鍵から公開鍵を導出しません。
- `community-operation --operation <PUBLISHER_KEY_ROTATION|VERSION_STATUS_REQUEST|OWNERSHIP_TRANSFER> --canonical-body <canonical-body> --request-id <SHA-256> --key-id <id> --private-key <private-key.pem> --out <sig.json>` は固定 SDK の JCS body bytes を署名します。ダイジェスト は requestId と一致し、出力は秘密鍵を含まない detached 署名 のみです。

公開鍵出力と operation 署名は既存出力を上書きしません。package は `artifact` で署名し、引数は `--help` を確認します。秘密鍵を Git や提出添付に含めないでください。

### アーティファクト署名

以下の filename を実 パス に変え、quote は残します。Ed25519 PKCS#8 PEM 秘密鍵は リポジトリ と ビルド output の外に置きます。リポジトリ 設定は Base64 X.509 SubjectPublicKeyInfo 公開鍵を使います。

```powershell
java -cp "signature-tool.jar" `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  artifact `
  --artifact "plugin.jar" `
  --plugin-id example-download `
  --version 0.1.0 `
  --key-id example-2026 `
  --private-key "ed25519-pkcs8.pem" `
  --out "plugin.jar.sig"
```

出力 JSON は `formatVersion`, `algorithm=Ed25519`, `keyId`, `value` です。同じ 成果物 の正確な サイズ と SHA-256 も記録します。

```powershell
$artifact = Get-Item -LiteralPath "plugin.jar"
$artifact.Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.FullName).Hash.ToLowerInvariant()
```

### カタログマニフェスト

スキーマ 1 の top level は `schemaVersion`, `generatedTime`, `entries` です。構造例の ドメイン・サイズ・ハッシュ・署名・時刻 を同じ実 成果物 の値で置き換えます。

```json
{
  "schemaVersion": "1",
  "generatedTime": "2026-08-10T00:00:00Z",
  "entries": [
    {
      "pluginId": "example-download",
      "displayNamespace": "example-download",
      "displayNameKey": "plugin.name",
      "descriptionKey": "plugin.summary",
      "market": {
        "displayName": {"zh": "示例下载", "en": "Example download"},
        "summary": {"zh": "示例下载类型", "en": "Example download type"},
        "description": {"zh": "插件详细说明", "en": "Plugin description"},
        "author": "Example Developer",
        "sourceType": "community",
        "category": "download",
        "tags": ["download"],
        "homepageUrl": "https://example.com/plugin",
        "license": "MIT",
        "latestVersion": "0.1.0",
        "updatedTime": "2026-08-10T00:00:00Z",
        "iconToken": "download",
        "colorToken": "green",
        "recommended": false,
        "officialRequired": false,
        "defaultInstalled": false
      },
      "packages": [
        {
          "version": "0.1.0",
          "packageUrl": "https://plugins.example.com/example-download-0.1.0.jar",
          "expectedSizeBytes": 12345,
          "sha256": "LOWERCASE_SHA256_HEX",
          "signature": {
            "formatVersion": 1,
            "algorithm": "Ed25519",
            "keyId": "example-2026",
            "value": "BASE64_SIGNATURE"
          },
          "signatureUrl": "https://plugins.example.com/example-download-0.1.0.jar.sig",
          "requiredSdk": "1.3",
          "dependencies": [],
          "releasedTime": "2026-08-10T00:00:00Z",
          "changeNotes": ["Initial release"],
          "channel": "stable",
          "deprecated": false
        }
      ]
    }
  ]
}
```

`market` は表示・検索・sort 用で、インストール 安全性の判定には使いません。マニフェスト と packageUrl は HTTPS が必要です。インストール の基準は サイズ、SHA-256、structured 署名、内部 記述子 です。マニフェスト の生 bytes を署名します。

```powershell
java -cp "signature-tool.jar" `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  manifest `
  --manifest "manifest.json" `
  --repository-id example `
  --key-id example-2026 `
  --private-key "ed25519-pkcs8.pem" `
  --out "manifest.json.sig"
```

マニフェスト、同 URL に `.sig` を付けた署名、成果物、任意 成果物 署名 を公開します。署名後の JSON 整形や書換えは禁止です。検証 subcommand は次のとおりです。

```text
verify-manifest --manifest "manifest.json" --signature "manifest.json.sig" --repository-id <id> [--policy official|custom]
verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> --version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]
```

custom ルート は `--trusted-key-id` と `--trusted-public-key` も指定します。任意値は `--trusted-algorithm`, `--trusted-state`, `--trusted-publisher`, `--trusted-label`, `--trusted-official` です。

### カスタムリポジトリを登録できるようにする

64 KiB 以下の strict UTF-8 JSON `repository.json` を公開し、GUI 設定 → プラグインマーケット設定 → 追加 → リポジトリ記述子を使用、で公開 HTTPS URL を指定します。

```json
{
  "schemaVersion": 1,
  "repositoryId": "example.plugins",
  "displayName": "Example Plugins",
  "publisher": {
    "id": "example",
    "displayName": "Example Publisher",
    "homepageUrl": "https://example.com/plugins"
  },
  "catalog": {
    "protocol": "manifest-v1",
    "endpoint": "https://plugins.example.com/manifest.json"
  },
  "networkProfile": "DIRECT_STRICT",
  "revocationsUrl": "https://plugins.example.com/revocations.json",
  "updateProofUrl": "https://plugins.example.com/repository-update.json",
  "trustedKeys": [{
    "keyId": "example-2026",
    "algorithm": "Ed25519",
    "publicKeySpkiBase64": "BASE64_X509_SUBJECT_PUBLIC_KEY_INFO",
    "state": "ACTIVE",
    "publisher": "Example Publisher",
    "trustLabel": "Example release key"
  }]
}
```

`trustedKeys` には Ed25519 SPKI 公開鍵を 1 個以上 4 個以下指定し、各 `keyId` は一意にします。

最初の信頼に `repository.json.sig` を使いません。自分を紹介する新しい 鍵 の署名は自己宣言です。アプリは 記述子 ダイジェスト、発行者、全 network ホスト、完全な `SHA-256(SPKI DER)` 指紋を表示します。確認時に再取得して bytes の ダイジェスト 一致を要求し、保存設定は再起動後に有効です。custom リポジトリ は公式 ルート を継承しません。`official`, `configured`, `community` は予約 ID です。

`networkProfile` は `DIRECT_STRICT` / `GITHUB_RELEASES` のみです。前者は宣言された 公開 HTTPS ホスト だけ、redirect なし。後者は固定 GitHub 境界内の 1 redirect を許可します。記述子 から SSRF 検査の停止、専用 address、任意 proxy/redirect allowlist を指定できません。

小規模は `manifest-v1`、大規模は API base を エンドポイント にした `paged-v2` を使えます。

```text
GET {endpoint}/plugins?cursor=&limit=&query=&category=&publisher=&channel=
GET {endpoint}/plugins/{pluginId}?cursor=&limit=
GET {endpoint}/plugins/{pluginId}/versions/{version}
```

ページ サイズ は既定 24、最大 100。応答は `generation` と opaque cursor、一覧 512 KiB・詳細 256 KiB 制限です。インストール 時の入力は リポジトリ ID、プラグイン ID、バージョン のみです。ホスト が バージョン エンドポイント を再解決し、固定 package 記述子・サイズ・SHA-256・発行者署名と比較します。

初回信頼後の 記述子/鍵 変更は既存信頼鍵の単調 `repository-update-v1` proof、安全上の撤回は大きい sequence の `revocations-v1` を使います。各 JSON URL に `.sig` を付け、別の署名 ドメイン を使います。

```text
repository-update --document repository-update.json --repository-id example.plugins --sequence 2 --key-id example-2026 --private-key <pem> --out repository-update.json.sig
plugin-revocations --document revocations.json --repository-id example.plugins --sequence 1 --key-id example-2026 --private-key <pem> --out revocations.json.sig
```

撤回 範囲 は `PACKAGE_SHA256`, `PLUGIN_VERSION`, `SIGNING_KEY`, `PUBLISHER`。`YANKED` は新規 インストール/更新、`REVOKED` は次の ロード 前に該当する導入済み bytes も止めます。どの execution モード も現 OS アカウント を使い、署名や撤回はコードの安全性を保証しません。

## コントリビューションと公開前チェック

通常の外部 プラグイン は ホスト 変更を必要としません。API/ランタイム/テンプレート の実際の不具合、複数 consumer 用の中立 ポート、テンプレート・SDK 文書・boundary テスト・診断、公式 プラグイン、署名・インストール transaction・lifecycle・能力不在処理の改善を提案できます。

SDK が不足する場合は app-専用 クラス を使う前に、実 consumer、owner、寿命、エラー/absence、テスト を説明した中立契約を提案します。公開契約の変更は SDK 識別情報 を進め、BOM、Javadoc、テンプレート、Douyin、このガイドを揃えます。公開 candidate は上書きしません。

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/plugin-api/your-capability upstream/master
```

関連 モジュール、テンプレート、boundary テスト を実行し、ID・ルート・リソース・i18n を一致させます。`target/`, `build/`、ランタイム 設定、認証情報、秘密鍵、ダウンロード データ を commit しません。PR には問題、安定境界、失敗・不在動作と実行済み検証コマンドを書きます。コード、テンプレート、開発文書は PR で `master` へ提出します。オンライン文書は独立した `gh-pages` を専用 worktree から直接 commit/push する運用です。

### 公式リポジトリのバージョンと Release 検査

配布内容が変わる場合は公開版より `plugin.version` を上げます。公開前の同じ版には続けて変更できます。新 SDK 能力を使う場合は requires と プラグイン 版を上げますが、SDK 更新だけで全 プラグイン の増版は不要です。公式 PR gate は配布差分と公開版を照合し、各 Quality Gate は compiled type/member/コールバック override を最小 SDK と比較します。公開 rerun は ソース 版の判断を繰り返しません。reflection、inline constant、動作契約にはコードレビューが必要です。

プラグイン 公開は ソース SDK の公開を前提にしません。安定アプリ Release は新しい安定 SDK を公開できます。手動の安定 SDK 公開は `-f` が必要でも 不変 release を上書きできません。Beta アプリは公開安定 SDK を再利用できますが新 SDK を公開しません。手動 プラグイン 公開の `-f` は同版 asset の修復用で、アプリ Release は自動指定しません。

検査対象には ソース、リソース、記述子、専用 dependency、配布文書、共有 Maven 設定、参照 ビルド ファイル を含みます。テスト 専用変更は増版不要です。gate は増加だけを検査するため major/minor/patch の選択は契約レビューで判断します。公式 market の legacy-クライアント 集合は 2026-10-07 時点の安定 SDK `1.0.0`, `1.1.0` に固定され、`scripts/market-curation.json` の `_legacySdkVersions` で管理し自動拡大しません。channel と履歴保持は[プラグイン管理](/ja/plugin-management)を参照します。

### 公開前に確認すること

- [ ] provided SDK または有効な BOM を使用し、ホスト 提供 クラス を除外
- [ ] ルート 記述子 の ID・版・requires・クラス・execution モード と実装一致、feature は 1 個、子 Bean は明示組立
- [ ] コントローラー/ページ/static ディレクトリ の正しい AccessPolicy、i18n・エラー・status に機密値なし
- [ ] ファイル と成功事実 commit 後の完了、resolver による owner、opaque workKey
- [ ] 非同期 キュー/タスク/クライアント/executor/scheduler の実 quiesce/drain/close
- [ ] 自分の gallery/API/リソース/i18n と自身の `gallery.type-switch` のみ
- [ ] 設定/認証情報/パス/`PluginDatabase`/`PluginMainDatabase` の owner 境界、既存 `PluginDataSource` の SQLite 専用契約
- [ ] ビルド/フロントエンド 動作/JAR 構造テスト成功、サイズ/ハッシュ/署名/マニフェスト が同じ bytes を参照
- [ ] 秘密鍵が ソース/ビルド/ログ/package/公開 repository にない
