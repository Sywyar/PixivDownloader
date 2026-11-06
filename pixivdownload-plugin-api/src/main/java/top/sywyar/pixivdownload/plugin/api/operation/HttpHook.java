package top.sywyar.pixivdownload.plugin.api.operation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

/**
 * 插件受管 Bean 上的 HTTP 回调与待批准绑定。目标从真实 MVC 目录解析，注解本身不授予权限。
 * 方法必须是单例 Bean 的 public 同步实例方法，唯一参数为 {@link OperationHook.Invocation}；前置返回
 * {@link OperationHook.Decision}，后置还可以返回 void。回调沿用同一受管租约和故障语义。
 * 普通方法只接收元数据；复杂数据与交互合同可以通过在 OperationHook 实现的 invoke 方法上声明本注解提供。
 * 不支持通过异步方法代理提前返回；返回时应已完成本次回调工作。
 * 一个声明解析为一个 handler，其多个路由别名共享绑定。已批准的必要前置要求不会因插件缺席而消失；
 * 后置为可跳过的辅助观察。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HttpHook {
    /** {@return 稳定回调身份，不随方法改名隐式改变} */
    String id();
    /** {@return 目标接口的 owner；提供者身份始终由宿主确定} */
    String targetOwner();
    /** {@return 目标 HTTP 方法} */
    HttpMethod method();
    /** {@return MVC 路由模板，不包含真实路径参数或查询字符串} */
    String path();
    /** {@return 前置检查或请求完成后的观察阶段} */
    OperationHook.Phase phase();
    /** {@return 同阶段排序，不能越过宿主安全检查} */
    int order() default 0;
    /** {@return 后置结果筛选；前置必须为 ALL} */
    OperationHook.Completion completion() default OperationHook.Completion.ALL;
    /** {@return 规则修订；纯元数据回调的验证代际也使用此值} */
    String revision() default "1";
    /** {@return 精确参数匹配声明；留空时不以参数筛选候选，歧义仍拒绝} */
    String[] params() default {};
    /** {@return 精确请求头匹配声明；留空时不以请求头筛选候选} */
    String[] headers() default {};
    /** {@return 精确 consumes 声明；留空时不以此筛选候选} */
    String[] consumes() default {};
    /** {@return 精确 produces 声明；留空时不以此筛选候选} */
    String[] produces() default {};
    /** {@return 经回调配置 schema 验证的纯值 JSON} */
    String configuration() default "{}";
    /** {@return 需要单独批准的字段；普通元数据方法应保持为空} */
    Field[] fields() default {};

    /** 字段仅传有界纯值，仍受宿主敏感字段与类型检查约束。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Field {
        /** {@return 查询、JSON 输入或 JSON 结果} */
        OperationBinding.Source source();
        /** {@return 参数名或 JSON Pointer} */
        String path();
        /** {@return 回调输入或结果中的字段别名} */
        String name();
    }
}
