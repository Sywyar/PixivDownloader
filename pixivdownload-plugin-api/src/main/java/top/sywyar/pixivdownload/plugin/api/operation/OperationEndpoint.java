package top.sywyar.pixivdownload.plugin.api.operation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 可选的 owner 操作分组；未标注的 MVC handler 仍自动进入接口目录并可直接绑定 Hook。
 * 路径、方法、身份及完成形态取自真实映射；此注解不执行方法织入或授予数据访问权限。
 * 同一分组也可通过宿主批准的接口配置建立，controller 不必依赖此类型。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OperationEndpoint {
    /** {@return owner 命名空间中的 operation 身份；多个 endpoint 可以共享} */
    String operation();
}
