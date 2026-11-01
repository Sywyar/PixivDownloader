# 独立 CAPTCHA プラグイン

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

一つのプラグインで Turnstile のオンライン検証と ALTCHA SHA-256/PBKDF2 のオフライン検証を提供します。SDK 1.4 の操作検証とページ関連付けを使用します。

JDK 17、Maven、Node.js/npm と、解決可能な正確な SDK 依存が必要です。このディレクトリで実行します。

```text
npm ci
mvn package
```

`target/captcha-example-0.1.0.jar` をインストールし、[ページエディターガイド](https://sywyar.github.io/PixivDownloader/#/ja/page-editor)に従って認証情報を設定し、フォームに関連付けて公開します。公式 ALTCHA Worker とライセンスはビルド時に同梱されます。公式テスト設定は既定で無効です。本番サイトには自分の Turnstile 認証情報を使用してください。
