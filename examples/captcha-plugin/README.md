# 独立验证码插件

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

一个插件提供 Turnstile 在线验证、ALTCHA SHA-256 与 PBKDF2 离线验证组件，使用 SDK 1.4 的通用操作验证和页面绑定契约。

需要 JDK 17、Maven、Node.js/npm，以及可解析的精确 SDK 依赖。在此目录执行：

```text
npm ci
mvn package
```

安装 `target/captcha-example-0.1.0.jar`，按[页面编辑器指南](https://sywyar.github.io/PixivDownloader/#/zh-cn/page-editor)配置凭据、绑定表单并发布。构建会打包官方 ALTCHA Worker 与许可证。默认不启用官方测试配置，真实站点须使用自己的 Turnstile 凭据。
