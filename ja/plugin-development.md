# サードパーティプラグイン SDK

外部 PixivDownloader プラグインの開発、デバッグ、公開を行う開発者向けガイドです。アプリシェルや公式プラグインの実装クラスを抜き出すのではなく、公式テンプレートをコピーして始めてください。

- [サードパーティプラグインテンプレート](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info)
- [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)
- [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [公式 Douyin 例](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-douyin)
- [プラグイン署名ツール](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)

Douyin はダウンロード、設定、プロキシ、キュー、スケジュール、永続化、プラグイン所有ギャラリーを含む完全な公式例です。新規プロジェクトは `plugin-templates` から作成してください。

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

## テンプレートから開始

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

## ビルド、テスト、デバッグ、インストール

```powershell
mvn clean verify
```

少なくとも descriptor / manifest、ルート認証、設定所有、状態分離、ダウンロード・キュー、スケジュール、静的資源、i18n、停止・再読み込み・アンロードのテストを書きます。外部プラグインは子コンテキストを明示的に作成し、開発モードまたは共有のデバッグ設定から起動します。成果物形式は互換性とライフサイクルを明記し、JAR / ZIP の内容と依存を検査します。

## 署名と公開

アーティファクトは署名ツールで Ed25519 署名を作成し、カタログマニフェストには plugin ID、版、SDK 要件、サイズ、SHA-256、署名、ダウンロード URL、変更履歴を記載します。秘密鍵はリポジトリ、ログ、ビルド出力に置きません。利用者がカスタムリポジトリを追加する場合は、そのリポジトリ自身の HTTPS マニフェストと公開鍵を信頼設定に登録します。

## コントリビューションと公開前チェック

- 公開 API は `plugin-api` / `core-api` の契約として先に設計
- テンプレート、公式例、SDK 文書、両言語の i18n を更新
- plugin ID、パッケージ、ルート、権限、所有者が一致
- 署名、SHA-256、サイズ、依存、SDK `major.minor` を検証
- 無効化、停止、アンロード、破損、非互換時に capability がきれいに撤回される
- 認証情報、Cookie、ローカルパス、秘密鍵をログや成果物に含めない
- `mvn clean verify` と最小のインストール / リロード / 復旧テストを実行
