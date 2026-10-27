package top.sywyar.pixivdownload.plugin.api.service;

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * 独立契约包中的同步纯值服务。contractId 是 required 依赖的契约包 id，不是提供者 id。
 * 接口及 DTO 由该契约包唯一加载；不能把它们复制到消费或提供服务的插件中。
 * 服务接口必须非 sealed，运行时在准备阶段拒绝无法生成调用代理的接口。
 *
 * @param contractId 契约包 id
 * @param type 公共服务接口
 * @param <T> 服务类型
 */
public record ServiceKey<T>(String contractId, Class<T> type) {
    /**
     * 创建服务键。
     * @param contractId 至多 128 字符的小写短横线契约包 id
     * @param type 公共接口
     */
    public ServiceKey {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(type, "type");
        if (contractId.length() > 128 || !contractId.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")
                || !type.isInterface()
                || !Modifier.isPublic(type.getModifiers())) {
            throw new IllegalArgumentException("SERVICE_KEY_INVALID");
        }
    }
}
