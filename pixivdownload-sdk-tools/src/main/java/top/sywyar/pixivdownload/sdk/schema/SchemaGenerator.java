package top.sywyar.pixivdownload.sdk.schema;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 从当前模块源码生成随 API JAR 交付的 Schema；不扫描宿主业务名单。 */
public final class SchemaGenerator {
    private static final String OUTPUT = "target/classes/META-INF/pixivdownload/schema";
    private SchemaGenerator() { }

    /**
     * 在模块自己的 target/classes 下生成 Schema，编译器只启用本工具的处理器。
     * @param module 独立契约源码模块
     * @param classpath 当前模块的编译 classpath
     * @throws IOException 输入、编译或输出无效时抛出
     */
    public static void generate(Path module, String classpath) throws IOException {
        module = module.toRealPath();
        Path completion = module.resolve("target/schema-generation.complete");
        requireLocalPath(module, completion);
        if (Files.exists(completion, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(completion, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SDK_SCHEMA_OUTPUT_CONTENT");
        }
        Files.deleteIfExists(completion);
        Path source = module.resolve("src/main/java");
        requireLocalPath(module, source);
        List<Path> sources;
        try (var paths = Files.walk(source)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().limit(513).toList();
        }
        if (sources.isEmpty() || sources.size() > 512) throw new IOException("SDK_SCHEMA_SOURCE_LIMIT");
        long total = 0;
        for (Path file : sources) {
            requireLocalPath(module, file);
            total += Files.size(file);
            if (total > 16L * 1024 * 1024) throw new IOException("SDK_SCHEMA_SOURCE_LIMIT");
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("SDK_SCHEMA_REQUIRES_JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var processor = new ValueSchemaProcessor();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-proc:only", "-encoding", "UTF-8", "-classpath", classpath),
                    null, manager.getJavaFileObjectsFromPaths(sources));
            task.setProcessors(List.of(processor));
            if (!task.call()) {
                var first = diagnostics.getDiagnostics().stream().filter(item -> item.getKind() == Diagnostic.Kind.ERROR).findFirst();
                throw new IOException("SDK_SCHEMA_COMPILE: " + first.map(item -> item.getCode() + ":" + item.getLineNumber()).orElse("unknown"));
            }
        }
        if (processor.schemas().isEmpty()) throw new IOException("SDK_SCHEMA_NO_ROOTS");
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (var entry : processor.schemas().entrySet()) files.put(entry.getKey() + ".schema.json", SchemaFiles.encode(entry.getValue()));
        Path output = module.resolve(OUTPUT);
        requireLocalPath(module, output);
        requireLocalPath(module, completion);
        Files.createDirectories(output);
        // 全部契约通过后才更新生成目录；只删除本工具拥有的旧 schema 文件。
        try (var old = Files.list(output)) {
            for (Path file : old.toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.getFileName().toString().endsWith(".schema.json")) {
                    throw new IOException("SDK_SCHEMA_OUTPUT_CONTENT");
                }
            }
        }
        for (var entry : files.entrySet()) {
            Path temporary = Files.createTempFile(output, ".schema-", ".tmp");
            try {
                Files.write(temporary, entry.getValue());
                Files.move(temporary, output.resolve(entry.getKey()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
        try (var old = Files.list(output)) {
            for (Path file : old.toList()) if (!files.containsKey(file.getFileName().toString())) Files.delete(file);
        }
        // Maven 在 validate 清除标记；只在本次所有生成物写入完成后允许打包。
        Files.writeString(completion, "", StandardCharsets.UTF_8);
    }

    private static void requireLocalPath(Path module, Path path) throws IOException {
        if (!path.normalize().startsWith(module)) throw new IOException("SDK_SCHEMA_PATH");
        for (Path current = path; !current.equals(module); current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().startsWith(module))) throw new IOException("SDK_SCHEMA_PATH");
        }
    }
}
