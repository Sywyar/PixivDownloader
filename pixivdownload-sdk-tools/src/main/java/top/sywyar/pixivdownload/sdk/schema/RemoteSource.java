package top.sywyar.pixivdownload.sdk.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.source.util.Trees;
import top.sywyar.pixivdownload.plugin.api.contract.remote.*;
import top.sywyar.pixivdownload.plugin.api.web.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.function.Function;

/** 远程值子集在编译器模型中拒绝自定义构造 / 序列化含义及不安全整数。 */
final class RemoteSource {
    private static final BigInteger SAFE = BigInteger.valueOf(9_007_199_254_740_991L);

    static ObjectNode describe(TypeElement type, ProcessingEnvironment environment, Function<TypeMirror, ObjectNode> schema) {
        if (type.getKind() != ElementKind.INTERFACE || type.getEnclosingElement().getKind() != ElementKind.PACKAGE
                || !type.getModifiers().contains(Modifier.PUBLIC) || !type.getTypeParameters().isEmpty()
                || !type.getInterfaces().isEmpty()) throw invalid();
        String owner = type.getAnnotation(RemoteService.class).owner();
        if (owner.length() > 128 || !owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) throw invalid();
        ObjectNode result = SchemaFiles.JSON.createObjectNode().put("format", 1).put("service", type.getQualifiedName().toString())
                .put("owner", owner);
        ObjectNode operations = result.putObject("operations");
        var paths = new HashSet<String>();
        long descriptionBytes = 0;
        for (Element element : type.getEnclosedElements()) {
            RemoteOperation operation = element.getAnnotation(RemoteOperation.class);
            if (operation == null) continue;
            if (!(element instanceof ExecutableElement method) || !method.getModifiers().contains(Modifier.ABSTRACT)
                    || method.getParameters().size() != 1 || !method.getTypeParameters().isEmpty()
                    || !method.getThrownTypes().isEmpty() || operation.method() != HttpMethod.POST || operation.access() != AccessPolicy.ADMIN
                    || !operation.path().matches("/api/(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_-]+")
                    || operation.path().length() > 256 || !paths.add(operation.path())
                    || operations.has(method.getSimpleName().toString()) || operations.size() >= 128) throw invalid();
            ObjectNode input = schema.apply(method.getParameters().get(0).asType());
            ObjectNode output = schema.apply(method.getReturnType());
            check(input, environment); check(output, environment);
            ObjectNode entry = operations.putObject(method.getSimpleName().toString())
                    .put("path", operation.path()).put("method", operation.method().name()).put("access", operation.access().name());
            entry.set("request", input); entry.set("response", output);
            // 逐操作累计，避免同一大 record 在许多操作中复制后才检查整个描述大小。
            try { descriptionBytes += SchemaFiles.JSON.writeValueAsBytes(entry).length; }
            catch (java.io.IOException failure) { throw invalid(); }
            if (descriptionBytes > SchemaFiles.MAX_BYTES) throw new IllegalArgumentException("SDK_REMOTE_OUTPUT_LIMIT");
        }
        if (operations.isEmpty()) throw invalid();
        return result;
    }

    private static void check(ObjectNode schema, ProcessingEnvironment environment) {
        if (!schema.has("$ref") || !schema.has("$defs")) throw invalid();
        var names = schema.path("$defs").fieldNames();
        while (names.hasNext()) {
            TypeElement record = environment.getElementUtils().getTypeElement(names.next());
            annotations(record);
            record.getRecordComponents().forEach(RemoteSource::annotations);
            Trees trees = Trees.instance(environment);
            for (Element member : record.getEnclosedElements()) {
                annotations(member);
                if (member.getKind() == ElementKind.CONSTRUCTOR || member.getKind() == ElementKind.METHOD) {
                    var path = trees.getPath(member);
                    if (path != null && trees.getSourcePositions().getEndPosition(path.getCompilationUnit(), path.getLeaf()) >= 0)
                        throw new IllegalArgumentException("SDK_REMOTE_CUSTOM_VALUE");
                }
            }
        }
        values(schema);
    }

    private static void annotations(Element element) {
        for (var annotation : element.getAnnotationMirrors()) {
            String name = annotation.getAnnotationType().toString();
            if (!java.util.Set.of("top.sywyar.pixivdownload.plugin.api.contract.schema.ValueSchema",
                    "top.sywyar.pixivdownload.plugin.api.contract.schema.ValueProperty",
                    "java.lang.Override", "java.lang.SuppressWarnings").contains(name))
                throw new IllegalArgumentException("SDK_REMOTE_CUSTOM_VALUE");
        }
    }

    private static void values(JsonNode node) {
        if (node.isObject() && node.path("type").asText().equals("integer")
                && (node.path("minimum").bigIntegerValue().compareTo(SAFE.negate()) < 0
                || node.path("maximum").bigIntegerValue().compareTo(SAFE) > 0))
            throw new IllegalArgumentException("SDK_REMOTE_UNSAFE_INTEGER");
        if (node.has("properties")) {
            var fields = node.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                boolean required = false;
                for (JsonNode name : node.path("required")) if (name.asText().equals(field.getKey())) required = true;
                if (!required && !field.getValue().has("anyOf")) throw new IllegalArgumentException("SDK_REMOTE_OPTIONAL_NON_NULL");
            }
        }
        if (node.isContainerNode()) node.forEach(RemoteSource::values);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("SDK_REMOTE_CONTRACT_INVALID"); }
}
