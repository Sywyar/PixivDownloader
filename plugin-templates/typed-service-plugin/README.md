# 独立契约服务示例

[English](README_en.md)

需要 JDK 17、Maven、PixivDownloader SDK 1.4，以及实现其服务、资源和远程操作接口的宿主。三个插件共享文本统计契约，宿主不引用该业务接口。

| 模块 | 用途 |
| --- | --- |
| api | 运行时仅依赖 JDK 的接口与 record，携带生成的契约产物 |
| contract | 携带原始 API JAR 及其 SHA-256 的安装包 |
| provider | 显式导出本地 `textMetrics` Bean |
| consumer | 导入服务，注册生成的管理员 HTTP 适配器与客户端静态资源 |

在本目录构建，工具路径取自 SDK 开发包：

```sh
mvn verify "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"
```

构建从 `@ValueSchema`、`@ValueProperty` 与显式的 `@RemoteService` / `@RemoteOperation` 生成 JSON Schema、操作描述、JS 客户端、类型声明和 Java HTTP 适配器。生成文件位于各模块的 `target/`；修改契约后重新构建，不手改产物。完成标记覆盖整组输出，关闭生成、生成失败或缺少契约时拒绝打包，避免非 clean 构建混入旧文件。纯 Schema 样本可用 `java -jar sdk-tools.jar schema-check <schema.json> <data.json>` 校验。

给 contract、provider、consumer 的 JAR 签名，通过正常安装入口确认信任。先安装 contract 并完整重启宿主，再安装另外两个插件。消费者允许业务 provider 缺席，契约包则是 required 依赖。API JAR 不单独安装，不复制进参与者的包；双方使用 provided 编译依赖。契约安装、更新、删除需完整进程重启，同一契约 id 不并存多个活动版本。

登录管理员账号后，在同源页面的控制台运行：

```js
const {analyzeTexts} = await import('/text-metrics-client/text-metrics.mjs');
await analyzeTexts({texts: ['abc', '你好']});
// {lengths: [3, 2], total: 5}
```

该模块实际导入生成客户端，并通过 JSDoc 引用生成类型。服务端读取同一契约，在调用前校验请求，在返回前校验响应。生成适配器提供路由声明，宿主检查 controller 与声明是否一致。内部服务导入、导出本身不会公开 HTTP。远程操作目前支持精确 POST 路径和 ADMIN 策略，保留登录、同源写入和当前用户身份检查。publication owner 不是用户身份。

统计结果按 Java UTF-16 单元计算，Schema 长度约束按 Unicode 码点计算。远程整数须同时符合 Java 类型范围与 ±9,007,199,254,740,991；long 必须显式限定安全范围。未知字段、非法枚举、超范围值及孤立代理字符会被拒绝。可缺失字段必须可空，缺失解码为 null。自定义构造器、访问器或序列化注解不属于生成子集。请求与响应最多 1 MiB，值遍历最多 16,384 节点、32 层。

生成客户端不自动重试。服务缺席或失效返回 `REMOTE_UNAVAILABLE`，契约身份不符返回 `REMOTE_CONTRACT_MISMATCH`，非法输入返回 `REMOTE_VALUE_INVALID`，提供方失败返回 `REMOTE_FAILED`。服务端响应含稳定 `code` 与本地化 `error`；客户端按 `RemoteError.code` 判断。`AbortSignal` 取消返回 `REMOTE_CANCELLED`，传输失败返回 `REMOTE_TRANSPORT`，包括读取响应体期间发生的失败。取消 fetch 不证明服务端已退出，宿主仍等待真实 drain。手写 `/api/text-metrics` 保留为直接调用服务的对照示例。

需要随依赖恢复重建订阅时，在初始化中登记绑定：

```java
ServiceKey<TextMetrics> key = new ServiceKey<>("text-metrics-contract", TextMetrics.class);
ServiceBinding binding = services.bind(List.of(key), scope -> {
    TextMetrics current = scope.require(key);
    scope.acquire(() -> eventSource.subscribe(
            scope.guard(() -> refresh(current))));
});
```

这里 `eventSource.subscribe` 返回可关闭句柄，`refresh` 只处理辅助事件。业务命令与持久任务不放入激活回调。自身 publication 与所有依赖均活动且唯一时才激活；撤回后禁止旧回调及代理进入，再排空、释放资源，最后建立新一轮。旧代理不会改连 replacement。

用 `binding.state()` 查询状态：等待依赖为 WAITING，多 provider 为 AMBIGUOUS；正常经过 ACTIVATING、ACTIVE，撤回后为 DRAINING。激活失败且回滚完成为 FAILED，关闭失败为 CLOSE_FAILED；可用 `retry()` 重试资源建立或清理。永久 `close()` 返回不代表排空，只有 CLOSED 表示清理完成。消费者停止后不会因 provider 恢复而复活。激活、受管回调及 disposer 中同步修改生命周期会被拒绝，避免自等待。

无需依赖驱动的本地资源继续使用 `PluginResources.openScope()`。服务仍只传递同步纯值；异步任务、流和资源句柄使用专用契约。安装检查保留重复类、split package、隐式类路径和原始 API 字节校验。完整约束见 SDK Javadoc。
