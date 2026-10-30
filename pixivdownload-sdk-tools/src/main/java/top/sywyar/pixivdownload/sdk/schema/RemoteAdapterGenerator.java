package top.sywyar.pixivdownload.sdk.schema;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** 整批服务共用完成标记；Java、JS 与声明文件全部就位才允许打包。 */
public final class RemoteAdapterGenerator {
    private static final String PREFIX = "META-INF/pixivdownload/schema/";
    private RemoteAdapterGenerator() { }

    public static void generate(Path module, String classpath, String service) throws IOException {
        generate(module, classpath, List.of(service));
    }

    /**
     * services 是本模块的完整集合；空集合撤回全部生成物。
     * 失败清理本工具拥有的旧、新产物与标记，不允许将上次结果误装入新包。
     */
    public static void generate(Path module, String classpath, List<String> services) throws IOException {
        module = module.toRealPath();
        Path completion = module.resolve("target/remote-generation.complete");
        Path manifest = module.resolve("target/remote-generated-files.txt");
        SchemaGenerator.requireLocalPath(module, completion);
        SchemaGenerator.requireLocalPath(module, manifest);
        Files.deleteIfExists(completion);
        Set<String> owned = new LinkedHashSet<>(readManifest(module, manifest));
        Path stage = null;
        boolean completed = false;
        try {
            if (services.size() > 128 || services.stream().distinct().count() != services.size()) {
                throw new IOException("SDK_REMOTE_SERVICE_LIMIT");
            }
            for (String service : services) {
                if (service == null || !javax.lang.model.SourceVersion.isName(service) || !service.contains(".")) {
                    throw new IOException("SDK_REMOTE_SERVICE");
                }
            }
            Files.createDirectories(completion.getParent());
            stage = Files.createTempDirectory(completion.getParent(), ".remote-stage-");
            Map<String, byte[]> outputs = new LinkedHashMap<>();
            Map<String, String> identities = new TreeMap<>();
            List<Path> sources = new ArrayList<>();
            Set<String> routes = new HashSet<>();
            long total = 0;
            for (String service : services.stream().sorted().toList()) {
                Map<String, byte[]> files = new LinkedHashMap<>();
                for (String extension : List.of(".remote.json", ".java.src", ".mjs", ".d.mts")) {
                    byte[] bytes = readClasspath(classpath, PREFIX + service + extension);
                    total += bytes.length;
                    if (total > 16L * 1024 * 1024) throw new IOException("SDK_REMOTE_OUTPUT_LIMIT");
                    files.put(extension, bytes);
                }
                var descriptor = SchemaFiles.JSON.readTree(files.get(".remote.json"));
                String identity = descriptor.path("identity").asText();
                if (!identity.matches("[a-f0-9]{64}") || !descriptor.path("service").asText().equals(service)) {
                    throw new IOException("SDK_REMOTE_CONTRACT");
                }
                for (var operation : descriptor.path("operations")) {
                    if (!routes.add(operation.path("method").asText() + " " + operation.path("path").asText())) {
                        throw new IOException("SDK_REMOTE_ROUTE_COLLISION");
                    }
                }
                for (String extension : List.of(".java.src", ".mjs", ".d.mts")) {
                    if (!new String(files.get(extension), StandardCharsets.UTF_8).contains(identity)) {
                        throw new IOException("SDK_REMOTE_MIXED_OUTPUT");
                    }
                }
                String source = "target/generated-sources/remote/" + adapterName(service).replace('.', '/') + ".java";
                outputs.put(source, files.get(".java.src"));
                outputs.put("target/classes/web/generated/" + service + ".mjs", files.get(".mjs"));
                outputs.put("target/classes/web/generated/" + service + ".d.mts", files.get(".d.mts"));
                sources.add(stage.resolve(source));
                identities.put(service, identity);
            }
            for (var output : outputs.entrySet()) {
                Path staged = stage.resolve(output.getKey());
                Files.createDirectories(staged.getParent());
                Files.write(staged, output.getValue());
            }
            Path classes = stage.resolve("target/classes");
            Files.createDirectories(classes);
            if (!sources.isEmpty()) compile(classpath, sources, classes);
            Set<String> next = new LinkedHashSet<>(outputs.keySet());
            try (var paths = Files.walk(classes)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    next.add(stage.relativize(file).toString().replace(File.separatorChar, '/'));
                }
            }
            // 清单记录编译器实际输出，包含内部类；移除旧服务不能留下旧 .class。
            owned.addAll(next);
            for (String name : owned) requireOutput(module, name);
            Files.write(manifest, owned, StandardCharsets.UTF_8);
            removeOwned(module, owned);
            for (String name : next) {
                Path destination = module.resolve(name);
                Files.createDirectories(destination.getParent());
                Files.move(stage.resolve(name), destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.write(manifest, next, StandardCharsets.UTF_8);
            Path marker = stage.resolve("complete");
            Files.write(marker, SchemaFiles.JSON.writeValueAsBytes(identities));
            Files.move(marker, completion, StandardCopyOption.ATOMIC_MOVE);
            completed = true;
        } finally {
            try {
                if (!completed) {
                    Files.deleteIfExists(completion);
                    removeOwned(module, owned);
                }
            } finally {
                if (stage != null) removeStage(module, stage);
            }
        }
    }

    static String adapterName(String service) {
        int dot = service.lastIndexOf('.');
        return "pixivdownload.generated.remote." + service.substring(0, dot) + "."
                + service.substring(dot + 1) + "RemoteAdapter";
    }

    private static void compile(String classpath, List<Path> sources, Path classes) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("SDK_REMOTE_REQUIRES_JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-proc:none", "-encoding", "UTF-8", "-classpath", classpath,
                            "-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(sources));
            if (!task.call()) throw new IOException("SDK_REMOTE_ADAPTER_COMPILE");
        }
    }

    private static List<String> readManifest(Path module, Path manifest) throws IOException {
        if (!Files.exists(manifest)) return List.of();
        byte[] bytes;
        try (var input = Files.newInputStream(manifest)) { bytes = input.readNBytes(1024 * 1024 + 1); }
        if (bytes.length > 1024 * 1024) throw new IOException("SDK_REMOTE_OUTPUT_LIMIT");
        var names = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (names.size() > 4096) throw new IOException("SDK_REMOTE_OUTPUT_LIMIT");
        for (String name : names) requireOutput(module, name);
        return names;
    }

    private static void requireOutput(Path module, String name) throws IOException {
        Path path = module.resolve(name).normalize();
        if (name.length() > 1024 || !name.startsWith("target/") || !path.startsWith(module.resolve("target"))) {
            throw new IOException("SDK_REMOTE_OUTPUT_PATH");
        }
        SchemaGenerator.requireLocalPath(module, path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SDK_REMOTE_OUTPUT_PATH");
        }
    }

    private static void removeOwned(Path module, Collection<String> names) throws IOException {
        for (String name : names) requireOutput(module, name);
        for (String name : names) Files.deleteIfExists(module.resolve(name));
    }

    private static void removeStage(Path module, Path stage) throws IOException {
        SchemaGenerator.requireLocalPath(module, stage);
        try (var paths = Files.walk(stage)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                SchemaGenerator.requireLocalPath(module, path);
                Files.delete(path);
            }
        }
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
