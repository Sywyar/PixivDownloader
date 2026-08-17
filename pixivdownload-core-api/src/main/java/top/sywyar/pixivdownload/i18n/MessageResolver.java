package top.sywyar.pixivdownload.i18n;

import java.util.Locale;

/**
 * 与可选插件共享的最小消息查询契约。
 */
public interface MessageResolver {

    /**
     * 返回当前执行上下文关联的语言区域。
     *
     * @return 方法返回的 {@code Locale} 实例
     */
    default Locale currentLocale() {
        return Locale.getDefault();
    }

    /**
     * 将显式请求的语言区域规范化为解析器支持的语言区域。
     *
     * @param locale 语言区域
     * @return 方法返回的 {@code Locale} 实例
     */
    default Locale normalizeLocale(Locale locale) {
        return locale == null ? currentLocale() : locale;
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param code 代码
     * @param args 参数列表
     * @return 方法返回的字符串
     */
    String get(String code, Object... args);

    /**
     * 执行对应操作并返回结果。
     *
     * @param locale 语言区域
     * @param code 代码
     * @param args 参数列表
     * @return 方法返回的字符串
     */
    String get(Locale locale, String code, Object... args);

    /**
     * 返回对应值。
     *
     * @param code 代码
     * @param defaultMessage 默认值消息
     * @param args 参数列表
     * @return 方法返回的字符串
     */
    String getOrDefault(String code, String defaultMessage, Object... args);

    /**
     * 返回对应值。
     *
     * @param locale 语言区域
     * @param code 代码
     * @param defaultMessage 默认值消息
     * @param args 参数列表
     * @return 方法返回的字符串
     */
    String getOrDefault(Locale locale, String code, String defaultMessage, Object... args);

    /**
     * 返回对应值。
     *
     * @param code 代码
     * @param args 参数列表
     * @return 方法返回的字符串
     */
    String getForLog(String code, Object... args);
}
