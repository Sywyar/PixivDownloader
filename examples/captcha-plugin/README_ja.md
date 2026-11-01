# CAPTCHA プラグインの独立 SDK ビルド

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

この工程は `pixivdownload-plugin-captcha` のソースとテストを、公開 SDK 1.4 と CAPTCHA ライブラリだけでコンパイルします。ホストの Maven parent は継承しません。1 つのプラグインで Turnstile のオンライン認証と ALTCHA SHA-256、PBKDF2 のオフライン認証を提供します。

完全なソースチェックアウト、JDK 17、Maven、Node.js/npm、指定バージョンの SDK 依存が必要です。このディレクトリで実行します：

```text
mvn package
```

ビルドはロック済み npm 依存を取得し、公式 ALTCHA Worker とライセンスを同梱します。成果物は `target/captcha-sdk-consumer-1.0.0.jar`、プラグイン ID は `captcha` です。公式モジュールと同じプラグインのため、どちらか一方だけをインストールしてください。初回有効化時にローカル署名キーを生成して暗号化保存します。インストール、フォーム関連付け、Turnstile 設定は[ページエディターガイド](https://sywyar.github.io/PixivDownloader/#/ja/page-editor)を参照してください。
