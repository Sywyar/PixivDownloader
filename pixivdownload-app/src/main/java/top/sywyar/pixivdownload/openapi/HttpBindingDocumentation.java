package top.sywyar.pixivdownload.openapi;

import jakarta.validation.Valid;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.core.web.HttpParameterError;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** Response media and binding failures produced by MVC before it calls the controller. */
final class HttpBindingDocumentation {
    static final String BASIC = "#/components/schemas/" + ApiErrorResponse.Basic.class.getCanonicalName();

    static final String METHOD_ERROR = "HttpMethodNotAllowedError";
    static final String MEDIA_ERROR = "HttpUnsupportedMediaTypeError";

    static final String PARAMETER_ERROR = "HttpParameterBindingError";
    private static final String BODY_ERROR = "HttpUnreadableBodyError";
    private static final String MULTIPART_ERROR = "HttpMultipartError";
    private static final String UPLOAD_SIZE_ERROR = "HttpUploadSizeError";
    private static final ClassValue<ExceptionHandlerMethodResolver> LOCAL_ERRORS = new ClassValue<>() {
        @Override protected ExceptionHandlerMethodResolver computeValue(Class<?> controller) {
            return new ExceptionHandlerMethodResolver(controller);
        }
    };

    static void transportSchemas(Components components, Map<String, String> text) {
        Map.of(METHOD_ERROR, "http.status.405", MEDIA_ERROR, "http.status.415",
                BODY_ERROR, "error.request.body.invalid",
                MULTIPART_ERROR, "error.upload.invalid", UPLOAD_SIZE_ERROR, "error.upload.too-large").forEach((name, code) -> fixedError(components, name, code));
        var location = new ObjectSchema()
                .addProperty("in", new StringSchema()._enum(List.of("path", "query", "header", "part", "body")).description(text.get("binding.in")))
                .addProperty("field", new StringSchema().description(text.get("binding.field")))
                .addProperty("reason", new StringSchema().description(text.get("binding.reason")));
        components.addSchemas("HttpInputLocation", location);
        var located = new ComposedSchema().addAllOfItem(new Schema<>().$ref(BASIC))
                .addAllOfItem(new Schema<>().$ref("#/components/schemas/HttpInputLocation"));
        located.setRequired(List.of("in", "field", "reason"));
        String model = HttpParameterError.class.getCanonicalName();
        components.addSchemas(model, located);
        var inputError = new Schema<>().$ref("#/components/schemas/" + model);
        components.addSchemas(PARAMETER_ERROR, withCodes(inputError, GlobalExceptionHandler.INVALID_PARAMETER));
        components.addSchemas("HttpValidationFieldError", withCodes(inputError, "error.request.validation"));
        components.addSchemas("HttpBodyTypeError", withCodes(inputError, "error.request.body.invalid"));
    }

    static void fixedError(Components components, String name, String code) {
        components.addSchemas(name, withCodes(new Schema<>().$ref(BASIC), code));
    }

    static Schema<?> withCodes(Schema<?> base, String... codes) {
        return new ComposedSchema().addAllOfItem(base)
                .addAllOfItem(new ObjectSchema().addProperty("code", new StringSchema()._enum(List.of(codes))));
    }

