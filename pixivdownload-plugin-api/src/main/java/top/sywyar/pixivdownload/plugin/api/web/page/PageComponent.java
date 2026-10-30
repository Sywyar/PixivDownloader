package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 可信插件的组件声明；配置只含 JSON 纯值，Schema 使用 SDK record 生成器支持的子集。
 * 模块只接收公开实例上下文，不接收编辑引擎对象。公开渲染许可由贡献者声明，不能由页面数据覆盖。
 *
 * @param type 稳定的 owner 命名空间类型
 * @param version 配置版本
 * @param labelKey 名称翻译键
 * @param namespace 翻译命名空间
 * @param moduleUrl 正式和预览挂载模块的同源地址
 * @param propertiesModuleUrl 可选的属性编辑模块地址，空字符串表示通用 inspector
 * @param schema SDK 生成的 JSON Schema
 * @param defaults 默认 JSON 配置
 * @param publicRenderable 是否允许在公开页面发布配置与模块
 * @param inputs 可连接的输入契约
 * @param outputs 可连接的输出契约
 */
public record PageComponent(String type, int version, String labelKey, String namespace,
        String moduleUrl, String propertiesModuleUrl, String schema, String defaults,
        boolean publicRenderable, List<PagePort> inputs, List<PagePort> outputs) {
    /**
     * 复制端口列表，声明快照不再依赖贡献者的可变集合。
     * @param type 组件身份
     * @param version 配置版本
     * @param labelKey 名称翻译键
     * @param namespace 翻译命名空间
     * @param moduleUrl 挂载模块地址
     * @param propertiesModuleUrl 属性编辑模块地址
     * @param schema 生成的配置 Schema
     * @param defaults 默认配置
     * @param publicRenderable 公开渲染许可
     * @param inputs 输入端口
     * @param outputs 输出端口
     */
    public PageComponent {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }
}
