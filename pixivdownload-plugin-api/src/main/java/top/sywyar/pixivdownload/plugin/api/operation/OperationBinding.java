package top.sywyar.pixivdownload.plugin.api.operation;

/**
 * 待批准的 Hook 绑定；页面或插件不能自报 provider owner。
 * operation 与 endpoint 二选一，前置一律为必要 gate，后置一律为辅助观察。
 * order 只在同一阶段排序，同序按稳定绑定身份排序。
 */
public record OperationBinding(String id, String hook, String operation, String endpoint,
        OperationHook.Phase phase, int order, OperationHook.Completion completion, String configuration,
        java.util.List<Field> fields) {
    /** 固定经批准的字段列表；空列表表示不读取请求或响应正文。
     * @param id 绑定身份
     * @param hook 注册回调身份
     * @param operation 接口组身份，与 endpoint 二选一
     * @param endpoint 单接口身份
     * @param phase 回调时机
     * @param order 同阶段内顺序
     * @param completion HTTP 完成结果筛选
     * @param configuration 由定义 schema 校验的纯值 JSON
     * @param fields 独立批准的有界字段白名单
     */
    public OperationBinding {
        fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
    }
    /** 只接收接口和调用元数据的绑定。
     * @param id 绑定身份
     * @param hook 注册回调身份
     * @param operation 接口组身份，与 endpoint 二选一
     * @param endpoint 单接口身份
     * @param phase 回调时机
     * @param order 同阶段内顺序
     * @param completion HTTP 完成结果筛选
     * @param configuration 由定义 schema 校验的纯值 JSON
     */
    public OperationBinding(String id, String hook, String operation, String endpoint,
            OperationHook.Phase phase, int order, OperationHook.Completion completion, String configuration) {
        this(id, hook, operation, endpoint, phase, order, completion, configuration, java.util.List.of());
    }
    /** 单独批准的字段白名单；JSON 路径为 JSON Pointer，查询路径为参数名。只传纯量或纯量数组。 */
    public record Field(Source source, String path, String name) { }
    /** 正文访问须单独批准；不提供 Cookie、凭据或任意对象来源。 */
    public enum Source {
        /** URL 查询，不读取 Cookie 或任意请求头。 */ QUERY,
        /** 有界 JSON 请求正文。 */ JSON,
        /** 有界 JSON 响应；流式响应只提供完成元数据。 */ RESULT
    }
}
