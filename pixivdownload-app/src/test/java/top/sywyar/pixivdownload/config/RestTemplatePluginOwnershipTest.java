package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("父容器 RestTemplate 配置的插件所有权边界")
class RestTemplatePluginOwnershipTest {

    private static final Set<String> CORE_REST_TEMPLATE_BEANS = Set.of(
            "restTemplate",
            "pixivCredentialRestTemplate",
            "downloadRestTemplate",
            "pixivImageRestTemplate");

    @Test
    @DisplayName("父应用全部生产配置只声明核心 HTTP 客户端")
    void parentConfigurationDoesNotDeclarePluginPrivateRestTemplates()
            throws IOException, URISyntaxException {
        List<RestTemplateBeanDeclaration> declarations =
                declaredRestTemplateBeanDeclarations();

        assertThat(declarations)
                .extracting(RestTemplateBeanDeclaration::beanName)
                .containsExactlyInAnyOrderElementsOf(CORE_REST_TEMPLATE_BEANS);
        assertThat(declarations)
                .extracting(RestTemplateBeanDeclaration::owner)
                .containsOnly(RestTemplateConfig.class.getName());
    }

    private static List<RestTemplateBeanDeclaration> declaredRestTemplateBeanDeclarations()
            throws IOException, URISyntaxException {
        List<RestTemplateBeanDeclaration> declarations = new ArrayList<>();
        Path classesRoot = Path.of(RestTemplateConfig.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path appPackageRoot = classesRoot.resolve("top/sywyar/pixivdownload");
        if (!Files.isDirectory(appPackageRoot)) {
            throw new IllegalStateException(
                    "Cannot locate compiled app production classes under " + classesRoot);
        }
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(appPackageRoot)) {
            classFiles = files
                    .filter(path -> path.toString().endsWith(".class"))
                    .sorted()
                    .toList();
        }
        for (Path classFile : classFiles) {
            Class<?> type = loadWithoutInitialization(classesRoot, classFile);
            for (Method method : type.getDeclaredMethods()) {
                Bean bean = method.getDeclaredAnnotation(Bean.class);
                if (bean == null
                        || !RestTemplate.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Set<String> beanNames = new LinkedHashSet<>();
                boolean hasName = addExplicitNames(beanNames, bean.name());
                hasName = addExplicitNames(beanNames, bean.value()) || hasName;
                if (!hasName) {
                    beanNames.add(method.getName());
                }
                beanNames.forEach(beanName -> declarations.add(
                        new RestTemplateBeanDeclaration(
                                beanName, type.getName(), method.getName())));
            }
        }
        return List.copyOf(declarations);
    }

    private static Class<?> loadWithoutInitialization(Path classesRoot, Path classFile) {
        String className = classesRoot.relativize(classFile)
                .toString()
                .replace(File.separatorChar, '.')
                .replaceFirst("\\.class$", "");
        try {
            return Class.forName(className, false, RestTemplateConfig.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException(
                    "Cannot inspect app production class " + className, failure);
        }
    }

    private static boolean addExplicitNames(Set<String> beanNames, String[] candidates) {
        boolean found = false;
        for (String candidate : candidates) {
            if (!candidate.isBlank()) {
                beanNames.add(candidate);
                found = true;
            }
        }
        return found;
    }

    private record RestTemplateBeanDeclaration(
            String beanName,
            String owner,
            String method
    ) {
    }
}
