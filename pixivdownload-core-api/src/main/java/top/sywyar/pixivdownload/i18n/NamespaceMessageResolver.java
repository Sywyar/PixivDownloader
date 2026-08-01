package top.sywyar.pixivdownload.i18n;

import java.util.Locale;
import java.util.Optional;

/**
 * 按贡献方命名空间解析本地化文案的宿主端口。
 *
 * <p>调用方只提交稳定的 namespace、key 与目标语言；活动贡献快照的选择、资源物化和
 * fallback 策略由宿主实现负责。namespace 或 key 不可用时返回空结果。
 */
@FunctionalInterface
public interface NamespaceMessageResolver {

    Optional<String> resolve(String namespace, Locale locale, String key);
}
