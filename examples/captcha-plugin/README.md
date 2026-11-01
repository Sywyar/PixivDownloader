# 验证码插件的独立 SDK 构建

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

此工程直接编译仓库中 `pixivdownload-plugin-captcha` 的源码和测试，仅依赖公开 SDK 1.4 与验证码库，不继承宿主 Maven parent。一个插件提供 Turnstile、reCAPTCHA v2 在线验证，以及 ALTCHA SHA-256、PBKDF2、Hutool 字符输入和 Tianai 滑块拼图四种离线组件。

需要完整源码仓库、JDK 17、Maven、Node.js/npm，以及可解析的精确 SDK 依赖。在此目录执行：

```text
mvn package
```

构建会安装锁定的 npm 依赖并打包官方 ALTCHA Worker 与许可证。产物为 `target/captcha-sdk-consumer-1.0.0.jar`，插件 ID 为 `captcha`；它与官方模块属于同一插件，不能同时安装两份。首次启用自动生成并加密保存离线签名密钥。六种组件的安装、绑定和配置见[验证码插件指南](https://sywyar.github.io/PixivDownloader/#/zh-cn/captcha)。
