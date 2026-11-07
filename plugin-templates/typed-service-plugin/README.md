# 独立契约服务示例（`typed-service-plugin`）

[English](README_en.md)

本多模块示例演示如何使用 PixivDownloader SDK 2.x 定义跨插件类型化服务契约、生成 JSON Schema 与管理员 HTTP 远程调用客户端，而无需在宿主中硬编码业务接口。

| 模块 | 职责 |
| --- | --- |
| `api` | 纯 JDK 运行时接口与 record（标注 `@ValueSchema`、`@ValueProperty`、`@RemoteService`、`@RemoteOperation`），产出 Schema 与客户端资源 |
| `contract` | 携带原始 `api` JAR 及精确 SHA-256 的契约载体插件（`process-restart`，作为提供者与消费者的必选依赖） |
| `provider` | 实现并显式导出 `textMetrics` 服务 Bean |
| `consumer` | 导入服务，注册生成的管理员 HTTP 适配器与前端客户端模块 |

## 1. 构建与安装

需 JDK 25+、Maven 与匹配的 SDK 开发包 `sdk-tools.jar`：

```sh
mvn verify "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"
```

构建自动在各模块 `target/` 生成 JSON Schema、JS 客户端、`.d.mts` 类型声明与 Java HTTP 适配器（请勿手改生成物；也可用 `java -jar sdk-tools.jar schema-check <schema.json> <data.json>` 单独校验样本）。

对 `contract`、`provider`、`consumer` 三个插件 JAR 签名后，**先安装 `contract` 并完整重启宿主**，再安装 `provider` 与 `consumer`（原始 `api` JAR 仅由 `contract` 承载，不单独安装，也不打入 `provider` / `consumer`）。

## 2. 浏览器远程调用与动态服务绑定

以管理员登录宿主后，在同源页面控制台调用生成的客户端：

```js
const {analyzeTexts} = await import('/text-metrics-client/text-metrics.mjs');
await analyzeTexts({texts: ['abc', '你好']});
// {lengths: [3, 2], total: 5}
```

- **远程调用约束**：当前支持 `ADMIN` 策略的精确 POST 路由；请求与响应均在边界按同一契约校验（单次上限 1 MiB、16,384 节点、32 层深度，整数须在 Java 类型与 JS 安全整数 `±9,007,199,254,740,991` 交集内）。错误返回稳定 `RemoteError.code`（`REMOTE_UNAVAILABLE`、`REMOTE_CONTRACT_MISMATCH`、`REMOTE_VALUE_INVALID`、`REMOTE_FAILED`、`REMOTE_CANCELLED`、`REMOTE_TRANSPORT`），客户端不自动重试。
- **生命周期感知绑定**：需要随提供者启停自动建立或释放订阅时，使用 `PluginServices.bind`：

```java
ServiceKey<TextMetrics> key = new ServiceKey<>("text-metrics-contract", TextMetrics.class);
ServiceBinding binding = services.bind(List.of(key), scope -> {
    TextMetrics current = scope.require(key);
    scope.acquire(() -> eventSource.subscribe(
            scope.guard(() -> refresh(current))));
});
```

当消费者与全部依赖均存在唯一活动实例时才进入 `ACTIVE`；依赖撤回时阻止新调用、等待排空并释放资源后再建立新轮次（状态可通过 `binding.state()` 查询，失败时可调用 `retry()`）。无需跟随外部依赖的本地资源使用 `PluginResources.openScope()`。完整契约说明见 SDK Javadoc。
