# 設定リファレンス

PixivDownloader の設定は所有者ごとに分かれています。これらの保存先は互換ではありません。

| 設定 | パス | 所有者 |
| --- | --- | --- |
| ホスト設定とプラグイン有効状態 | `config/config.yaml` | アプリケーションシェル |
| プラグインの業務設定 | `config/plugins/{pluginId}.properties` | そのプラグイン |
| プラグインの認証情報 | `config/credentials/{pluginId}.properties` | そのプラグイン。ホストが暗号化 |

まず GUI の設定ページを使ってください。初回起動時は `config/config.yaml` が生成され、アップグレード時は既存値を上書きせず不足しているホストキーだけが追加されます。手動編集時は UTF-8 を使い、空の値をコメントアウトせず有効な `key: value` として残します。

## ホスト設定

### サービス、デバッグ、ダウンロード

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS サービスポート |
| `debug.enabled` | `false` | デバッグモード |
| `download.root-folder` | `pixiv-download` | ダウンロード作品のルート |
| `download.user-flat-folder` | `false` | 作者ディレクトリを省略する構成 |
| `download.max-concurrent` | `10` | 同時ダウンロード数。追加で最大 100 件をキューに保持し、それを超えると 429 |
| `database.maximum-pool-size` | `28` | SQLite 接続プールの上限 |

`download.root-folder` には作品だけが保存され、設定、データベース、プラグイン状態、キャッシュは別の場所に置かれます。小説、Douyin などの固有設定は各プラグインが所有します。

画像、表紙、埋め込み画像は 1 件 100 MiB、通常の作品 / 小説 1 タスク全体で 1 GiB の安全上限があります。Ugoira は ZIP 100 MiB、500 エントリ、展開後 1 件 32 MiB / 合計 200 MiB、圧縮率 100:1、500 フレーム、1 フレーム 25,000,000 ピクセルなどの上限があります。これらは設定で引き上げられません。

### プラグインマーケット

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | マーケットのマスター切り替え |
| `plugin-catalog.official-repository-enabled` | `true` | 組み込み公式リポジトリ |
| `plugin-catalog.connect-timeout-ms` | `15000` | 接続タイムアウト |
| `plugin-catalog.read-timeout-ms` | `60000` | 読み取りタイムアウト |
| `plugin-catalog.max-manifest-bytes` | `1048576` | マニフェストサイズ上限 |
| `plugin-catalog.max-package-bytes` | `104857600` | パッケージサイズ上限 |
| `plugin-catalog.repositories` | 空リスト | カスタムリポジトリ |

公式リポジトリ URL と信頼ルートは組み込まれています。カスタムリポジトリは HTTPS マニフェストと Ed25519 公開鍵を自分で宣言し、公式の信頼ルートを継承しません。手動例：

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

リポジトリ ID は一意で、`official` と `configured` は使えません。`direct-strict` は直接 HTTPS のみ、`proxy-trusted` はアプリプロキシと組み込み信頼ホストへの最大 5 回のリダイレクト、`custom` はエントリのフラグに従います。個別エントリではタイムアウトとサイズ上限を上書きできます。

### 外向きプロキシ

| キー | 既定値 |
| --- | --- |
| `proxy.enabled` | `true` |
| `proxy.host` | `127.0.0.1` |
| `proxy.port` | `7890` |

プロキシ対応が必要なプラグインは、安定した HTTP / WebSocket SDK または `core-api` のプロキシポートを使います。ホストの `ProxyConfig` 実装へ依存しないでください。

### マルチモードのクォータと制限

| キー | 既定値 |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

`multi-mode.post-download-mode` は `pack-and-delete`、`never-delete`、`timed-delete` のいずれかです。ゲストには `guest-invite.request-limit-minute=300`、`guest-invite.static-resource-request-limit-minute=1200`、`guest-invite.tts-request-limit-minute=30`、`setup.login-rate-limit-minute=10` が適用されます。

### メンテナンス時間

`maintenance.enabled` の既定値は `true` です。月曜は `maintenance.monday.enabled=true`、他の曜日は `false`、各曜日の時刻は既定で `10:00` です。

### HTTPS とリバースプロキシ

| キー | 既定値 |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | 空 |
| `server.ssl.certificate-private-key` | 空 |
| `server.ssl.key-store-type` | `JKS` |
| `server.trusted-proxy-cidrs` | 空 |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

PEM の証明書 / 秘密鍵、または JKS キーストアを指定します。秘密鍵とキーストアパスワードをコミットしないでください。

`server.trusted-proxy-cidrs` には、実際にバックエンドへ接続するプロキシ出口の IPv4 / IPv6 CIDR だけをカンマ区切りで記載します。

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

`0.0.0.0/0` や `::/0` を信頼しないでください。空の場合は直接モードとなり、転送ヘッダーを拒否します。信頼プロキシは毎回、`Forwarded` の `for` / `proto` / `host`、または `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host`（任意で Port）の完全な一組を送る必要があります。欠落、混在、形式不正、信頼されない送信元では HTTP 400 になります。

### 言語とデスクトップ UI

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `app.language` | 空 | システムに従うか、対応言語コードを指定 |
| `app.theme` | `system` | GUI テーマ ID |
| `app.config-menu-expand-all` | `false` | 初期状態で全グループを展開 |

テーマはインストール済みテーマプラグインから提供されます。

### 更新とスケジュールホスト

更新は `update.enabled=true`、`update.auto-check=true` が既定です。`update.manifest-url` と `update.nightly-manifest-url` でマニフェストを指定できます。

スケジュール関連の主な設定は `schedule.enabled=true`、`schedule.tick-interval-ms=60000`、`schedule.max-tasks=100`、`schedule.inbox-check-every=500`、`schedule.auth-failure-circuit-breaker=5`、`schedule.pending-max-attempts=5`、`schedule.overuse-defer-default-minutes=60` です。ダウンロード元や認証情報はプラグインが所有します。

### プラグインの有効状態

ホストは `plugins.{pluginId}.enabled` を所有します。

```yaml
plugins.douyin.enabled: true
```

必須プラグインは無効化できません。変更が即時反映されるかはプラグインのライフサイクルポリシーによります。詳しくは [プラグイン管理](/ja/plugin-management) を参照してください。

## プラグイン業務設定

各プラグインは UTF-8 の Java properties 形式で `config/plugins/{pluginId}.properties` だけを書き込みます。

```properties
example.timeout-ms=15000
example.output-format=json
```

ホストキー、`plugins.*.enabled`、認証情報らしいキーの上書きは拒否されます。プラグインは子 Spring コンテキストの `Environment`、`@Value`、`@ConfigurationProperties` から値を読み取り、ファイルを直接読まないでください。保存後、ホストは即時反映、バックエンド再起動、プロセス再起動のどれが必要かを示します。

## プラグイン認証情報

パスワード、Cookie、トークン、API キー、秘密、Webhook キーは `config/credentials/{pluginId}.properties` に置きます。暗号化、権限、移行、所有者単位の注入はホストが担当します。`config.yaml` や `config/plugins/*.properties` に認証情報を置かず、プラグインから認証情報ファイルを読み取り・復号しないでください。

暗号化バックアップを別環境で復元するには、元の認証情報マスターキーも必要です。

## 現在の契約を確認する

1. GUI の設定ページを開く
2. ホストの既定値は現在の `DefaultConfigTemplate` を確認する
3. プラグイン項目は `GuiConfigContribution`、`@ConfigurationProperties`、設定サービスを確認する
4. 旧来の `mail.*`、`push.*`、`notification.*`、`download.novel-*` を `config.yaml` にコピーしない
