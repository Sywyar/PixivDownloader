package top.sywyar.pixivdownload.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import top.sywyar.pixivdownload.common.InvalidPage;
import top.sywyar.pixivdownload.core.web.HttpParameterError;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;

/** Keeps the binding location when MVC selects a controller's handler for a nested conversion exception. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpParameterBinding implements HandlerExceptionResolver {
    private static final String ATTRIBUTE = HttpParameterBinding.class.getName();

    private record Location(String in, String field, String reason) {}

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception failure) {
        if (failure instanceof MethodArgumentTypeMismatchException mismatch) {
            request.setAttribute(ATTRIBUTE, location(mismatch));
        } else if (failure instanceof InvalidPage page) {
            request.setAttribute(ATTRIBUTE, new Location("query", page.field(), "out_of_range"));
        } else {
            request.removeAttribute(ATTRIBUTE);
        }
        return null;
    }

    public static HttpParameterError mismatch(MethodArgumentTypeMismatchException failure, String code,
                                              MessageResolver messages, Locale locale) {
        var location = location(failure);
        return error(code, location.in(), location.field(), "type", messages, locale);
    }

    private static Location location(MethodArgumentTypeMismatchException failure) {
        var parameter = failure.getParameter();
        String in = parameter.hasParameterAnnotation(PathVariable.class) ? "path"
                : parameter.hasParameterAnnotation(RequestHeader.class) ? "header" : "query";
        return new Location(in, failure.getName(), "type");
    }

    public static HttpParameterError error(String code, String in, String field, String reason,
                                           MessageResolver messages, Locale locale) {
        String key = switch (reason) {
            case "required" -> "error.request.field.required";
            case "type" -> "error.request.field.type";
            default -> "error.request.field.invalid";
        };
        return new HttpParameterError(code, messages.get(locale, key, field), in, field, reason);
    }

    public static Object withLocation(ApiErrorResponse body, HttpServletRequest request, MessageResolver messages, Locale locale) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof Location location)) return body;
        var details = error(body.code(), location.in(), location.field(), location.reason(), messages, locale);
        if (body instanceof ApiErrorResponse.Basic) return details;
        if (!(JsonMapper.shared().valueToTree(body) instanceof ObjectNode json) || json.hasNonNull("field")) return body;
        return json.put("in", details.in()).put("field", details.field()).put("reason", details.reason()).put("error", details.error());
    }
}
