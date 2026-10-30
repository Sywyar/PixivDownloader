# サードパーティプラグイン SDK

外部 PixivDownloader プラグインの開発、デバッグ、公開を行う開発者向けガイドです。アプリシェルや公式プラグインの実装クラスを抜き出すのではなく、公式テンプレートをコピーして始めてください。

- [サードパーティプラグインテンプレート](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info)
- [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)
- [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [サードパーティ Douyin プラグイン例](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)
- [プラグイン署名ツール](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)

Douyin はダウンロード、設定、プロキシ、キュー、スケジュール、永続化、プラグイン所有ギャラリーを含むサードパーティ SDK の実装例です。公開 SDK 契約だけに依存し、公式配布には含まれません。新規プロジェクトは `plugin-templates` から作成してください。

## 最初に信頼境界を理解する

外部プラグインはホストと同じ JVM で実行され、プロセスや OS によるセキュリティサンドボックスはありません。プロセスから読めるファイル、ネットワーク、リソースを利用できるため、他のインプロセスコードと同じリスクがあります。Ed25519 署名が証明するのは、信頼鍵から出たこととバイトが改変されていないことだけで、コードの安全性ではありません。

ホストは構造、サイズ、パス、バージョン、依存関係、SHA-256、署名、来歴を検証し、同じ凍結バイトをロードします。これはサプライチェーンの完全性を守るもので、コード隔離ではありません。Cookie、トークン、プロキシ、作品ディレクトリ、プラグインデータの正当な利用は作者の責任です。

## SDK の境界

SDK は `pixivdownload-sdk-info`、`pixivdownload-plugin-api`、`pixivdownload-core-api` で構成され、`pixivdownload-sdk-bom` がバージョンを揃えます。`sdk-info` が SDK の版、リビジョン、互換性規則の唯一の情報源です。

```text
第三者プラグイン
  ├─ pixivdownload-sdk-info    必須：SDK 版と互換性
  ├─ pixivdownload-plugin-api  必須：入口、貢献、パス、私有データ
  └─ pixivdownload-core-api    任意：安定したダウンロード / プロキシポート

依存禁止：pixivdownload-app、ホスト実装、plugin-runtime / installer / signature 内部、
公式プラグインの私有サービス・Mapper・Controller、ホスト DataSource、私有フロントエンドグローバル
```

プラグインは descriptor と contribution で機能を宣言します。ホストは信頼済みプラグイン ID、パッケージ ID、世代、公開単位で登録します。停止、アンロード、破損、非互換時には、そのプラグインのルート、静的資源、i18n、ナビゲーション、ダウンロード種別、キュー、スケジュール機能が撤回されます。利用側は capability がない場合も空白ページ、null 失敗、途中だけ完了したタスクを作らず、正常に劣化させます。

`PixivFeaturePlugin` の主な入口は `id`、表示情報、`start` / `stop`、`schema`、`routes`、`staticResources`、`i18n`、`navigation`、`startupRoutes`、`landings`、`pageSections`、`uiSlots`、`guiThemes`、`guiConfigContributions`、`guiOnboardingSteps`、`drilldowns`、`userscripts`、`scheduledSourceDescriptors`、`downloadTypes` です。未使用の入口は空リストのままにします。Spring Bean はこのメソッドから返さず、`PixivPluginProvider.configurationClasses()` で設定クラスを宣言し、プラグインごとの子 `ApplicationContext` を使います。

### 宣言的デスクトップ UI の境界

Swing と Compose が同じページを別々に保守する構成ではありません。アプリケーションシェルがツールキット非依存の完全な `DesktopUiDocument` を生成し、ページ構造、状態、設定保存、バックエンド連携、型付きイベント処理を所有します。`gui-swing` と `gui-compose` は同じ文書を描画する汎用 `DesktopUiProvider` であり、レンダラー、ウィンドウ、トレイ、テーマ、プラットフォーム連携だけを所有します。ページ ID、プラグイン ID、設定キー、i18n キーによる専用分岐を provider に追加しないでください。

機能プラグインは純データの `GuiConfigContribution` と field / group / section / layout / action / preset で、自分の設定 section の完全な業務構造だけを宣言します。ホストが信頼済み owner ごとに統合、検証、保存します。Swing / Compose コンポーネントやトップレベルウィンドウを返してはいけません。再利用可能な UI 要素が必要なら中立な `DesktopUiNode` 契約を拡張し、すべての provider で汎用実装します。

公式 `gui-swing` は既定でインストールされる既定 provider、`gui-compose` はオンデマンドです。どちらも `process-restart` のため、切り替え、インストール、更新、無効化、削除にはアプリケーション全体の再起動が必要です。Compose プラグインは Gradle Wrapper が Kotlin / Compose のコンパイルと JAR-with-lib 生成を実行し、Maven reactor がその成果物を通常の公式ビルド、署名、配布へ接続します。

## テンプレートから開始

SDK 開発パッケージには、単一のコンパイル依存関係 `pixivdownload-sdk`、独立した Maven プロジェクト、Gradle / sbt の例が含まれます。固定ランタイムのマニフェストを含むパッケージでは、付属の Run / Debug から現在のプラグインをビルドし、対応するホストと公式プラグイン一式を自動で準備できます。実行データは各プロジェクトの `.dev/` に保存されます。提供バージョンと使い方は、選択した [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) と同梱の README を確認してください。

| テンプレート | 用途 | 内容 |
| --- | --- | --- |
| `minimal-feature-plugin` | ページ、API、ナビゲーション、i18n、設定 | PF4J 入口、provider、feature、子コンテキスト、Controller、ルート / 静的 / i18n、テスト |
| `download-type-plugin` | 新しいダウンロード種別 | descriptor、5 つの取得モード、キュー、スケジュール、Vue UI slot、独立ギャラリー、テスト |

```powershell
mvn -f plugin-templates/pom.xml clean verify
mvn -f plugin-templates/pom.xml -pl minimal-feature-plugin -am verify
mvn -f plugin-templates/pom.xml -pl download-type-plugin -am verify
```

リポジトリ外へコピーした後は独立 Maven プロジェクトなので、テンプレートのディレクトリで `mvn clean verify` を実行します。SDK の開発版は次でインストールできます。

```powershell
./mvnw.cmd -pl pixivdownload-sdk-info,pixivdownload-plugin-api,pixivdownload-core-api,pixivdownload-sdk-bom -am install -DskipTests
```

SDK は `1.0.0` を初期契約とします。`plugin.requires` はアプリ版ではなく SDK の `major.minor` だけを宣言し、同じ major かつホスト minor が要求以上である必要があります。破壊的変更は MAJOR、互換追加は MINOR、互換修正は PATCH を上げます。PF4J、Spring、Jackson などホストが提供する依存も `provided` にし、共有クラスを JAR にコピーしません。

### コピー後にすべての ID を変更する

`example-download-plugin`（artifactId）、`example-download`（一意のプラグイン ID、キュー種別、URL、i18n 名前空間）、Java パッケージ、`ExampleDownload` 型名、`0.1.0`、`plugin.requires=1.0`、発行者名を一緒に変更します。ルート、静的パス、フロントエンド定数、私有テーブル名、テスト、両言語の i18n も変更してください。`plugin.properties` だけを変更すると公開 ID が不一致になります。

## パッケージと入口

JAR ルートの `plugin.properties` には次のような値を置きます。

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=1.0
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.lifecycle-policy=hot-reload
```

`plugin.id` は小文字 kebab-case のグローバル一意値で `PixivFeaturePlugin.id()` と一致させます。`plugin.class` は `PixivPluginProvider` を実装する PF4J メインクラスです。ライフサイクルは大文字小文字を区別する `hot-reload`、`backend-restart`、`process-restart` のいずれかです。

入口は明示的な子コンテキストで組み立てます。

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }
    @Override public List<Class<?>> configurationClasses() {
        return List.of(ExampleDownloadConfiguration.class);
    }
}
```

ホストのルートパッケージスキャンやプラグインパッケージ全体のスキャンに頼らず、設定クラスの `@Bean` で必要な Bean を明示します。アプリ実装クラスを子コンテキストへ注入しません。

## PostHog ブラウザークライアントの再利用

調査を公開する Web プラグインは公式 `posthog` に依存し、`/pixiv-posthog/pixiv-posthog.js` を自分のページスクリプトより先に読み込めます。調査 ID、質問スキーマ、トリガー、状態、文言、プライバシーフィルター、4 つの公開パラメーターは公開側プラグインが所有します。依存がない、パラメーター不正、SDK 失敗、競合時はクライアントがなくても画面を壊さず劣化させます。

`ownerKey` はグローバルに安定させ、同じページでパラメーター、匿名 ID、`beforeSend` が変わる場合は失敗を閉じます。再送は同じ安定 UUID を使います。API キーや Cookie、作品、ローカルパス、元のインストール ID を送信しません。

## 通知テンプレート

通知テンプレートはメール / プッシュ輸送ではなく、業務シナリオを所有するプラグインが提供します。子コンテキストから `NotificationTemplateContributor` Bean を出し、`scenarioId`、`medium`、`locale`、タイトル、本文を持つ値を返します。重複する `(scenarioId, medium, locale)` は失敗します。ホストは不変スナップショットにコピーし、停止・再読み込み・アンロード時にその公開単位を撤回します。

HTML は同じ JVM の制限付き文字列だけで渡し、タイトル 16 KiB、本文 1 MiB、1 公開あたり 256 テンプレート / 合計 8 MiB の上限を守ります。`InputStream`、`Path`、Spring Resource、ClassLoader、Bean、遅延 callback を契約に渡さないでください。信頼されない値を HTML プレースホルダーに入れる前にエスケープします。

## Web ルート、静的資源、i18n

Controller の mapping、静的ディレクトリ、トップレベル HTML は所有プラグイン自身の `routes()` に宣言します。未宣言の path + HTTP method は 404 であり、フロントエンドを隠すことは認証ではありません。

| ファクトリ | アクセス |
| --- | --- |
| `publicRoute` | 認証不要。ソロ / マルチ共通 |
| `visitor` | マルチの訪問者。ソロではセッションが必要、招待ゲスト不可 |
| `visitorAndInvitedGuest` | マルチ訪問者と招待ゲスト |
| `invitedGuest` | 管理者と招待ゲスト。監視規則付き |
| `admin` | 管理者のみ |
| `local` | ローカルプロセスの例外 |
| `gui` | 信頼されたローカル要求と GUI トークンの両方 |

独立管理ページの最小例：

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
        WebRouteContribution.admin("/example-download-gallery.html"),
        WebRouteContribution.admin("/example-download-gallery/**"),
        WebRouteContribution.admin("/api/example-download/gallery"));
}

