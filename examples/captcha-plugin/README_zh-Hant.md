# 驗證碼外掛的獨立 SDK 建置

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

此工程直接編譯倉庫中 `pixivdownload-plugin-captcha` 的原始碼與測試，僅依賴公開 SDK 1.4 與驗證碼函式庫，不繼承宿主 Maven parent。一個外掛提供 Turnstile 線上驗證、ALTCHA SHA-256 與 PBKDF2 離線驗證元件。

需要完整原始碼倉庫、JDK 17、Maven、Node.js/npm，以及可解析的精確 SDK 依賴。在此目錄執行：

```text
mvn package
```

建置會安裝鎖定的 npm 依賴並打包官方 ALTCHA Worker 與授權文件。產物為 `target/captcha-sdk-consumer-1.0.0.jar`，外掛 ID 為 `captcha`；它與官方模組屬於同一外掛，不能同時安裝兩份。首次啟用自動產生並加密儲存離線簽章金鑰。安裝、表單綁定與 Turnstile 設定見[頁面編輯器指南](https://sywyar.github.io/PixivDownloader/#/zh-hant/page-editor)。
