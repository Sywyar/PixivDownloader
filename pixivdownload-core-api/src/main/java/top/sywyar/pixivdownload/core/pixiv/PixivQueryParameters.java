package top.sywyar.pixivdownload.core.pixiv;

import top.sywyar.pixivdownload.core.web.HttpParameterError;
import top.sywyar.pixivdownload.i18n.MessageResolver;

/** Pixiv 搜索和账号查询共用的协议值，同时用于参数校验与 HTTP 目录。 */
public final class PixivQueryParameters {
    private PixivQueryParameters() {}

    /** 搜索结果排序方式。 */
    public enum Order {
        /** 按日期从新到旧。 */
        date_d,
        /** 按日期从旧到新。 */
        date,
        /** 按人气降序。 */
        popular_d
    }
    /** 搜索结果的内容分级。 */
    public enum Mode {
        /** 检索所有分级。 */
        all,
        /** 仅检索全年龄作品。 */
        safe,
        /** 仅检索成人作品。 */
        r18
    }
    /** 匹配关键词的范围。 */
    public enum SearchMode {
        /** 匹配作品标签。 */
        s_tag,
        /** 匹配标题和说明。 */
        s_tc
    }
    /** 账号查询的公开范围。 */
    public enum Rest {
        /** 查询公开条目。 */
        show,
        /** 查询非公开条目。 */
        hide
    }

    /**
     * 校验 Pixiv 搜索关键词、排序、分级及匹配方式。
     *
     * @param messages 错误文案解析器
     * @param word 搜索关键词
     * @param order 排序方式
     * @param mode 内容分级
     * @param sMode 匹配范围
     * @return 首个参数错误，校验通过时为 {@code null}
     */
    public static HttpParameterError search(MessageResolver messages, String word, String order, String mode, String sMode) {
        if (word == null || word.isBlank())
            return error(messages, "word", "required", "error.request.param.invalid");
        if (!allows(Order.class, order))
            return error(messages, "order", "invalid_value", "pixiv.proxy.search.order.invalid", order);
        if (!allows(Mode.class, mode))
            return error(messages, "mode", "invalid_value", "pixiv.proxy.search.mode.invalid", mode);
        if (!allows(SearchMode.class, sMode))
            return error(messages, "sMode", "invalid_value", "pixiv.proxy.search.s-mode.invalid", sMode);
        return null;
    }

    /**
     * 检查起止页码是否均为正整数。
     *
     * @param messages 错误文案解析器
     * @param startPage 起始页码
     * @param endPage 结束页码
     * @return 首个参数错误，校验通过时为 {@code null}
     */
    public static HttpParameterError range(MessageResolver messages, int startPage, int endPage) {
        if (startPage > 0 && endPage > 0) return null;
        return error(messages, startPage <= 0 ? "startPage" : "endPage", "out_of_range", "pixiv.proxy.search-range.invalid");
    }

    /**
     * 校验账号查询使用的公开范围。
     *
     * @param messages 错误文案解析器
     * @param rest 待校验的公开范围
     * @return 首个参数错误，校验通过时为 {@code null}
     */
    public static HttpParameterError rest(MessageResolver messages, String rest) {
        return allows(Rest.class, rest) ? null
                : error(messages, "rest", "invalid_value", "pixiv.proxy.me.rest.invalid", rest);
    }

    private static <E extends Enum<E>> boolean allows(Class<E> values, String token) {
        for (E option : values.getEnumConstants()) {
            if (option.name().equals(token)) return true;
        }
        return false;
    }

    private static HttpParameterError error(MessageResolver messages, String field, String reason, String message, Object... args) {
        return new HttpParameterError("error.request.param.invalid", messages.get(message, args), "query", field, reason);
    }
}
