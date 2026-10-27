# 独立契约服务示例

[English](README_en.md)

此工程需要 JDK 17、Maven 和 PixivDownloader SDK 1.4。三个插件通过独立的文本统计契约协作，宿主无需引用该业务接口。

| 模块 | 产物与用途 |
| --- | --- |
| api | 仅依赖 JDK 的接口和 record；可单独作为 Maven API 发布 |
| contract | 安装包，携带原始 API JAR 及其 SHA-256 |
| provider | 显式导出 `textMetrics` Bean |
| consumer | 声明导入服务，通过管理员 HTTP 入口调用 |

在本目录运行：

```sh
mvn package
```

使用 SDK 的签名工具给 `contract`、`provider`、`consumer` 各自的 `target/*.jar` 签名。通过正常插件安装入口安装并确认信任；不要把 `api` JAR 当作插件安装，也不要把 API 类打入提供者或消费者。

先安装 `text-metrics-contract`，完整重启宿主，再安装提供者和消费者。消费者不要求提供者已经存在；缺席时业务入口返回 HTTP 503 和 `UNAVAILABLE`。契约包是两者的 required 运行依赖。契约安装、更新和删除在完整进程重启后生效；提供者和消费者可按正常生命周期停启。

登录管理员账号后，在同源页面的开发者控制台调用：

```js
fetch('/api/text-metrics', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({texts: ['abc', '你好']})
}).then(response => response.json()).then(console.log);
// {lengths: [3, 2], total: 5}
```

长度按 Java 字符串的 UTF-16 单元计算。该入口受管理员身份和同源写入检查保护。

服务键的 `contractId` 是契约插件 id；接口类型区分同一契约内的不同服务。只会导出声明的本地 Bean。构造器和 `PostConstruct` 可以保存 `PluginServices`，首次业务调用时再解析服务。多提供者返回 `AMBIGUOUS`；旧代理不会转接新提供者，任一方停止后都应重新解析。调用取消不代表在途方法已退出，插件仍须等待排空。

消费者重启后使用新注入的 `PluginServices`；旧访问器也已失效。

新通道仅支持同步纯值。服务接口必须非 sealed，接口、DTO 必须来自同一原始 API JAR；record 使用默认 canonical 构造器。完整类型和资源边界见 SDK 的 `PluginServices` Javadoc。异步任务、流和资源句柄继续使用专用契约。

失败响应包含稳定的 `code` 和按请求语言解析的 `error`。调用方按 `code` 判断原因，例如 `UNAVAILABLE`；不要把说明文案作为判断条件。

同一契约 id 不并存多个活动版本。安装检查拒绝重复契约类、split package、多版本 API JAR，以及共享契约类空间中的隐式 JAR `Class-Path` 和索引。提供者与消费者以 `provided` 依赖编译；这只是构建声明，运行时仍会检查真实类来源。
