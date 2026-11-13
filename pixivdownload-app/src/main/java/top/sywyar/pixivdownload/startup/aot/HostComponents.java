package top.sywyar.pixivdownload.startup.aot;

import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.io.IOException;
import java.util.List;

/** 发行包复用 Spring 生成的宿主组件装配；条件配置和外置插件仍由当前环境决定。 */
public final class HostComponents implements ApplicationContextInitializer<GenericApplicationContext> {
    private static final String GENERATED = HostComponents.class.getPackageName() + ".GeneratedHostComponents";
    private static final String HOST_PATTERN = "classpath*:top/sywyar/pixivdownload/**/*.class";

    /** 只缩小宿主根扫描的资源集合，保留 Spring 原有配置解析和自动配置顺序。 */
    public static ApplicationContextFactory contextFactory() {
        return type -> switch (type) {
            case SERVLET -> new ServletContext();
            case NONE -> new NonWebContext();
            default -> ApplicationContextFactory.DEFAULT.create(type);
        };
    }

    @Override
    public void initialize(GenericApplicationContext context) {
        if (!(context instanceof IndexedContext) || PluginDevelopmentArtifacts.enabled()) return;
        Class<?> generated;
        try { generated = Class.forName(GENERATED, false, context.getClassLoader()); }
        catch (ClassNotFoundException developmentClasses) { return; }
        try {
            @SuppressWarnings("unchecked")
            var initializer = (ApplicationContextInitializer<GenericApplicationContext>) generated.getConstructor().newInstance();
            initializer.initialize(context);
            LoggerFactory.getLogger(HostComponents.class).info("Spring AOT host components registered; conditional configuration remains dynamic");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Spring AOT host initialization failed", failure);
        }
    }

    /** 由同批生成的初始化器设置，本次 context 独占，不影响插件子 context 的扫描。 */
    public static void runtimeComponents(GenericApplicationContext context, List<String> classNames) {
        ((IndexedContext) context).index().initialize(context, classNames);
    }

    private interface IndexedContext { Index index(); }

    private static final class Index {
        private Resource[] components;

        void initialize(ResourceLoader loader, List<String> classNames) {
            components = classNames.stream().map(name -> loader.getResource("classpath:" + name.replace('.', '/') + ".class"))
                    .toArray(Resource[]::new);
        }

        Resource[] find(String pattern) {
            return components != null && HOST_PATTERN.equals(pattern) ? components.clone() : null;
        }
    }

    private static final class ServletContext extends AnnotationConfigServletWebServerApplicationContext implements IndexedContext {
        private final Index index = new Index();
        @Override public Index index() { return index; }
        @Override public Resource[] getResources(String pattern) throws IOException {
            Resource[] indexed = index.find(pattern);
            return indexed != null ? indexed : super.getResources(pattern);
        }
    }

    private static final class NonWebContext extends AnnotationConfigApplicationContext implements IndexedContext {
        private final Index index = new Index();
        @Override public Index index() { return index; }
        @Override public Resource[] getResources(String pattern) throws IOException {
            Resource[] indexed = index.find(pattern);
            return indexed != null ? indexed : super.getResources(pattern);
        }
    }
}
