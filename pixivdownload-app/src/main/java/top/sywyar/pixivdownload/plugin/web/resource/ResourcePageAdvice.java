package top.sywyar.pixivdownload.plugin.web.resource;

import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.io.UncheckedIOException;

/** 控制器直接返回的 HTML 资源也经过共享转换，保留控制器原有的缓存与鉴权约束。 */
@ControllerAdvice
final class ResourcePageAdvice implements ResponseBodyAdvice<Resource> {
    private final StaticResourceVersioning versioning;

    ResourcePageAdvice(StaticResourceVersioning versioning) {
        this.versioning = versioning;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return ResourceHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Resource beforeBodyWrite(Resource body, MethodParameter returnType, MediaType contentType,
                                    Class<? extends HttpMessageConverter<?>> converterType,
                                    ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !MediaType.TEXT_HTML.isCompatibleWith(contentType)
                || response.getHeaders().getContentDisposition().isAttachment()) return body;
        try {
            Resource transformed = versioning.transformPage(((ServletServerHttpRequest) request).getServletRequest(), body);
            if (transformed != body) {
                response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
                response.getHeaders().remove(HttpHeaders.LAST_MODIFIED);
                response.getHeaders().setETag('"' + StaticResourceVersioning.contentHash(transformed) + '"');
            }
            return transformed;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
