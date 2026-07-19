package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;

/** 为宿主及外置插件 controller 注入当前请求对应的纯值作品可见性作用域。 */
@Component
@RequiredArgsConstructor
public class GuestWorkVisibilityScopeArgumentResolver implements HandlerMethodArgumentResolver {

    private final GuestWorkVisibilityScopeFactory scopeFactory;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == WorkVisibilityScope.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return scopeFactory.fromRequest(request);
    }
}
