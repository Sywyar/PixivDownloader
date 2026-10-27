package top.sywyar.pixivdownload.sdk.schema;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** 从显式编译依赖提取生成适配器；不加载契约类，不启用第三方注解处理器。 */
public final class RemoteAdapterGenerator {
    private static final String PREFIX = "META-INF/pixivdownload/schema/";
    private RemoteAdapterGenerator() { }

    public static void generate(Path module, String classpath, String service) throws IOException {
        module = module.toRealPath();
        if (!javax.lang.model.SourceVersion.isName(service) || !service.contains(".")) throw new IOException("SDK_REMOTE_SERVICE");
        Path completion = module.resolve("target/remote-generation.complete");
        SchemaGenerator.requireLocalPath(module, completion);
        Files.deleteIfExists(completion);
        Path manifest = module.resolve("target/remote-generated-files.txt");
        SchemaGenerator.requireLocalPath(module, manifest);
        if (Files.exists(manifest)) {
            for (String name : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                Path path = module.resolve(name).normalize();
                if (!path.startsWith(module.resolve("target/")) || !name.startsWith("target/")) throw new IOException("SDK_REMOTE_OUTPUT_PATH");
                SchemaGenerator.requireLocalPath(module, path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
            }
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String extension : List.of(".remote.json", ".java.src", ".mjs", ".d.mts")) {
            files.put(extension, readClasspath(classpath, PREFIX + service + extension));
        }
        var descriptor = SchemaFiles.JSON.readTree(files.get(".remote.json"));
        String identity = descriptor.path("identity").asText();
        if (!identity.matches("[a-f0-9]{64}") || !descriptor.path("service").asText().equals(service)) throw new IOException("SDK_REMOTE_CONTRACT");
        for (String extension : List.of(".java.src", ".mjs", ".d.mts"))
            if (!new String(files.get(extension), StandardCharsets.UTF_8).contains(identity)) throw new IOException("SDK_REMOTE_MIXED_OUTPUT");
        int dot = service.lastIndexOf('.');
        String adapter = "pixivdownload/generated/remote/" + service.substring(dot + 1) + "RemoteAdapter";
        Map<String, byte[]> outputs = new LinkedHashMap<>();
        String source = "target/generated-sources/remote/" + adapter + ".java";
        outputs.put(source, files.get(".java.src"));
        outputs.put("target/classes/web/generated/" + service + ".mjs", files.get(".mjs"));
        outputs.put("target/classes/web/generated/" + service + ".d.mts", files.get(".d.mts"));
        List<String> owned = new ArrayList<>(outputs.keySet());
        owned.add("target/classes/" + adapter + ".class");
        for (String name : owned) SchemaGenerator.requireLocalPath(module, module.resolve(name));
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, owned, StandardCharsets.UTF_8);
        for (var output : outputs.entrySet()) {
            Path path = module.resolve(output.getKey());
            Files.createDirectories(path.getParent()); Files.write(path, output.getValue());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("SDK_REMOTE_REQUIRES_JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-proc:none", "-encoding", "UTF-8", "-classpath", classpath,
                            "-d", module.resolve("target/classes").toString()), null,
                    manager.getJavaFileObjects(module.resolve(source).toFile()));
            if (!task.call()) throw new IOException("SDK_REMOTE_ADAPTER_COMPILE");
        }
        Files.writeString(completion, identity, StandardCharsets.UTF_8);
    }

    private static byte[] readClasspath(String classpath, String resource) throws IOException {
        byte[] result = null;
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry);
            byte[] found = null;
            if (Files.isDirectory(path)) {
                Path file = path.resolve(resource);
                if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { found = bounded(input); }
            } else if (Files.isRegularFile(path)) {
                try (var jar = new JarFile(path.toFile())) {
                    var file = jar.getJarEntry(resource);
                    if (file != null) try (var input = jar.getInputStream(file)) { found = bounded(input); }
                }
            }
            if (found != null) {
                if (result != null) throw new IOException("SDK_REMOTE_DUPLICATE_CONTRACT");
                result = found;
            }
        }
        if (result == null) throw new IOException("SDK_REMOTE_CONTRACT_MISSING");
        return result;
    }

    private static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(SchemaFiles.MAX_BYTES + 1);
        if (bytes.length > SchemaFiles.MAX_BYTES) throw new IOException("SDK_REMOTE_OUTPUT_LIMIT");
        return bytes;
    }
}
