package top.sywyar.pixivdownload.sdk.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueSchema;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaGeneratorTest {
    @TempDir Path temporary;
    private static final String IMPORTS = """
            package unknown.contract;
            import top.sywyar.pixivdownload.plugin.api.contract.schema.*;
            import java.util.*;
            """;

    @Test
    @DisplayName("独立源码生成可重复 Schema，真实校验器消费约束，编译产物仍只需要 JDK")
    void generatesAndConsumesSchema() throws Exception {
        Path project = source("normal", """
                @ValueSchema public record Request(
                    @ValueProperty(required=true, nullable=false, minLength=1, maxLength=2) String name,
                    @ValueProperty(required=true, nullable=false, minimum="1", maximum="5") int count,
                    @ValueProperty(required=true, nullable=false, elementsNullable=false, minItems=1, maxItems=2) List<String> texts,
                    @ValueProperty(required=false, nullable=true) String note) {}
                """);
        generate(project);
        Path schema = schema(project, "Request");
        byte[] original = Files.readAllBytes(schema);
        generate(project);
        assertThat(Files.readAllBytes(schema)).isEqualTo(original);
        assertThatCode(() -> check(schema, """
                {"name":"😀","count":5,"texts":["abc"]}
                """)).doesNotThrowAnyException();
        check(schema, "{\"name\":\"x\",\"count\":1,\"texts\":[\"abc\"],\"note\":null}");
        for (String input : List.of(
                "{}", "{\"name\":\"abc\",\"count\":2,\"texts\":[\"x\"]}",
                "{\"name\":null,\"count\":2,\"texts\":[\"x\"]}",
                "{\"name\":\"x\",\"count\":6,\"texts\":[\"x\"]}",
                "{\"name\":\"x\",\"count\":2,\"texts\":[null]}",
                "{\"name\":\"x\",\"count\":2,\"texts\":[]}",
                "{\"name\":\"x\",\"count\":2,\"texts\":[\"x\"],\"extra\":true}")) {
            assertThatThrownBy(() -> check(schema, input)).hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
        }
        Path classes = Files.createDirectories(project.resolve("compiled"));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-proc:none",
                "-classpath", classpath(), "-d", classes.toString(),
                project.resolve("src/main/java/unknown/contract/Request.java").toString());
        assertThat(exit).isZero();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> record = loader.loadClass("unknown.contract.Request");
            Object request = record.getConstructor(String.class, int.class, List.class, String.class)
                    .newInstance("x", 1, List.of("text"), null);
            assertThat(record.getMethod("count").invoke(request)).isEqualTo(1);
            assertThat(new String(Files.readAllBytes(classes.resolve("unknown/contract/Request.class")), StandardCharsets.ISO_8859_1))
                    .doesNotContain("ValueSchema", "ValueProperty", "top/sywyar");
        }
    }

    @Test
    @DisplayName("源码初始化器不会运行，嵌套 record、枚举、映射和递归值由真实 Schema 校验")
    void supportsStructuredValuesWithoutRunningCode() throws Exception {
        Path project = source("nested", """
                @ValueSchema public record Request(
                    @ValueProperty(required=true, nullable=false, elementsNullable=false) Map<String, Item> values,
                    @ValueProperty(required=true, nullable=true) Request next) {
                    static { System.setProperty("schema.executed.input", "yes"); }
                    public record Item(@ValueProperty(required=true, nullable=false) Status status) {}
                    public enum Status { READY, DONE }
                }
                """);
        generate(project);
        assertThat(System.getProperty("schema.executed.input")).isNull();
        Path schema = schema(project, "Request");
        check(schema, "{\"values\":{\"a\":{\"status\":\"READY\"}},\"next\":{\"values\":{},\"next\":null}}");
        assertThatThrownBy(() -> check(schema, "{\"values\":{}}")).hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
        assertThatThrownBy(() -> check(schema, "{\"values\":{\"a\":{\"status\":\"UNKNOWN\"}},\"next\":null}"))
                .hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
    }

    @Test
    @DisplayName("不可投影类型、缺少显式约束和矛盾约束拒绝，失败不会覆盖上次生成物")
    void rejectsUnsupportedContracts() throws Exception {
        int index = 0;
        for (String component : List.of("String text",
                "@ValueProperty(required=true, nullable=true) int count",
                "@ValueProperty(required=true, nullable=false, minimum=\"5\", maximum=\"1\") int count",
                "@ValueProperty(required=true, nullable=false, minItems=1) String text",
                "@ValueProperty(required=true, nullable=false) Object value",
                "@ValueProperty(required=true, nullable=false) Optional<String> value",
                "@ValueProperty(required=true, nullable=false) java.time.Instant value",
                "@ValueProperty(required=true, nullable=false) double value",
                "@ValueProperty(required=true, nullable=false) Map<Integer, String> value",
                "@ValueProperty(required=true, nullable=false) List<?> value")) {
            Path project = source("invalid-" + index++, "@ValueSchema public record Request(" + component + ") {}");
            assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_COMPILE");
            assertThat(schema(project, "Request")).doesNotExist();
        }
        Path project = source("preserve", "@ValueSchema public record Request(@ValueProperty(required=true, nullable=false) int value) {}");
        generate(project);
        byte[] previous = Files.readAllBytes(schema(project, "Request"));
        assertThat(project.resolve("target/schema-generation.complete")).exists();
        source("preserve", "@ValueSchema public record Request(Object value) {}");
        assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_COMPILE");
        assertThat(Files.readAllBytes(schema(project, "Request"))).isEqualTo(previous);
        assertThat(project.resolve("target/schema-generation.complete")).doesNotExist();
    }

    @Test
    @DisplayName("可选字段扩展接受旧数据，新增必填或收紧范围使旧数据显式失败")
    void exposesCompatibilityChangesThroughData() throws Exception {
        Path v1 = source("v1", "@ValueSchema public record Request(@ValueProperty(required=true, nullable=false) int value) {}");
        Path optional = source("optional", """
                @ValueSchema public record Request(@ValueProperty(required=true, nullable=false) int value,
                    @ValueProperty(required=false, nullable=true) String note) {}
                """);
        Path required = source("required", """
                @ValueSchema public record Request(@ValueProperty(required=true, nullable=false) int value,
                    @ValueProperty(required=true, nullable=false) String note) {}
                """);
        Path bounded = source("bounded", "@ValueSchema public record Request(@ValueProperty(required=true, nullable=false, maximum=\"3\") int value) {}");
        for (Path project : List.of(v1, optional, required, bounded)) generate(project);
        check(schema(v1, "Request"), "{\"value\":5}");
        check(schema(optional, "Request"), "{\"value\":5}");
        assertThatThrownBy(() -> check(schema(required, "Request"), "{\"value\":5}")).hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
        assertThatThrownBy(() -> check(schema(bounded, "Request"), "{\"value\":5}")).hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
        assertThatThrownBy(() -> check(schema(v1, "Request"), "{\"value\":5,\"note\":\"new\"}")).hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
    }

    @Test
    @DisplayName("校验器拒绝外部引用、非目标 dialect、重复键和超限输入")
    void rejectsExternalReferencesAndMalformedInput() throws Exception {
        Path schema = temporary.resolve("external.schema.json");
        Files.writeString(schema, "{\"$schema\":\"" + SchemaFiles.DIALECT + "\",\"$defs\":{},\"$ref\":\"https://example.invalid/schema\"}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> check(schema, "{}")).hasMessageContaining("SDK_SCHEMA_REFERENCE");
        Files.writeString(schema, "{\"$schema\":\"https://example.invalid/dialect\"}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> check(schema, "{}")).hasMessageContaining("SDK_SCHEMA_DIALECT");
        Files.writeString(schema, "{\"$schema\":\"" + SchemaFiles.DIALECT + "\",\"$defs\":{\"loop\":{\"$ref\":\"#/$defs/loop\"}},\"$ref\":\"#/$defs/loop\"}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> check(schema, "{}")).hasMessageContaining("SDK_SCHEMA_DOCUMENT_INVALID");
        Path project = source("strict", "@ValueSchema public record Request(@ValueProperty(required=true, nullable=false) long value) {}");
        generate(project);
        check(schema(project, "Request"), "{\"value\":9223372036854775807}");
        assertThatThrownBy(() -> check(schema(project, "Request"), "{\"value\":9223372036854775808}"))
                .hasMessageContaining("SDK_SCHEMA_DATA_INVALID");
        assertThatThrownBy(() -> check(schema(project, "Request"), "{\"value\":1,\"value\":2}"))
                .hasMessageContaining("SDK_SCHEMA_JSON_INVALID");
        assertThatThrownBy(() -> check(schema(project, "Request"), " ".repeat(SchemaFiles.MAX_BYTES + 1)))
                .hasMessageContaining("SDK_SCHEMA_FILE_LIMIT");
    }

    @Test
    @DisplayName("校验器拒绝组合式分支与嵌套定义，并在交给校验库前限制 Schema 累计节点")
    void rejectsNonGeneratedShapesAndExcessiveWork() throws Exception {
        Path project = source("shape", "@ValueSchema public record Request(@ValueProperty(required=true, nullable=true) String value) {}");
        generate(project);
        Path schema = schema(project, "Request");
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) SchemaFiles.read(schema);
        var properties = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("$defs").path("unknown.contract.Request").path("properties");
        for (String invalid : List.of(
                "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"boolean\"}]}",
                "{\"anyOf\":[{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]},{\"type\":\"null\"}]}",
                "{\"type\":\"string\",\"$defs\":{}}")) {
            properties.set("value", SchemaFiles.JSON.readTree(invalid));
            Files.writeString(schema, root.toString(), StandardCharsets.UTF_8);
            assertThatThrownBy(() -> check(schema, "{}")).hasMessageContaining("SDK_SCHEMA_DOCUMENT_INVALID");
        }
        properties.removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("$defs").path("unknown.contract.Request").path("required")).removeAll();
        for (int i = 0; i < 16_384; i++) properties.putObject("value" + i).put("type", "boolean");
        Files.writeString(schema, root.toString(), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> check(schema, "{}")).hasMessageContaining("SDK_SCHEMA_NODE_LIMIT");
        assertThatThrownBy(() -> SchemaFiles.encode(root)).hasMessageContaining("SDK_SCHEMA_NODE_LIMIT");
    }

    @Test
    @DisplayName("源码累计预算超限在编译前失败，工具只写所属模块的生成目录")
    void boundsSourceWorkAndOwnsOutput() throws Exception {
        Path project = source("too-many", "@ValueSchema public record Request() {}");
        Path source = project.resolve("src/main/java");
        for (int i = 0; i < 512; i++) Files.writeString(source.resolve("Extra" + i + ".java"), "", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_SOURCE_LIMIT");
        assertThat(project.resolve("target")).doesNotExist();
        Path large = source("too-large", "@ValueSchema public record Request() {}" + " ".repeat(16 * 1024 * 1024));
        assertThatThrownBy(() -> generate(large)).hasMessageContaining("SDK_SCHEMA_SOURCE_LIMIT");
        Path ordinary = source("owned", "@ValueSchema public record Request() {}");
        Path unrelated = ordinary.resolve("target/classes/keep.txt");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);
        generate(ordinary);
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
        Path old = schema(ordinary, "Removed");
        Files.writeString(old, "{}", StandardCharsets.UTF_8);
        generate(ordinary);
        assertThat(old).doesNotExist();
        assertThat(schema(ordinary, "Request")).exists();
    }

    @Test
    @DisplayName("真实 CLI 拒绝非法 JSON 时只输出错误码与行列，不回显样本 token、字段名或尾随正文")
    void cliDoesNotDiscloseMalformedInput() throws Exception {
        Path project = source("cli-errors", "@ValueSchema public record Request(@ValueProperty(required=true, nullable=false) Map<String, Integer> values) {}");
        generate(project);
        String canary = "SCHEMA_PRIVATE_CANARY_924817";
        Path schema = schema(project, "Request");
        Path malformed = temporary.resolve("malformed.json");
        Path data = temporary.resolve("valid.json");
        Files.writeString(data, "{\"values\":{}}", StandardCharsets.UTF_8);
        for (String body : List.of(canary, "{\"" + canary + "\":1,\"" + canary + "\":2}",
                "{\"value\":\"ok\"} " + canary)) {
            Files.writeString(malformed, body, StandardCharsets.UTF_8);
            for (List<Path> inputs : List.of(List.of(schema, malformed), List.of(malformed, data))) {
                assertThat(cliCheck(inputs.get(0), inputs.get(1)))
                        .contains("SDK_SCHEMA_JSON_INVALID").doesNotContain(canary, "JsonParseException", "Unrecognized token", "Duplicate field");
            }
        }
        Files.writeString(malformed, "{\"values\":{\"" + canary + "\":\"invalid\"}}", StandardCharsets.UTF_8);
        assertThat(cliCheck(schema, malformed)).contains("SDK_SCHEMA_DATA_INVALID").doesNotContain(canary);
    }

    @Test
    @DisplayName("输出目录链接越过模块边界时拒绝，并保留链接目标的 canary")
    void rejectsOutputEscape() throws Exception {
        Path project = source("escape", "@ValueSchema public record Request() {}");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path canary = outside.resolve("keep.txt");
        Files.writeString(canary, "keep", StandardCharsets.UTF_8);
        Path link = project.resolve("target");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor()).isZero();
        } else {
            Files.createSymbolicLink(link, outside);
        }
        try {
            assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_PATH");
            assertThat(Files.readString(canary, StandardCharsets.UTF_8)).isEqualTo("keep");
            assertThat(outside.resolve("classes")).doesNotExist();
        } finally {
            Files.delete(link);
        }
    }

    private Path source(String directory, String source) throws Exception {
        Path project = temporary.resolve(directory);
        Path file = project.resolve("src/main/java/unknown/contract/Request.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, IMPORTS + source, StandardCharsets.UTF_8);
        return project;
    }

    private String cliCheck(Path schema, Path data) throws Exception {
        Path log = Files.createTempFile(temporary, "cli-error-", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "top.sywyar.pixivdownload.sdk.development.SdkTools", "schema-check", schema.toString(), data.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            return Files.readString(log, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void generate(Path project) throws Exception { SchemaGenerator.generate(project, classpath()); }
    private String classpath() throws Exception { return Path.of(ValueSchema.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    private Path schema(Path project, String name) { return project.resolve("target/classes/META-INF/pixivdownload/schema/unknown.contract." + name + ".schema.json"); }
    private void check(Path schema, String value) throws Exception {
        Path file = Files.createTempFile(temporary, "data-", ".json");
        Files.writeString(file, value, StandardCharsets.UTF_8);
        SchemaFiles.check(schema, file);
    }
}
