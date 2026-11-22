package top.sywyar.pixivdownload.core.pixiv;

import top.sywyar.pixivdownload.core.web.HttpParameterError;
import top.sywyar.pixivdownload.i18n.MessageResolver;

/** Pixiv 搜索和账号查询共用的协议值，同时用于参数校验与 HTTP 目录。 */
public final class PixivQueryParameters {
    private PixivQueryParameters() {}

    public enum Order { date_d, date, popular_d }
    public enum Mode { all, safe, r18 }
    public enum SearchMode { s_tag, s_tc }
    public enum Rest { show, hide }

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

    public static HttpParameterError range(MessageResolver messages, int startPage, int endPage) {
        if (startPage > 0 && endPage > 0) return null;
        return error(messages, startPage <= 0 ? "startPage" : "endPage", "out_of_range", "pixiv.proxy.search-range.invalid");
    }

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
