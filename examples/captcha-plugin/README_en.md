# Independent CAPTCHA plugin

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

One plugin provides Turnstile online verification and ALTCHA SHA-256/PBKDF2 offline components through SDK 1.4 operation verification and page binding.

Use JDK 17, Maven, Node.js/npm and a resolvable exact SDK dependency. Run here:

```text
npm ci
mvn package
```

Install `target/captcha-example-0.1.0.jar`, then follow the [page editor guide](https://sywyar.github.io/PixivDownloader/#/en/page-editor) to configure credentials, bind a form and publish. The build packages official ALTCHA workers and their license. Official test configuration is disabled by default; production sites need their own Turnstile credentials.
