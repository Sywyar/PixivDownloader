# 獨立驗證碼外掛

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

一個外掛提供 Turnstile 線上驗證、ALTCHA SHA-256 與 PBKDF2 離線驗證元件，使用 SDK 1.4 的通用操作驗證與頁面綁定契約。

需要 JDK 17、Maven、Node.js/npm，以及可解析的精確 SDK 依賴。在此目錄執行：

```text
npm ci
mvn package
```

安裝 `target/captcha-example-0.1.0.jar`，依[頁面編輯器指南](https://sywyar.github.io/PixivDownloader/#/zh-hant/page-editor)設定憑證、綁定表單並發布。建置會打包官方 ALTCHA Worker 與授權文件。官方測試設定預設不啟用，正式網站須使用自己的 Turnstile 憑證。
