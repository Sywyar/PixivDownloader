import org.springframework.aot.generate.ClassNameGenerator;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.FileSystemGeneratedFiles;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Lookup;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.context.annotation.*;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.javapoet.ClassName;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 构建时生成稳定宿主组件的 Bean 定义和构造代码，条件与基础设施继续在运行时处理。 */
public final class GenerateHostComponents {
    private static final String GENERATED_PACKAGE = "top.sywyar.pixivdownload.startup.aot";

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        if (args.length != 2) throw new IllegalArgumentException("Expected the build directory and Java release");
        generate("top.sywyar.pixivdownload", Path.of(args[0]), args[1]);
    }

    public static void generate(String basePackage, Path buildDirectory, String release) throws Exception {
        Path output = buildDirectory.toAbsolutePath().normalize().resolve("spring-aot");
        if (Files.isSymbolicLink(output)) throw new IOException("Spring AOT output must not be a symbolic link");
        FileSystemUtils.deleteRecursively(output);
        Path sources = Files.createDirectories(output.resolve("sources"));
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path resources = Files.createDirectories(output.resolve("resources"));
        List<String> runtime = new ArrayList<>();
        int stableCount;
        String initializer;
        try (var context = new GenericApplicationContext()) {
            var component = new AnnotationTypeFilter(Component.class);
            boolean namedAvailable = ClassUtils.isPresent("jakarta.inject.Named", context.getClassLoader());
            var managed = new AnnotationTypeFilter(PluginManagedBean.class);
            var autoConfiguration = new AutoConfigurationExcludeFilter();
            var scanner = new ClassPathBeanDefinitionScanner(context) {
                @Override
                protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                    // 索引须包含条件不匹配的组件，由实际启动环境重新判定。
                    return (component.match(reader, getMetadataReaderFactory())
                            || (namedAvailable && reader.getAnnotationMetadata().hasAnnotation("jakarta.inject.Named")))
                            && !managed.match(reader, getMetadataReaderFactory())
                            && !autoConfiguration.match(reader, getMetadataReaderFactory());
                }
            };
            scanner.setIncludeAnnotationConfig(false);
            // 此处只收集原始候选；有作用域的组件留给运行时创建代理。
            scanner.setScopeMetadataResolver(definition -> new ScopeMetadata());
            scanner.scan(basePackage);
            for (String name : context.getBeanDefinitionNames()) {
                var definition = context.getBeanDefinition(name);
                Class<?> type = Class.forName(definition.getBeanClassName(), false, context.getClassLoader());
                if (runtimeOnly(type)) {
                    runtime.add(type.getName());
                    context.removeBeanDefinition(name);
                }
            }
            stableCount = context.getBeanDefinitionCount();
            var files = new FileSystemGeneratedFiles(kind -> switch (kind) {
                case SOURCE -> sources;
                case CLASS -> classes;
                case RESOURCE -> resources;
            });
            var generation = new DefaultGenerationContext(
                    new ClassNameGenerator(ClassName.get(GENERATED_PACKAGE, "HostComponents")), files);
            initializer = new ApplicationContextAotGenerator().processAheadOfTime(context, generation).canonicalName();
            generation.writeGeneratedContent();
        }
        runtime.sort(String::compareTo);
        Path adapter = sources.resolve(GENERATED_PACKAGE.replace('.', '/')).resolve("GeneratedHostComponents.java");
        Files.createDirectories(adapter.getParent());
        String names = runtime.stream().map(name -> "\"" + name + "\"").collect(java.util.stream.Collectors.joining(",\n"));
        Files.writeString(adapter, """
                package %s;
                @org.springframework.aot.generate.Generated
                public final class GeneratedHostComponents implements
                        org.springframework.context.ApplicationContextInitializer<org.springframework.context.support.GenericApplicationContext> {
                    @Override public void initialize(org.springframework.context.support.GenericApplicationContext context) {
                        new %s().initialize(context);
                        HostComponents.runtimeComponents(context, java.util.List.of(%s));
                    }
                }
                """.formatted(GENERATED_PACKAGE, initializer, names), StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Spring AOT generation requires the build JDK compiler");
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
             var paths = Files.walk(sources)) {
            var units = manager.getJavaFileObjectsFromPaths(paths.filter(path -> path.toString().endsWith(".java")).sorted().toList());
            var options = List.of("-proc:none", "-encoding", "UTF-8", "--release", release, "-classpath",
                    System.getProperty("java.class.path") + java.io.File.pathSeparator + classes, "-d", classes.toString());
            if (!compiler.getTask(null, manager, null, options, null, units).call())
                throw new IllegalStateException("Spring AOT generated source compilation failed");
        }
        System.out.println("Spring AOT host components: generated=" + stableCount + ", runtime=" + runtime.size());
    }

    private static boolean runtimeOnly(Class<?> type) {
        var annotations = MergedAnnotations.from(type, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
        if (annotations.isPresent(Configuration.class) || annotations.isPresent(Conditional.class)
                || annotations.isPresent(ComponentScan.class) || annotations.isPresent(Import.class)
                || annotations.isPresent(ImportResource.class) || annotations.isPresent(PropertySource.class)
                || annotations.isPresent(Scope.class) || type.getDeclaredConstructors().length != 1
                || BeanFactoryPostProcessor.class.isAssignableFrom(type) || BeanPostProcessor.class.isAssignableFrom(type)
                || FactoryBean.class.isAssignableFrom(type)) return true;
        return Arrays.stream(ReflectionUtils.getAllDeclaredMethods(type)).anyMatch(method ->
                MergedAnnotations.from(method).isPresent(Bean.class) || MergedAnnotations.from(method).isPresent(Lookup.class));
    }
}