@Override
public List<I18nContribution> i18n() {
    return List.of(new I18nContribution(
        "example-download", "i18n.web.example-download"));
}
```

異なるプラグインが同じパスを扱う場合、アクセスポリシーは一致しなければなりません。HTTP メソッドを制限する場合は明示的な `HttpMethod` 集合を使います。

## ダウンロード種別を追加する流れ

現在のソースにあるダウンロードタスク API では、プラグインが送信ハンドラー、オプションフック、イベントオブザーバーを登録できます。リクエストの重複排除、特定の実行のキャンセル、再接続時のスナップショットを提供し、SDK の基底クラスを継承する必要はありません。[ダウンロード種別テンプレート](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates/download-type-plugin)で使用例を確認できます。同じ契約を含む SDK とホストを使用してください。現在のソースにある API が古いリリースにも含まれるとは限りません。

`download-type-plugin` を基に、次の順に実装します。

1. `DownloadTypeDescriptor` で ID、表示名、保存形式、許可された取得モードを宣言
2. URL / ユーザー / 検索 / シリーズなどのフロントエンド行動モジュールを実装
3. バックエンドで要求所有者を解決し、他ユーザーのキューを操作できないようにする
4. `QueueOperations` を実装し、プレビュー、重複、フィルター、ダウンロードの状態を所有
5. ページ section と UI slot を必要な範囲だけ追加
6. `ScheduledSourceDescriptor` を純データとして宣言し、スケジュール実行を所有

ページ、静的資源、i18n、ルート、認証、ファイル保存をプラグイン境界の内側に置きます。欠落した capability をコアの特例で補わないでください。

## プラグイン所有ギャラリー

ギャラリーを追加する場合は、独自のページ、静的資源、ルート、検索・表示 API、i18n、ナビゲーションを貢献します。作品データとパスはプラグインの所有者単位で管理し、ホストの私有データベースや内部 Controller を直接利用しません。

## 設定、認証情報、ファイル

設定所有者は次の 3 つです。

1. ホスト：`config/config.yaml`
2. プラグイン：`config/plugins/{pluginId}.properties`
3. 認証情報：`config/credentials/{pluginId}.properties`

認証情報はホストが暗号化・注入します。プラグインは子コンテキストの `Environment` から復号済みの値だけを読みます。パスは `RuntimePathProvider` から取得し、作品は `download.root-folder` 下のプラグイン ID ディレクトリまたはプラグインが選んだ作品ディレクトリに保存します。ホストの秘密ファイルを直接走査しません。

## 外向き HTTP と WebSocket

安定した HTTP / WebSocket SDK または `core-api` のポートを使い、ホストの具体的な HTTP クライアントやプロキシ実装に依存しません。URL、Cookie、API キー、リダイレクト、サイズ上限、タイムアウト、ログへの秘匿情報を明示的に設計します。要求所有者とアクセス権をすべての書き込み API で検証します。

## 独立サービス契約と組み合わせ

以下の API には SDK 1.4.0 が必要です。`typed-service-plugin` テンプレートを使い、API、契約キャリア、提供者、利用者を別モジュールにします。業務バージョンは契約側で管理します。キャリアは `pixiv.contract-api` と `pixiv.contract-sha256` で元の API JAR を指定し、`process-restart` を使用します。提供者と利用者はキャリアへの依存を宣言し、API クラスを重複して同梱しません。

`PixivFeaturePlugin.serviceImports()` に利用する key、`serviceExports()` に実装 Bean を宣言し、child context に `PluginServices` を注入します。コンストラクターでは参照の保存や binding の登録ができますが、業務呼び出しには利用者の有効な publication が必要です。インターフェースと record/enum は同じ契約 classloader とコードの出所に属する必要があります。契約パッケージ間の依存は未対応です。境界 DTO は明示的に変換してください。

`find(key)` は不在時に空、`require(key)` は `UNAVAILABLE` を返します。複数実装がある場合は両方とも `AMBIGUOUS` です。`findAll(key)` は変更不能な `List<ServiceProvider<T>>` を返し、空集合も有効です。上限は 128 メンバーです。`service()` は利用者と提供者の正確な publication に固定されたプロキシ、`publication()` は信頼された識別情報です。公開順は業務上の優先順位ではありません。選択とソートは利用者が定義し、撤回されたプロキシは代替実装へ転送しません。

一意な依存には `bind(keys, activation)`、空集合を含む実装集合には `bindAll(keys, activation)` を使います。各回の `scope.require(key)` または `scope.findAll(key)` から取得し、`scope.acquire(...)` と `scope.guard(...)` でリソースと補助コールバックを管理します。メンバー変更時は旧回の受付を閉じ、実際の終了と清掃を待ってから新しい回を開始します。activation で業務コマンドを再実行してはいけません。`FAILED` と `CLOSE_FAILED` は明示的に `retry()` でき、清掃完了を示すのは `CLOSED` だけです。

単純な配信には独立した `EventSink.onEvent(record)` 契約を定義し、`findAll` を順番に呼び出して `ServiceAccessException` を契約に従って処理します。best effort は補助的な副作用に限ります。必須検証は書き込み前に完了し、不在や失敗時は書き込みを拒否します。集合 API は永続メッセージ、自動再試行、背圧、配信保証を提供しません。

長時間タスクは業務契約内の `start → jobId → query/cancel/result` という純値プロトコルで扱います。`PluginRuntimeTaskRegistrar` に仕事を登録し、`PluginResources` で実行器と資源を所有し、提供者自身がキャンセルに応答します。ハンドルのキャンセルは終了証明ではなく、宿主は実際の drain を待ちます。再起動後の復旧、checkpoint、冪等性はデータ owner の私有 DB で定義します。Future、スレッド、コールバックをサービス値として渡せません。

`binding.diagnostic()` は有界な純値の理由、状態、依存、publication を返します。管理画面のサービス関係と停止影響は同じ登録情報を使い、宣言された import と drain 中の binding を含みます。待機、曖昧、activation 失敗、実行器の拒否、清掃失敗を区別できます。`truncated` は表示の省略を意味し、依存がないという意味ではありません。診断の読み取りで業務の再実行や状態変更は起こりません。

## GUI フィールドに依存しない認証情報とログイン検証

`credentialKeys()` で `my-plugin.token` のような owner 接頭辞付きのキーを宣言し、`PluginCredentials` を注入します。`read(key)` は `Optional<String>`、`update(Map)` は原子的な差分更新です。省略したキーは保持し、空白値は削除します。`delete(key)` は冪等です。宣言・更新は最大 128 項目、値ごとに UTF-8 64 KiB、更新全体で 1 MiB です。未宣言キーと旧 generation のハンドルは拒否され、別 owner は指定できません。

認証情報のキーを通常の GUI フィールドとしても宣言することはできません。同じキーのフィールドは機密扱いが必要です。競合すると宿主はその owner の認証情報とデスクトップ設定のスナップショットを拒否し、検出済み機密キーのマスクは保持します。GUI の認証情報スナップショット、書き込み、失敗時の復元は一つの owner ロック内で実行します。読み込みと再読み込みでも、認証情報の取得と移行判断を同じロック内で行い、古いスナップショットや平文で成功済みのプログラム側の更新を上書きしません。認証情報を含む環境エクスポートは GUI のない宣言済みキーも含み、暗号化が必須です。認証情報を除外すると値は出力されません。復元時も owner とインストール世代を確認します。

暗号化とファイルロックは宿主の既存処理を使います。既存 GUI の機密フィールドも同じ宣言・マスク対象です。保存しても既存の `@Value` や設定オブジェクトは自動で再バインドされません。必要時に読み取るか、既存の再起動方針に従ってください。平文を DTO、例外、ログ、診断に含めないでください。

Spring Bean として `LoginVerification` を実装し、`policyId()` で安定したキーを返します。管理者が `setup.login-verification-policies` に必須ポリシーをカンマ区切りで設定し、バックエンド再起動後に有効になります。空値は無効、上限は 16 項目です。宿主はレート制限とパスワード照合の後、セッション作成の前に呼び出します。入力はユーザー名、クライアントアドレス、attemptId、不透明な proof で、パスワードやセッション権限を含みません。

結果は `APPROVED`、`REJECTED`、`CHALLENGE` です。必須ポリシーの不在・曖昧・例外は 503、拒否は 403、チャレンジは 409 と `verificationToken`、ポリシー別の `challenges` を返します。次の `/api/auth/login` には元の認証情報、token、`verificationProofs` を渡します。チャレンジはユーザー名、アドレス、正確な publication に固定され、有効期間は 5 分、応答は一度だけです。古いチャレンジは 409 です。チャレンジ/proof は各 UTF-8 16 KiB、合計 64 KiB、保留中は最大 256 件です。失敗時にセッション Cookie は作成しません。

画面が必要なら通常の `login.verification` UiSlot にモジュールを提供し、その静的リソースを公開ルートとして宣言します。宿主は `/api/web/login-verification-slots` でこの対象だけを公開します。属性は `{verificationToken, challenges}`、`proof` イベントの値は `{verificationToken, policyId, proof}` です。ユーザーが再度ログインを押して送信します。認証情報を編集すると proof は消去されます。型は SDK JAR 内の `META-INF/pixivdownload/web/login-verification.d.ts` にあります。画面の表示はバックエンド検証の代わりにならず、インストールだけで必須ポリシーが有効になることもありません。

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

プレリリース SDK は精密な契約を使い、properties では `plugin.requires==7.2.3-rc.4` と記述します（例）。安定版は `major.minor` です。コンパイル依存、記述子、実行パッケージを揃え、異なる RC 間の互換性を仮定しないでください。元の工程と `.dev/` を残し、新 SDK を別の場所に展開してツール、契約資源、実行マニフェストをまとめて更新します。プライベートデータの移行はプラグイン自身が管理し、未知の形式や失敗時は元データを保持して起動を止めます。

独立工程で `mvnw.cmd clean verify`（Windows）または `./mvnw clean verify` を実行し、同じ Wrapper の `verify exec:exec@sdk-run` で起動します。管理者画面で開始、停止、再開始、許可された reload を試し、ルート撤回、旧 publication の無効化、実タスクの drain とデータ保持を確認します。`process-restart` は全体を終了してから再起動してください。開発環境の停止は `exec:exec@sdk-stop` です。開発ディレクトリの full-trust 実行は正式 worker の検証を代替しません。

`DownloadObserver` は同期 best-effort 通知で永続再生を提供しません。`DownloadAdmissionPolicy` は副作用前に許可・拒否し、要求を書き換えません。どちらも宿主管理の任意 full-trust Bean です。`WorkFileImporter` は元ファイルへの読み取り専用参照を登録し、型 owner が不在なら成功を返しません。対象 Release の Javadoc で API の有無を確認してください。

投稿の問題は失敗した手順から調べます。候補がなければ既定ブランチの現在の CI と `tools/candidate-projects.json`、認証・push 失敗なら現在のアカウントと権限を確認します。ダイジェスト不一致では同じ配布物を再取得してください。事実・所有権の変更や応答喪失では元の要求を再照会し、投稿を自動反復しません。報告には ID、バージョン、ソース commit、SDK、エラーコードを含め、認証情報、秘密鍵、個人パスを除いてください。

## ビルド、テスト、デバッグ、インストール

```powershell
mvn clean verify
```

少なくとも descriptor / manifest、ルート認証、設定所有、状態分離、ダウンロード・キュー、スケジュール、静的資源、i18n、停止・再読み込み・アンロードのテストを書きます。外部プラグインは子コンテキストを明示的に作成し、開発モードまたは共有のデバッグ設定から起動します。成果物形式は互換性とライフサイクルを明記し、JAR / ZIP の内容と依存を検査します。

## 署名と公開

### 発行者の鍵とコミュニティ操作の署名

署名 CLI は `pixivdownload-plugin-signature` にあり、JDK 17 のみで実行できます。ソースリポジトリのルートでビルドし、生成された JAR から呼び出します。

```text
./mvnw -pl pixivdownload-plugin-signature package
java -cp <signature-tool.jar> top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool <command>
```

- `keygen --directory <new-directory>`：`private-key.pem`（PKCS#8）と対応する `public-key.pem`（SPKI）を生成します。ディレクトリと秘密鍵へのアクセスは現在のユーザーに限定されます。ソースリポジトリと一時ディレクトリの外を選び、両方のファイルをバックアップしてください。既存のディレクトリは上書きしません。
- `public-key --public-key <public-key.pem> --key-id <id> --out <public.json>`：正規化された SPKI Base64 と SHA-256 公開鍵フィンガープリントを出力します。対応する公開鍵ファイルを読み取り、秘密鍵から公開鍵を導出する処理は行いません。
- `community-operation --operation <PUBLISHER_KEY_ROTATION|VERSION_STATUS_REQUEST|OWNERSHIP_TRANSFER> --canonical-body <canonical-body> --request-id <SHA-256> --key-id <id> --private-key <private-key.pem> --out <sig.json>`：固定された SDK が生成した JCS 本文のバイト列に署名します。本文のダイジェストは requestId と一致する必要があります。出力には分離署名のみが含まれ、秘密鍵は含まれません。

公開鍵の出力とコミュニティ操作の署名では既存の出力ファイルを上書きできません。プラグインパッケージの署名には `artifact` を使います。引数の一覧は `--help` で確認できます。秘密鍵を Git にコミットしたり、提出ファイルに含めたりしないでください。

アーティファクトは署名ツールで Ed25519 署名を作成し、カタログには plugin ID、版、SDK 要件、サイズ、SHA-256、署名、ダウンロード URL、変更履歴を記載します。秘密鍵はリポジトリ、ログ、ビルド出力に置きません。

第三者リポジトリは、最大 64 KiB の厳格な UTF-8 JSON `repository.json` を公開します。必須情報は `schemaVersion: 1`、予約値ではない `repositoryId`、表示名、発行者、`catalog.protocol`（`manifest-v1` または `paged-v2`）、公開 HTTPS endpoint、`networkProfile`（`DIRECT_STRICT` または `GITHUB_RELEASES`）、1～4 個の Ed25519 SPKI 公開鍵です。任意で `revocationsUrl` と `updateProofUrl` を指定できます。初回取り込みでは `repository.json.sig` を使いません。利用者が発行者、全接続先ホスト、公開鍵の完全な `SHA-256(SPKI DER)` 指紋を確認し、確認時の再取得で descriptor のダイジェストが一致した場合だけ、再起動後に有効になります。

`paged-v2` は `{endpoint}/plugins`、`{endpoint}/plugins/{pluginId}`、`{endpoint}/plugins/{pluginId}/versions/{version}` を提供し、既定 24・最大 100 件、`generation` と不透明 cursor を使います。インストール時は版 endpoint を再取得し、凍結したパッケージのサイズ、SHA-256、署名、内部 descriptor と照合します。鍵の更新には旧信頼鍵で署名した単調 `repository-update-v1`、撤回には `revocations-v1` を使います。署名 CLI のコマンドは `repository-update` と `plugin-revocations` です。`YANKED` は新規インストール/更新を止め、`REVOKED` は既存バイトのロードも止めます。プラグインは同一 JVM で実行され、コードサンドボックスはありません。

## コントリビューションと公開前チェック

- 公開 API は `plugin-api` / `core-api` の契約として先に設計
- テンプレート、公式例、SDK 文書、両言語の i18n を更新
- plugin ID、パッケージ、ルート、権限、所有者が一致
- 署名、SHA-256、サイズ、依存、SDK `major.minor` を検証
- 無効化、停止、アンロード、破損、非互換時に capability がきれいに撤回される
- 認証情報、Cookie、ローカルパス、秘密鍵をログや成果物に含めない
- `mvn clean verify` と最小のインストール / リロード / 復旧テストを実行

## ギャラリーのナビゲーションスロット

プラグインは `NavigationContribution.placements` でリンク先を宣言します。`gallery.sidebar` はプラグイン、ダウンロード、ギャラリーなどの主要ページ、`gallery.sidebar.tools` は招待管理、統計、重複候補などのツール用です。小説ギャラリーでは `novel.sidebar` と `novel.sidebar.tools` を使います。各グループは独立して並び順とアクセス権を適用します。貢献を撤回するとリンクが消え、空のグループは非表示になります。ページは `data-nav-slot` を宣言し、プラグイン ID で分類しません。

管理者のダウンロードページは、`DownloadTasks` の実行スナップショットを既存のキューに統合します。種類モジュールは `process(item, context)` 内でバックエンドから実際の実行 UUID を受け取り、`context.bindTask(taskId)` を呼び出します。既存の行がその実行に関連付けられ、実行 ID でキャンセルできます。作品 ID を実行 ID の代わりに使わないでください。他の経路で登録されたタスクには「他の経路」と表示され、ページから再送信されません。終了した実行のスナップショットは 5 分後に失効しますが、保存済みのキュー記録は残ります。実行中かどうか確認できない項目は未確認のまま保持され、ページを再読み込みしても自動で再送信されません。
