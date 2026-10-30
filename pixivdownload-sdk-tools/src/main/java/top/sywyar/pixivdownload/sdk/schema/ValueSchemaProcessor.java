package top.sywyar.pixivdownload.sdk.schema;

import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueProperty;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueEncoding;
import top.sywyar.pixivdownload.plugin.api.contract.remote.RemoteService;
import top.sywyar.pixivdownload.plugin.api.contract.remote.RemoteOperation;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueSchema;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 读取编译器类型模型，不加载契约类，也不运行它的构造器或初始化器。 */
final class ValueSchemaProcessor extends AbstractProcessor {
    private static final String ANNOTATION = ValueSchema.class.getName();
    private final Map<String, ObjectNode> schemas = new TreeMap<>();
    private final Map<String, ObjectNode> definitions = new LinkedHashMap<>();
    private final Map<String, ObjectNode> remotes = new TreeMap<>();

    Map<String, ObjectNode> schemas() { return schemas; }
    Map<String, ObjectNode> remotes() { return remotes; }

    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of(ANNOTATION, RemoteService.class.getName(), RemoteOperation.class.getName()); }
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_17; }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(ValueSchema.class).stream()
                .sorted(java.util.Comparator.comparing(Object::toString)).toList()) {
            try {
                if (!(element instanceof TypeElement type) || type.getKind() != ElementKind.RECORD) throw invalid();
                schemas.put(type.getQualifiedName().toString(), schema(type.asType()));
            } catch (IllegalArgumentException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), element);
            }
        }
        for (Element element : round.getElementsAnnotatedWith(RemoteService.class)) {
            try {
                TypeElement type = (TypeElement) element;
                remotes.put(type.getQualifiedName().toString(), RemoteSource.describe(type, processingEnv, this::schema));
            } catch (IllegalArgumentException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), element);
            }
        }
        for (Element element : round.getElementsAnnotatedWith(RemoteOperation.class)) {
            if (element.getEnclosingElement().getAnnotation(RemoteService.class) == null)
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "SDK_REMOTE_SERVICE_REQUIRED", element);
        }
        return true;
    }

    private ObjectNode schema(TypeMirror type) {
        definitions.clear();
        ObjectNode root = SchemaFiles.JSON.createObjectNode().put("$schema", SchemaFiles.DIALECT);
        root.setAll(value(type, false, true, 0));
        ObjectNode defs = root.putObject("$defs");
        definitions.forEach(defs::set);
        return root;
    }

    private ObjectNode value(TypeMirror mirror, boolean nullable, boolean elementsNullable, int depth) {
        if (depth > 32) throw invalid();
        ObjectNode result = SchemaFiles.JSON.createObjectNode();
        String name = mirror.toString();
        switch (name) {
            case "boolean", "java.lang.Boolean" -> result.put("type", "boolean");
            case "byte", "java.lang.Byte" -> integer(result, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case "short", "java.lang.Short" -> integer(result, Short.MIN_VALUE, Short.MAX_VALUE);
            case "int", "java.lang.Integer" -> integer(result, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "long", "java.lang.Long" -> integer(result, Long.MIN_VALUE, Long.MAX_VALUE);
            case "java.lang.String" -> result.put("type", "string");
            case "java.util.UUID" -> encoded(result, ValueEncoding.UUID_STRING);
            case "java.time.Instant" -> encoded(result, ValueEncoding.INSTANT_SECONDS_NANOS);
            case "java.time.Duration" -> encoded(result, ValueEncoding.DURATION_SECONDS_NANOS);
            case "float", "java.lang.Float" -> encoded(result, ValueEncoding.FLOAT32_HEX);
            case "double", "java.lang.Double" -> encoded(result, ValueEncoding.FLOAT64_HEX);
            default -> {
                if (!(mirror instanceof DeclaredType declared)
                        || !(declared.asElement() instanceof TypeElement type)) throw invalid();
                String raw = type.getQualifiedName().toString();
                if (raw.equals("java.util.List") && declared.getTypeArguments().size() == 1) {
                    result.put("type", "array");
                    result.set("items", value(declared.getTypeArguments().get(0), elementsNullable, true, depth + 1));
                } else if (raw.equals("java.util.Map") && declared.getTypeArguments().size() == 2
                        && declared.getTypeArguments().get(0).toString().equals("java.lang.String")) {
                    result.put("type", "object");
                    result.set("additionalProperties", value(declared.getTypeArguments().get(1), elementsNullable, true, depth + 1));
                } else {
                    if (!type.getModifiers().contains(Modifier.PUBLIC) || !declared.getTypeArguments().isEmpty()
                            || !type.getTypeParameters().isEmpty()
                            || com.sun.source.util.Trees.instance(processingEnv).getPath(type) == null) throw invalid();
                    if (type.getKind() == ElementKind.ENUM) {
                        result.put("type", "string");
                        var values = result.putArray("enum");
                        for (Element member : type.getEnclosedElements()) {
                            if (member.getKind() == ElementKind.ENUM_CONSTANT) values.add(member.getSimpleName().toString());
                            if (member.getKind() == ElementKind.FIELD && !member.getModifiers().contains(Modifier.STATIC)) throw invalid();
                        }
                        if (values.isEmpty()) throw invalid();
                    } else {
                        if (type.getKind() != ElementKind.RECORD) throw invalid();
                        result.put("$ref", "#/$defs/" + raw);
                        if (!definitions.containsKey(raw)) {
                            if (definitions.size() >= 512) throw invalid();
                            ObjectNode definition = SchemaFiles.JSON.createObjectNode().put("type", "object")
                                    .put("additionalProperties", false);
                            definitions.put(raw, definition);
                            ObjectNode properties = definition.putObject("properties");
                            var required = definition.putArray("required");
                            for (var component : type.getRecordComponents()) {
                                ValueProperty property = component.getAnnotation(ValueProperty.class);
                                if (property == null || component.asType().getKind().isPrimitive() && property.nullable()) throw invalid();
                                ObjectNode schema = value(component.asType(), false, property.elementsNullable(), depth + 1);
                                constraints(schema, property);
                                if (property.encoding() != ValueEncoding.JSON) {
                                    if (property.encoding() != ValueEncoding.DECIMAL_STRING
                                            || !Set.of("long", "java.lang.Long").contains(component.asType().toString())) throw invalid();
                                    String minimum = schema.remove("minimum").asText();
                                    String maximum = schema.remove("maximum").asText();
                                    encoded(schema, ValueEncoding.DECIMAL_STRING);
                                    schema.put("x-minimum", minimum).put("x-maximum", maximum);
                                }
                                properties.set(component.getSimpleName().toString(), nullable(schema, property.nullable()));
                                if (property.required()) required.add(component.getSimpleName().toString());
                            }
                        }
                    }
                }
            }
        }
        return nullable(result, nullable);
    }

    private void constraints(ObjectNode schema, ValueProperty property) {
        String type = schema.path("type").asText();
        if (!property.elementsNullable() && !type.equals("array") && !schema.has("additionalProperties")) throw invalid();
        bound(schema, "minLength", "maxLength", property.minLength(), property.maxLength(), type.equals("string") && !schema.has("x-encoding"));
        bound(schema, "minItems", "maxItems", property.minItems(), property.maxItems(), type.equals("array"));
        if (!property.minimum().isEmpty() || !property.maximum().isEmpty()) {
            if (!type.equals("integer")) throw invalid();
            BigInteger minimum = number(property.minimum(), schema.path("minimum").bigIntegerValue());
            BigInteger maximum = number(property.maximum(), schema.path("maximum").bigIntegerValue());
            if (minimum.compareTo(maximum) > 0 || minimum.compareTo(schema.path("minimum").bigIntegerValue()) < 0
                    || maximum.compareTo(schema.path("maximum").bigIntegerValue()) > 0) throw invalid();
            schema.put("minimum", minimum);
            schema.put("maximum", maximum);
        }
    }

    private static BigInteger number(String text, BigInteger fallback) {
        if (text.isEmpty()) return fallback;
        if (text.length() > 20 || !text.matches("-?(0|[1-9][0-9]*)")) throw invalid();
        return new BigInteger(text);
    }

    private static void bound(ObjectNode schema, String minimumKey, String maximumKey, int minimum, int maximum, boolean supported) {
        if (minimum < -1 || maximum < -1 || minimum >= 0 && maximum >= 0 && minimum > maximum
                || !supported && (minimum >= 0 || maximum >= 0)) throw invalid();
        if (minimum >= 0) schema.put(minimumKey, minimum);
        if (maximum >= 0) schema.put(maximumKey, maximum);
    }

    private static void integer(ObjectNode schema, long minimum, long maximum) {
        schema.put("type", "integer").put("minimum", minimum).put("maximum", maximum);
    }

    private static void encoded(ObjectNode schema, ValueEncoding encoding) {
        schema.put("type", "string").put("x-encoding", encoding.name());
    }

    private static ObjectNode nullable(ObjectNode schema, boolean nullable) {
        if (!nullable) return schema;
        ObjectNode union = SchemaFiles.JSON.createObjectNode();
        union.putArray("anyOf").add(schema).addObject().put("type", "null");
        return union;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("SDK_SCHEMA_CONTRACT_INVALID"); }
}
