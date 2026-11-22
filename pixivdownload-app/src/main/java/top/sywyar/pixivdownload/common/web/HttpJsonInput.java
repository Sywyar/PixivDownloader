package top.sywyar.pixivdownload.common.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import org.springframework.validation.FieldError;
import tools.jackson.core.JsonPointer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

public final class HttpJsonInput {
    private HttpJsonInput() {}

    public static JsonMapper.Builder strictStrings(JsonMapper.Builder builder) {
        return builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }

    public static String reason(JacksonException failure) {
        return failure instanceof tools.jackson.databind.exc.UnrecognizedPropertyException ? "unknown_property" : "type";
    }

    public static String pointer(JacksonException failure) {
        var path = new StringBuilder();
        for (var reference : failure.getPath()) {
            if (reference.getPropertyName() != null)
                path.append("/").append(reference.getPropertyName().replace("~", "~0").replace("/", "~1"));
            else if (reference.getIndex() >= 0) path.append("/").append(reference.getIndex());
        }
        return path.isEmpty() ? null : path.toString();
    }

    public static String pointer(FieldError field) {
        if (!field.contains(ConstraintViolation.class)) return JsonPointer.empty().appendProperty(field.getField()).toString();
        JsonPointer pointer = JsonPointer.empty();
        for (var node : field.unwrap(ConstraintViolation.class).getPropertyPath()) {
            if (node.getIndex() != null) pointer = pointer.appendIndex(node.getIndex());
            else if (node.getKey() != null) pointer = pointer.appendProperty(node.getKey().toString());
            if (node.getKind() == ElementKind.PROPERTY) pointer = pointer.appendProperty(node.getName());
        }
        return pointer.toString();
    }
}