    static void describe(Operation operation, HandlerMethod handler, Function<String, String> text) {
        if (operation.getResponses() == null) return;
        for (var response : operation.getResponses().values()) {
            var content = response.getContent();
            if (content == null || !content.containsKey(MediaType.ALL_VALUE)) continue;
            // Springdoc adds a wildcard inferred from the return type alongside explicit binary formats.
            if (content.size() > 1) {
                content.remove(MediaType.ALL_VALUE);
            } else {
                var result = ResolvableType.forMethodReturnType(handler.getMethod());
                if (HttpEntity.class.isAssignableFrom(result.toClass())) result = result.as(HttpEntity.class).getGeneric(0);
                Class<?> wireType = result.resolve(Object.class);
                if (wireType != Object.class && wireType != byte[].class && !Resource.class.isAssignableFrom(wireType)) {
                    String media = CharSequence.class.isAssignableFrom(wireType) ? MediaType.TEXT_PLAIN_VALUE
                            : MediaType.APPLICATION_JSON_VALUE;
                    content.addMediaType(media, content.remove(MediaType.ALL_VALUE));
                }
            }
        }
        var missing = operation.getResponses().get("404");
        if (missing != null && (missing.getContent() == null || missing.getContent().isEmpty())
                && operation.getResponses().entrySet().stream().filter(entry -> entry.getKey().startsWith("2"))
                        .map(entry -> entry.getValue().getContent())
                        .allMatch(content -> content == null || content.isEmpty() || content.containsKey(MediaType.APPLICATION_JSON_VALUE))) {
            addError(operation, "404", BASIC, null);
        }
        boolean body = false, validatedBody = false, convertedBody = false, requiredQuery = false, requiredHeader = false;
        boolean multipart = false, requiredPart = false, converted = false;
        for (var parameter : handler.getMethodParameters()) {
            var query = parameter.getParameterAnnotation(RequestParam.class);
            if (parameter.hasParameterAnnotation(top.sywyar.pixivdownload.core.web.CsvIds.class) && query != null
                    && operation.getParameters() != null) {
                String name = query.name().isEmpty() ? parameter.getParameterName() : query.name();
                operation.getParameters().stream().filter(p -> "query".equals(p.getIn()) && p.getName().equals(name)).findFirst()
                        .ifPresent(p -> p.style(io.swagger.v3.oas.models.parameters.Parameter.StyleEnum.FORM).explode(false)
                                .schema(new io.swagger.v3.oas.models.media.ArraySchema().items(new io.swagger.v3.oas.models.media.IntegerSchema().format("int64").minimum(java.math.BigDecimal.ONE))));
            }
            var header = parameter.getParameterAnnotation(RequestHeader.class);
            var type = ClassUtils.resolvePrimitiveIfNecessary(parameter.nestedIfOptional().getNestedParameterType());
            boolean requestBody = parameter.hasParameterAnnotation(RequestBody.class);
            body |= requestBody;
            validatedBody |= requestBody && parameter.hasParameterAnnotation(Valid.class);
            convertedBody |= requestBody && type != String.class && type != byte[].class && !Resource.class.isAssignableFrom(type);
            requiredPart |= MultipartFile.class.isAssignableFrom(type) && query != null && query.required() && !parameter.isOptional();
            multipart |= MultipartFile.class.isAssignableFrom(type);
            requiredQuery |= !MultipartFile.class.isAssignableFrom(type) && !parameter.isOptional() && query != null && query.required() && ValueConstants.DEFAULT_NONE.equals(query.defaultValue());
            requiredHeader |= !parameter.isOptional() && header != null && header.required() && ValueConstants.DEFAULT_NONE.equals(header.defaultValue());
            boolean named = query != null || header != null || parameter.hasParameterAnnotation(PathVariable.class);
            converted |= parameter.hasParameterAnnotation(top.sywyar.pixivdownload.core.web.CsvIds.class) || named && (Number.class.isAssignableFrom(type) || type == Boolean.class
                    || type == UUID.class || type.isEnum());
        }
        var methods = handler.getMethodAnnotation(RequestMapping.class);
        var controller = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequestMapping.class);
        String[] produces = methods != null && methods.produces().length > 0 ? methods.produces()
                : controller == null ? new String[0] : controller.produces();
        // A JSON body is also negotiated by the message converters when the mapping omits produces.
        boolean negotiatedJson = operation.getResponses().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("2"))
                .map(entry -> entry.getValue().getContent())
                .anyMatch(content -> content != null && content.containsKey(MediaType.APPLICATION_JSON_VALUE));
        if (negotiatedJson || produces.length > 0 && java.util.Arrays.stream(produces).noneMatch(MediaType.ALL_VALUE::equals))
            operation.getResponses().putIfAbsent("406", new ApiResponse().description(text.apply("response.406")).content(new Content()));
        if (methods != null && methods.method().length > 0) {
            addError(operation, "405", "#/components/schemas/" + METHOD_ERROR, text.apply("response.405"));
            operation.getResponses().get("405").addHeaderObject("Allow", new Header()
                    .schema(new StringSchema()));
        }
        String[] consumes = methods != null && methods.consumes().length > 0 ? methods.consumes()
                : controller == null ? new String[0] : controller.consumes();
        if (convertedBody || consumes.length > 0 && java.util.Arrays.stream(consumes).noneMatch(MediaType.ALL_VALUE::equals))
            addError(operation, "415", "#/components/schemas/" + MEDIA_ERROR, text.apply("response.415"));
        var local = LOCAL_ERRORS.get(handler.getBeanType());
        if (local.resolveMethodByExceptionType(HttpParameterException.class) == null && operation.getParameters() != null
                && operation.getParameters().stream().anyMatch(p -> "header".equals(p.getIn())
                        && AcquisitionCredentialResolver.HEADER_NAME.equalsIgnoreCase(p.getName())))
            addError(operation, "400", "#/components/schemas/" + PARAMETER_ERROR, null);
        if (body) bindingError(operation, "400", BODY_ERROR, text.apply("binding.body"),
                local.resolveMethodByExceptionType(HttpMessageNotReadableException.class) != null);
        if (convertedBody && local.resolveMethodByExceptionType(HttpMessageNotReadableException.class) == null)
            addError(operation, "400", "#/components/schemas/HttpBodyTypeError", null);
        if (validatedBody && local.resolveMethodByExceptionType(MethodArgumentNotValidException.class) == null)
            addError(operation, "400", "#/components/schemas/HttpValidationFieldError", null);
        if (requiredPart) addError(operation, "400", "#/components/schemas/" + PARAMETER_ERROR, text.apply("binding.part"));
        if (requiredQuery || requiredHeader) bindingError(operation, "400", PARAMETER_ERROR, text.apply("binding.required"),
                (!requiredQuery || local.resolveMethodByExceptionType(MissingServletRequestParameterException.class) != null)
                        && (!requiredHeader || local.resolveMethodByExceptionType(MissingRequestHeaderException.class) != null));
        if (multipart) {
            bindingError(operation, "400", MULTIPART_ERROR, text.apply("binding.multipart"),
                local.resolveMethodByExceptionType(MultipartException.class) != null);
            bindingError(operation, "413", UPLOAD_SIZE_ERROR, text.apply("binding.uploadSize"),
                local.resolveMethodByExceptionType(MaxUploadSizeExceededException.class) != null);
        }
        if (converted) {
            boolean localMismatch = local.resolveMethodByExceptionType(MethodArgumentTypeMismatchException.class) != null;
            bindingError(operation, "400", PARAMETER_ERROR, text.apply("binding.conversion"), localMismatch);
            if (localMismatch || local.resolveMethodByExceptionType(IllegalArgumentException.class) != null) {
                // Local handlers retain their business code; location is present on the binding failure branch.
                var json = operation.getResponses().get("400").getContent().get(MediaType.APPLICATION_JSON_VALUE);
                json.setSchema(new ComposedSchema().addAllOfItem(json.getSchema())
                        .addAllOfItem(new Schema<>().$ref("#/components/schemas/HttpInputLocation")));
            }
        }
    }

    static void describeFileRanges(Operation operation, Function<String, String> text) {
        if (operation.getParameters() == null || operation.getParameters().stream()
                .noneMatch(p -> "header".equals(p.getIn()) && "Range".equalsIgnoreCase(p.getName()))) return;
        var full = operation.getResponses().get("200");
        if (full == null || full.getContent() == null || full.getContent().values().stream()
                .noneMatch(media -> media.getSchema() != null && "binary".equals(media.getSchema().getFormat()))) return;
        var supported = new Header().schema(new StringSchema()._enum(List.of("bytes")));
        full.addHeaderObject("Accept-Ranges", supported);
        var content = new Content();
        content.putAll(full.getContent());
        content.addMediaType("multipart/byteranges", new io.swagger.v3.oas.models.media.MediaType()
                .schema(new StringSchema().format("binary")));
        operation.getResponses().addApiResponse("206", new ApiResponse().description(text.apply("response.206"))
                .content(content).addHeaderObject("Accept-Ranges", supported)
                .addHeaderObject("Content-Range", new Header().description(text.apply("header.contentRange.single")).schema(new StringSchema())));
        operation.getResponses().addApiResponse("416", new ApiResponse().description(text.apply("response.416"))
                .addHeaderObject("Accept-Ranges", supported).addHeaderObject("Content-Range", new Header().schema(new StringSchema())));
        var unchanged = new ApiResponse().description(text.apply("response.304"));
        for (var parameter : operation.getParameters()) {
            if (!"header".equals(parameter.getIn())) continue;
            String header;
            if ("If-Modified-Since".equalsIgnoreCase(parameter.getName())) header = "Last-Modified";
            else if ("If-None-Match".equalsIgnoreCase(parameter.getName())) header = "ETag";
            else continue;
            var validator = new Header().schema(new StringSchema());
            full.addHeaderObject(header, validator);
            operation.getResponses().get("206").addHeaderObject(header, validator);
            operation.getResponses().get("416").addHeaderObject(header, validator);
            unchanged.addHeaderObject(header, validator);
        }
        if (unchanged.getHeaders() != null) operation.getResponses().addApiResponse("304", unchanged);
    }

    private static void bindingError(Operation operation, String status, String schema, String detail, boolean local) {
        var response = operation.getResponses().get(status);
        var content = response == null ? null : response.getContent();
        var json = content == null ? null : content.get(MediaType.APPLICATION_JSON_VALUE);
        if (json == null || json.getSchema() == null) {
            // Business failures can share this status with MVC, including in contributed routes.
            addError(operation, status, BASIC, null);
        }
        if (local) {
            var current = operation.getResponses().get(status);
            var description = current.getDescription();
            current.setDescription(description == null || description.isBlank() ? detail : description + " " + detail);
        } else {
            addError(operation, status, "#/components/schemas/" + schema, detail);
        }
    }

    static void addError(Operation operation, String status, String reference, String detail) {
        var response = operation.getResponses().computeIfAbsent(status, key -> new ApiResponse());
        if (detail != null && !detail.isBlank()) {
            var previous = response.getDescription();
            response.setDescription(previous == null || previous.isBlank() ? detail : previous + " " + detail);
        }
        if (response.getContent() == null) response.setContent(new Content());
        var media = response.getContent().computeIfAbsent(MediaType.APPLICATION_JSON_VALUE,
                key -> new io.swagger.v3.oas.models.media.MediaType());
        Schema<?> schema = media.getSchema();
        if (schema == null) media.setSchema(new Schema<>().$ref(reference));
        else if (!reference.equals(schema.get$ref())) {
            if (schema.getAnyOf() != null) {
                if (schema.getAnyOf().stream().noneMatch(s -> reference.equals(s.get$ref())))
                    schema.addAnyOfItem(new Schema<>().$ref(reference));
            } else {
                media.setSchema(new ComposedSchema().addAnyOfItem(schema).addAnyOfItem(new Schema<>().$ref(reference)));
            }
        }
    }

    private HttpBindingDocumentation() {}
}
