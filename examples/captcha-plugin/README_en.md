# Independent SDK build of the CAPTCHA plugin

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

This project compiles the source and tests in `pixivdownload-plugin-captcha`, using only public SDK 1.4 and CAPTCHA libraries without inheriting the host Maven parent. One plugin provides Turnstile and reCAPTCHA v2 online verification, plus ALTCHA SHA-256, PBKDF2, Hutool character entry and Tianai slider puzzles for offline verification.

Use a complete source checkout, JDK 17, Maven, Node.js/npm and resolvable exact SDK dependencies. Run in this directory:

```text
mvn package
```

The build installs locked npm dependencies and packages official ALTCHA workers and their license. The artifact is `target/captcha-sdk-consumer-1.0.0.jar`, with plugin ID `captcha`. It is the same plugin as the official module; install only one build. First activation generates and encrypts the local signing key. See the [CAPTCHA plugin guide](https://sywyar.github.io/PixivDownloader/#/en/captcha) for installation, form binding and configuration of all six components.
