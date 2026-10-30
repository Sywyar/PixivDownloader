package top.sywyar.pixivdownload.sdk.schema;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueSchema;
import top.sywyar.pixivdownload.plugin.api.web.RemoteOperations;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("显式远程操作生成可执行客户端及类型和服务端适配器")
class RemoteGenerationTest {
    @TempDir Path temporary;
    private Path source(String body) throws Exception {
        Path project = temporary.resolve("contract");
        Path file = project.resolve("src/main/java/unknown/RemoteProbe.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package unknown;
                import java.util.*;
                import top.sywyar.pixivdownload.plugin.api.contract.schema.*;
                import top.sywyar.pixivdownload.plugin.api.contract.remote.*;
                import top.sywyar.pixivdownload.plugin.api.web.*;
                @RemoteService(owner="probe-contract")
                public interface RemoteProbe {
                    @RemoteOperation(path="/api/probe/execute", method=HttpMethod.POST, access=AccessPolicy.ADMIN)
                    Reply execute(Request request);
                    @ValueSchema record Reply(@ValueProperty(required=true, nullable=false) String text) {}
                """ + body + "\n}", StandardCharsets.UTF_8);
        return project;
    }
    private void generate(Path project) throws Exception {
        SchemaGenerator.generate(project, Path.of(ValueSchema.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
    private static final String REQUEST = """
            @ValueSchema record Request(
                @ValueProperty(required=true, nullable=false, minimum="-9007199254740991", maximum="9007199254740991") long number,
                @ValueProperty(required=true, nullable=false, maxLength=4) String text,
                @ValueProperty(required=false, nullable=true) String note) {}
            """;

    @Test @DisplayName("生成客户端与本地校验器共同拒绝无损编码的非法值，响应保留精度")
    void encodedValues() throws Exception {
        Path project = source("""
                @ValueSchema record Request(
                  @ValueProperty(required=true,nullable=false,encoding=ValueEncoding.DECIMAL_STRING) long sequence,
                  @ValueProperty(required=true,nullable=false) UUID id,
                  @ValueProperty(required=true,nullable=false) java.time.Instant time,
                  @ValueProperty(required=true,nullable=false) java.time.Duration delay,
                  @ValueProperty(required=true,nullable=false) float score,
                  @ValueProperty(required=true,nullable=false) double ratio) {}
                """);
        Path source = project.resolve("src/main/java/unknown/RemoteProbe.java");
        Files.writeString(source, Files.readString(source).replace("Reply execute(Request", "Request execute(Request"));
        generate(project);
        Path generated = project.resolve("target/classes/META-INF/pixivdownload/schema");
        Path schema = generated.resolve("unknown.RemoteProbe.Request.schema.json");
        String valid = """
                {"sequence":"9223372036854775807","id":"12345678-1234-5678-1234-567812345678",
                 "time":"31556889864403199:999999999","delay":"-1:000000001","score":"80000000","ratio":"0000000000000001"}
                """;
        Path sample = project.resolve("sample.json"); Files.writeString(sample, valid);
        SchemaFiles.check(schema, sample);
        for (String broken : List.of(valid.replace("9223372036854775807", "9223372036854775808"),
                valid.replace("80000000", "7f800000"), valid.replace("31556889864403199", "31556889864403200"))) {
            Files.writeString(sample, broken);
            assertThatThrownBy(() -> SchemaFiles.check(schema, sample)).hasMessage("SDK_SCHEMA_DATA_INVALID");
        }
        Path script = project.resolve("encoded.mjs");
        Files.writeString(script, """
                import assert from 'node:assert/strict';
                import {createClient, contractIdentity, wireValues as w} from './target/classes/META-INF/pixivdownload/schema/unknown.RemoteProbe.mjs';
                const input = {sequence: w.int64(9223372036854775807n), id: w.uuid('12345678-1234-5678-1234-567812345678'),
                  time:w.instant(31556889864403199n,999999999),delay:w.duration(-1n,1),score:w.float32(-0),ratio:w.float64(Number.MIN_VALUE)};
                let calls = 0;
                const client = createClient(async (path, init) => { calls++; return new Response(init.body,{headers:{'X-Pixiv-Contract':contractIdentity}}); });
                const result = await client.execute(input);
                assert.deepEqual(result,input); assert.equal(result.sequence,'9223372036854775807');
                assert.ok(Object.is(w.number32(result.score),-0)); assert.equal(w.number64(result.ratio),Number.MIN_VALUE);
                for (const changes of [{sequence:'9223372036854775808'},{time:'31556889864403200:000000000'},
                    {score:'7f800000'},{ratio:'7ff8000000000000'},{id:'1-1-1-1-1'},{sequence:9223372036854775807}])
                  await assert.rejects(client.execute({...input,...changes}), {code:'REMOTE_VALUE_INVALID'});
                assert.equal(calls,1);
                const broken = createClient(async () => new Response(JSON.stringify({...input,ratio:'7ff0000000000000'}),{headers:{'X-Pixiv-Contract':contractIdentity}}));
                await assert.rejects(broken.execute(input),{code:'REMOTE_VALUE_INVALID'});
                """, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("node", script.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(output).isZero();
    }

    @Test @DisplayName("双下划线 proto 操作作为普通成员执行")
    void executesProtoOperation() throws Exception {
        executeSpecialName("__proto__", "text");
    }

    @Test @DisplayName("双下划线 proto 字段保持 JSON 输入输出语义")
    void preservesProtoField() throws Exception {
        executeSpecialName("execute", "__proto__");
    }

    private void executeSpecialName(String operation, String field) throws Exception {
        Path project = source("@ValueSchema record Request(@ValueProperty(required=true, nullable=false) String text) {}");
        Path file = project.resolve("src/main/java/unknown/RemoteProbe.java");
        Files.writeString(file, Files.readString(file).replace("Reply execute(", "Reply " + operation + "(")
                .replace("String text", "String " + field), StandardCharsets.UTF_8);
        generate(project);
        Path check = project.resolve("special-name.mjs");
        Files.writeString(check, """
                import assert from 'node:assert/strict';
                import {createClient, contractIdentity} from './target/classes/META-INF/pixivdownload/schema/unknown.RemoteProbe.mjs';
                const [operation, field] = process.argv.slice(2);
                const input = JSON.parse(`{"${field}":"😀safe"}`);
                let calls = 0;
                const client = createClient(async (path, init) => {
                  calls++;
                  assert.equal(path, '/api/probe/execute');
                  assert.deepEqual(JSON.parse(init.body), input);
                  return new Response(init.body, {headers:{'X-Pixiv-Contract':contractIdentity}});
                });
                assert.equal(Object.hasOwn(client, operation), true);
                const output = await client[operation](input);
                assert.deepEqual(output, input);
                assert.deepEqual(Object.keys(output), [field]);
                assert.equal(calls, 1);
                """, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("node", check.toString(), operation, field).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(output).isZero();
    }

    @Test @DisplayName("业务名称与辅助或 Object 方法同名时适配器仍编译并按路由分派")
    void compilesAndInvokesConflictingNames() throws Exception {
        var names = List.of("routes", "toString", "hashCode", "wait", "getClass", "notify", "remoteOperation0");
        StringBuilder declarations = new StringBuilder(REQUEST);
        for (String name : names) declarations.append("@RemoteOperation(path=\"/api/probe/").append(name)
                .append("\", method=HttpMethod.POST, access=AccessPolicy.ADMIN) Reply ").append(name).append("(Request request);\n");
        Path project = source(declarations.toString()); generate(project);
        Path classes = project.resolve("target/classes");
        String classpath = classes + java.io.File.pathSeparator + System.getProperty("java.class.path");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-proc:none",
                "-encoding", "UTF-8", "-cp", classpath, "-d", classes.toString(),
                project.resolve("src/main/java/unknown/RemoteProbe.java").toString())).isZero();
        Path consumer = temporary.resolve("consumer"); Files.createDirectories(consumer);
        RemoteAdapterGenerator.generate(consumer, classpath, "unknown.RemoteProbe");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL(),
                consumer.resolve("target/classes").toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> adapter = loader.loadClass("pixivdownload.generated.remote.unknown.RemoteProbeRemoteAdapter");
            var calls = new ArrayList<String>();
            RemoteOperations operations = (key, operation, identity) -> {
                assertThat(key.type().getName()).isEqualTo("unknown.RemoteProbe");
                calls.add(operation);
                return new RemoteOperations.Response(200, "{\"text\":\"safe\"}", identity);
            };
            Object instance = adapter.getConstructor(RemoteOperations.class).newInstance(operations);
            for (String name : names) {
                var handler = Arrays.stream(adapter.getDeclaredMethods()).filter(method -> {
                    var mapping = method.getAnnotation(PostMapping.class);
                    return mapping != null && Arrays.asList(mapping.value()).contains("/api/probe/" + name);
                }).findFirst().orElseThrow();
                var response = (ResponseEntity<?>) handler.invoke(instance);
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isEqualTo("{\"text\":\"safe\"}");
            }
            assertThat(calls).containsExactlyElementsOf(names);
            assertThat((List<?>) adapter.getMethod("routes").invoke(null)).hasSize(names.size() + 1);
        }
    }

    @Test @DisplayName("未注解重载不改变远程合同，重复远程操作仍拒绝")
    void localOverloadPreservesContract() throws Exception {
        Path project = source(REQUEST); generate(project);
        Path output = project.resolve("target/classes/META-INF/pixivdownload/schema");
        var before = new LinkedHashMap<String, String>();
        for (String suffix : List.of(".remote.json", ".mjs", ".d.mts", ".java.src"))
            before.put(suffix, Files.readString(output.resolve("unknown.RemoteProbe" + suffix)));
        source(REQUEST + "Reply execute(String localOnly);\n"); generate(project);
        for (var entry : before.entrySet())
            assertThat(Files.readString(output.resolve("unknown.RemoteProbe" + entry.getKey()))).isEqualTo(entry.getValue());
        source(REQUEST + "@RemoteOperation(path=\"/api/probe/other\", method=HttpMethod.POST, access=AccessPolicy.ADMIN) Reply execute(String localOnly);\n");
        assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_COMPILE");
        assertThat(project.resolve("target/schema-generation.complete")).doesNotExist();
    }

    @Test @DisplayName("同简单名的多服务整批编译，非 clean 删除及编译失败均清理旧 Java 和浏览器产物")
    void publishesMultipleServicesAsOneBatch() throws Exception {
        Path project = source(REQUEST);
        Path original = project.resolve("src/main/java/unknown/RemoteProbe.java");
        String contents = Files.readString(original, StandardCharsets.UTF_8);
        Files.delete(original);
        List<Path> sources = new ArrayList<>();
        for (String pack : List.of("first", "second")) {
            Path file = project.resolve("src/main/java/" + pack + "/RemoteProbe.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, contents.replace("package unknown;", "package " + pack + ";")
                    .replace("/api/probe/execute", "/api/" + pack + "/execute"), StandardCharsets.UTF_8);
            sources.add(file);
        }
        generate(project);
        Path classes = project.resolve("target/classes");
        String classpath = classes + java.io.File.pathSeparator + System.getProperty("java.class.path");
        var arguments = new ArrayList<>(List.of("--release", "17", "-proc:none", "-encoding", "UTF-8",
                "-cp", classpath, "-d", classes.toString()));
        sources.forEach(source -> arguments.add(source.toString()));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new))).isZero();
        Path consumer = temporary.resolve("multi-consumer");
        Files.createDirectories(consumer);
        var services = List.of("first.RemoteProbe", "second.RemoteProbe");
        RemoteAdapterGenerator.generate(consumer, classpath, services);
        byte[] marker = Files.readAllBytes(consumer.resolve("target/remote-generation.complete"));
        for (String service : services) {
            assertThat(consumer.resolve("target/classes/" + RemoteAdapterGenerator.adapterName(service).replace('.', '/') + ".class"))
                    .isRegularFile();
            assertThat(consumer.resolve("target/classes/web/generated/" + service + ".mjs")).isRegularFile();
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL(),
                consumer.resolve("target/classes").toUri().toURL()}, getClass().getClassLoader())) {
            for (String service : services) {
                Class<?> adapter = loader.loadClass(RemoteAdapterGenerator.adapterName(service));
                assertThat((List<?>) adapter.getMethod("routes").invoke(null)).hasSize(1);
            }
        }
        RemoteAdapterGenerator.generate(consumer, classpath, List.of(services.get(1), services.get(0)));
        assertThat(Files.readAllBytes(consumer.resolve("target/remote-generation.complete"))).isEqualTo(marker);
        RemoteAdapterGenerator.generate(consumer, classpath, List.of(services.get(0)));
        assertThat(consumer.resolve("target/classes/" + RemoteAdapterGenerator.adapterName(services.get(1)).replace('.', '/') + ".class"))
                .doesNotExist();
        assertThat(consumer.resolve("target/classes/web/generated/" + services.get(1) + ".mjs")).doesNotExist();
        assertThat(consumer.resolve("target/classes/web/generated/" + services.get(1) + ".d.mts")).doesNotExist();
        Path broken = classes.resolve("META-INF/pixivdownload/schema/" + services.get(1) + ".java.src");
        Files.writeString(broken, Files.readString(broken) + "\nthis cannot compile", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> RemoteAdapterGenerator.generate(consumer, classpath, services))
                .hasMessage("SDK_REMOTE_ADAPTER_COMPILE");
        assertThat(consumer.resolve("target/remote-generation.complete")).doesNotExist();
        for (String service : services) {
            assertThat(consumer.resolve("target/classes/" + RemoteAdapterGenerator.adapterName(service).replace('.', '/') + ".class"))
                    .doesNotExist();
            assertThat(consumer.resolve("target/classes/web/generated/" + service + ".mjs")).doesNotExist();
        }
        generate(project);
        RemoteAdapterGenerator.generate(consumer, classpath, services);
        RemoteAdapterGenerator.generate(consumer, classpath, List.of());
        assertThat(Files.readString(consumer.resolve("target/remote-generation.complete"))).isEqualTo("{}");
        assertThat(Files.readString(consumer.resolve("target/remote-generated-files.txt"))).isEmpty();
    }

    @Test @DisplayName("生成可重复，客户端实际运行并在网络调用前拒绝非法值")
    void executesGeneratedClient() throws Exception {
        Path project = source(REQUEST); generate(project);
        Path output = project.resolve("target/classes/META-INF/pixivdownload/schema");
        byte[] original = Files.readAllBytes(output.resolve("unknown.RemoteProbe.mjs"));
        generate(project); assertThat(Files.readAllBytes(output.resolve("unknown.RemoteProbe.mjs"))).isEqualTo(original);
        Path check = project.resolve("check.mjs");
        Files.writeString(check, """
                import assert from 'node:assert/strict';
                import {createClient, contractIdentity} from './target/classes/META-INF/pixivdownload/schema/unknown.RemoteProbe.mjs';
                let calls=0;
                const client=createClient(async (path, init) => {
                  calls++; assert.equal(path, '/api/probe/execute');
                  assert.equal(init.headers['X-Pixiv-Contract'], contractIdentity);
                  return new Response(JSON.stringify({text:JSON.parse(init.body).text}), {headers:{'X-Pixiv-Contract':contractIdentity}});
                });
                assert.deepEqual(await client.execute({number:9007199254740991,text:'😀'}), {text:'😀'});
                for(const data of [{number:9007199254740992,text:'x'}, {number:1.2,text:'x'},
                    {number:1,text:'longer'}, {number:1,text:null}, {number:1,text:'x',extra:true},
                    {number:1,text:'x',note:undefined}, {text:'x'}, {number:1,text:String.fromCharCode(0xd800)}]) {
                  await assert.rejects(client.execute(data), error => error.code === 'REMOTE_VALUE_INVALID');
                }
                await assert.rejects(client.execute({number:1,text:'x',note:'x'.repeat(1024*1024+1)}),
                    error => error.code === 'REMOTE_BUDGET');
                assert.equal(calls,1);
                for (const cancel of [false, true]) {
                  const controller = new AbortController();
                  const broken = createClient(async () => new Response(new ReadableStream({pull(stream) {
                    if (cancel) controller.abort();
                    stream.error(new Error('private stream failure'));
                  }})));
                  await assert.rejects(broken.execute({number:1,text:'x'}, {signal:controller.signal}),
                    error => error.code === (cancel ? 'REMOTE_CANCELLED' : 'REMOTE_TRANSPORT'));
                }
                """, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("node", check.toString()).redirectErrorStream(true).start();
        String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(outputText).isZero();
        assertThat(Files.readString(output.resolve("unknown.RemoteProbe.d.mts"))).contains("execute_Request", "readonly", "number");
        assertThat(Files.readString(output.resolve("unknown.RemoteProbe.java.src"))).contains("@PostMapping", "AccessPolicy.ADMIN");
    }

    @Test @DisplayName("不安全 long、自定义构造器和非管理员暴露在生成时拒绝")
    void rejectsUnsafeDeclarations() throws Exception {
        for (String body : List.of(
                REQUEST.replace(", minimum=\"-9007199254740991\", maximum=\"9007199254740991\"", ""),
                REQUEST.replace("String note) {}", "String note) { public Request { text = text.trim(); } }"),
                REQUEST.replace("required=false, nullable=true", "required=false, nullable=false"))) {
            Path project = source(body);
            assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_COMPILE");
            assertThat(project.resolve("target/schema-generation.complete")).doesNotExist();
        }
        Path project = source(REQUEST);
        Path file = project.resolve("src/main/java/unknown/RemoteProbe.java");
        Files.writeString(file, Files.readString(file).replace("AccessPolicy.ADMIN", "AccessPolicy.PUBLIC"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_COMPILE");
    }

    @Test @DisplayName("契约改动和删除同步更新全部产物，失败撤销完成标记")
    void invalidatesAllOutputs() throws Exception {
        Path project = source(REQUEST); generate(project);
        Path output = project.resolve("target/classes/META-INF/pixivdownload/schema");
        String before = Files.readString(output.resolve("unknown.RemoteProbe.remote.json"));
        source(REQUEST.replace("maxLength=4", "maxLength=3")); generate(project);
        String after = Files.readString(output.resolve("unknown.RemoteProbe.remote.json"));
        assertThat(after).isNotEqualTo(before);
        String identity = SchemaFiles.JSON.readTree(after).path("identity").asText();
        for (String suffix : List.of(".mjs", ".d.mts", ".java.src"))
            assertThat(Files.readString(output.resolve("unknown.RemoteProbe" + suffix))).contains(identity);
        Path file = project.resolve("src/main/java/unknown/RemoteProbe.java");
        Files.writeString(file, Files.readString(file).replace("@RemoteService(owner=\"probe-contract\")", "")
                .replace("@RemoteOperation(path=\"/api/probe/execute\", method=HttpMethod.POST, access=AccessPolicy.ADMIN)", ""), StandardCharsets.UTF_8);
        generate(project);
        assertThat(output.resolve("unknown.RemoteProbe.remote.json")).doesNotExist();
        assertThat(output.resolve("unknown.RemoteProbe.mjs")).doesNotExist();
        assertThat(output.resolve("unknown.RemoteProbe.d.mts")).doesNotExist();
        assertThat(output.resolve("unknown.RemoteProbe.java.src")).doesNotExist();
    }

    @Test @DisplayName("远程操作和路径预算在边界内生成，超出边界拒绝")
    void operationAndPathBudgets() throws Exception {
        StringBuilder methods = new StringBuilder(REQUEST);
        for (int i = 1; i < 128; i++) methods.append("@RemoteOperation(path=\"/api/probe/m")
                .append(i).append("\", method=HttpMethod.POST, access=AccessPolicy.ADMIN) Reply m")
                .append(i).append("(Request request);\n");
        Path project = source(methods.toString()); generate(project);
        var descriptor = SchemaFiles.JSON.readTree(Files.readAllBytes(project.resolve(
                "target/classes/META-INF/pixivdownload/schema/unknown.RemoteProbe.remote.json")));
        assertThat(descriptor.path("operations").size()).isEqualTo(128);
        source(methods + "@RemoteOperation(path=\"/api/probe/overflow\", method=HttpMethod.POST, access=AccessPolicy.ADMIN) Reply overflow(Request request);");
        assertThatThrownBy(() -> generate(project)).hasMessageContaining("SDK_SCHEMA_COMPILE");
        source(REQUEST);
        Path file = project.resolve("src/main/java/unknown/RemoteProbe.java");
        String declaration = Files.readString(file);
        Files.writeString(file, declaration.replace("/api/probe/execute", "/api/" + "x".repeat(251)), StandardCharsets.UTF_8);
        generate(project);
        Files.writeString(file, declaration.replace("/api/probe/execute", "/api/" + "x".repeat(252)), StandardCharsets.UTF_8);
        Path boundedProject = project;
        assertThatThrownBy(() -> generate(boundedProject)).hasMessageContaining("SDK_SCHEMA_COMPILE");
        var fields = new ArrayList<String>();
        for (int i = 0; i < 120; i++) fields.add("@ValueProperty(required=true,nullable=false) String field" + i);
        source("@ValueSchema record Request(" + String.join(",", fields) + ") {}\n"
                + methods.substring(REQUEST.length()));
        generate(project);
        assertThat(Files.size(project.resolve("target/classes/META-INF/pixivdownload/schema/unknown.RemoteProbe.remote.json")))
                .isLessThanOrEqualTo(SchemaFiles.MAX_BYTES);
        for (int i = 120; i < 240; i++) fields.add("@ValueProperty(required=true,nullable=false) String field" + i);
        source("@ValueSchema record Request(" + String.join(",", fields) + ") {}\n"
                + methods.substring(REQUEST.length()));
        assertThatThrownBy(() -> generate(boundedProject)).hasMessageContaining("SDK_SCHEMA_COMPILE");
        assertThat(project.resolve("target/schema-generation.complete")).doesNotExist();
    }
}
