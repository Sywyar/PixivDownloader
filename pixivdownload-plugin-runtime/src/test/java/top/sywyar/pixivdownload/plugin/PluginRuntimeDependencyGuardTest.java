package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * plugin-runtime 边界守卫：证明本模块是插件框架的 Spring 耦合启用运行时 + PF4J 外置插件运行时骨架 / 发现桥接——
 * 承载 {@link ConditionalOnPluginEnabled} / {@link OnPluginEnabledCondition} / {@link PluginToggleProperties}
 * 三件套、{@code plugin.runtime} 子包（{@link PluginRuntimeManager} 目录定位 / 加载 / 启动 / 诊断，
 * {@link PixivPluginDiscoveryBridge} 把外置插件的 PixivFeaturePlugin 暴露给核心）
 * 以及计划能力租约 / 迁移协议（{@code core.schedule.capability} / {@code core.schedule.migration}；
 * 保留原 FQN 供宿主 registrar 与工作台消费）。允许 Spring（条件 / 环境 /
 * {@code @ConfigurationProperties} 绑定）、PF4J、slf4j、<b>plugin-api</b>（发现桥接产出 PixivFeaturePlugin 需跨边界
 * 共享契约）与 JDK，但<b>零 app / 具体插件类反向依赖</b>，尤其<b>不得回指组合根 {@code BuiltInPlugins} /
 * 运行时 {@code PluginRegistry} / {@code CorePlugin}</b>（它们与本模块共享拆分包
 * {@code top.sywyar.pixivdownload.plugin}，但留在 app、在全部插件模块之上）。
 *
 * <p>本守卫在 {@code pixivdownload-plugin-runtime} 模块内自包含运行：{@link ClassFileImporter} 扫描本模块 main
 * classpath 上的 {@code top.sywyar.pixivdownload..} 类。本模块编译期依赖 plugin-api 后，plugin-api 的契约类也会落到
 * classpath、被一并导入；签名模块也会随统一验签依赖进入 classpath。故各规则的<b>主语集合显式限定</b>为本模块自身的
 * {@code plugin} / {@code plugin.runtime} / {@code core.schedule.capability} /
 * {@code core.schedule.migration} 类，不把 plugin-api / 签名模块自己的依赖面误算进本模块。app 的
 * {@code PluginApiDependencyGuardTest}、core-api 的 {@code CoreApiDependencyGuardTest} 各自从自己模块的 classpath
 * 断言，与本守卫正交。
 */
class PluginRuntimeDependencyGuardTest {

    private static final Pattern PRIVATE_HTTP_ARTIFACT = Pattern.compile(
            "(?i)(?:httpclient|httpcore|httpasyncclient).*");
    private static final Set<DependencyCoordinate> EXPECTED_POM_DEPENDENCIES = Set.of(
            dependency("io.github.sywyar.pixivdownloader", "pixivdownload-sdk-info", "compile"),
            dependency("io.github.sywyar.pixivdownloader", "pixivdownload-plugin-api", "compile"),
            dependency("top.sywyar.lovepopup", "pixivdownload-plugin-worker", "compile"),
            dependency("top.sywyar.lovepopup", "pixivdownload-plugin-signature", "compile"),
            dependency("org.springframework", "spring-context", "compile"),
            dependency("org.springframework", "spring-web", "compile"),
            dependency("org.springframework", "spring-tx", "compile"),
            dependency("org.springframework.boot", "spring-boot", "compile"),
            dependency("org.pf4j", "pf4j", "compile"),
            dependency("org.slf4j", "slf4j-api", "compile"),
            dependency("org.junit.jupiter", "junit-jupiter", "test"),
            dependency("org.assertj", "assertj-core", "test"),
            dependency("org.springframework.boot", "spring-boot-test", "test"),
            dependency("org.mockito", "mockito-core", "test"),
            dependency("org.junit.platform", "junit-platform-launcher", "test"),
            dependency("com.tngtech.archunit", "archunit", "test"));
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("plugin-runtime 必须自包含：只依赖 JDK、Spring、PF4J、slf4j、plugin-api、签名公开接口与自身包")
    void pluginRuntimeIsSelfContained() {
        classes()
                .that().resideInAnyPackage("top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.plugin.runtime..",
                        "top.sywyar.pixivdownload.core.schedule.capability..",
                        "top.sywyar.pixivdownload.core.schedule.migration..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.plugin.runtime..",
                        "top.sywyar.pixivdownload.plugin.api..",
                        "top.sywyar.pixivdownload.sdk..",
                        "top.sywyar.pixivdownload.plugin.signature",
                        "top.sywyar.pixivdownload.core.schedule.capability..",
                        "top.sywyar.pixivdownload.core.schedule.migration..",
                        "java..", "org.springframework..", "org.pf4j..", "org.slf4j..")
                .because("plugin-runtime 是插件框架的 Spring 耦合启用运行时 + PF4J 外置插件运行时骨架 / 发现桥接 / "
                        + "描述符 / 兼容性 / 状态模型 / 计划能力租约与迁移协议：只能依赖 JDK、Spring（条件 / 绑定）、"
                        + "PF4J（PluginManager 等）、slf4j、"
                        + "plugin-api（跨插件契约，发现桥接产出 PixivFeaturePlugin、兼容判定委托 SdkVersion）与自身包 "
                        + "top.sywyar.pixivdownload.plugin（三件套）/ top.sywyar.pixivdownload.plugin.runtime..（PF4J 封装 + "
                        + "发现桥接 + descriptor / status 子包）/ core.schedule.capability / core.schedule.migration，"
                        + "不得依赖任何 app 业务包或具体插件实现包（本规则主语已排除 plugin.api / plugin.signature 自身，"
                        + "只约束本模块拥有的运行时类）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 队列状态保留设施只在 Spring 调度边界之内")
    void pluginRuntimeQueueStatusRetentionStaysAtSchedulingBoundary() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.runtime.download.queue..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin.runtime.download.queue..",
                        "top.sywyar.pixivdownload.plugin.api.download.queue..",
                        "java..", "org.springframework.scheduling..")
                .because("TaskScheduler 相关状态保留属于 plugin-runtime；它只能依赖纯 JDK 队列契约与 Spring 调度 API，"
                        + "不得回指 app 或具体插件")
                .check(CLASSES);

        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.download.queue.QueueStatusRetention.class.getName()))
                .isTrue();
    }

    @Test
    @DisplayName("plugin-runtime 推流注册中心只依赖稳定 stream 契约、slf4j 与 JDK")
    void pluginRuntimeStreamRegistryStaysAtStableContractBoundary() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.runtime.stream..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin.runtime.stream..",
                        "top.sywyar.pixivdownload.plugin.api.stream..",
                        "java..", "org.slf4j..")
                .because("全局推流注册中心属于 plugin-runtime 宿主设施；插件只经 plugin-api 的 owner-scoped "
                        + "registrar 登记，注册中心不得反向依赖 app、具体插件或 Spring 组件扫描")
                .check(CLASSES);

        assertThat(CLASSES.contain(PluginStreamRegistry.class.getName())).isTrue();
        assertThat(Modifier.isFinal(PluginStreamRegistry.class.getModifiers()))
                .as("生命周期测试需要用受控子类观察 close 顺序")
                .isFalse();
        assertThat(PluginStreamRegistry.class.getDeclaredAnnotations())
                .as("注册中心由 app 组合根显式装配，runtime 不得自行组件扫描")
                .isEmpty();
    }

    @Test
    @DisplayName("plugin-runtime 后台任务注册中心只依赖稳定 task 契约与 JDK")
    void pluginRuntimeTaskRegistryStaysAtStableContractBoundary() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.runtime.task..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin.runtime.task..",
                        "top.sywyar.pixivdownload.plugin.api.task..",
                        "java..")
                .because("全局后台任务注册中心属于 plugin-runtime 父加载器设施；插件只经 plugin-api 的 "
                        + "owner-scoped registrar 登记包装器，注册中心不得反向依赖 app、具体插件或 Spring")
                .check(CLASSES);

        assertThat(CLASSES.contain(PluginRuntimeTaskRegistry.class.getName())).isTrue();
        assertThat(Modifier.isFinal(PluginRuntimeTaskRegistry.class.getModifiers()))
                .as("app 生命周期测试需要用受控子类观察清退顺序")
                .isFalse();
        assertThat(PluginRuntimeTaskRegistry.class.getDeclaredAnnotations())
                .as("注册中心由 app 组合根显式装配，runtime 不得自行组件扫描")
                .isEmpty();
    }

    @Test
    @DisplayName("plugin-runtime HTTP bridge 只做稳定传输契约与 Spring-Web 的机械映射")
    void pluginRuntimeHttpBridgeStaysMechanical() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.runtime.http..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin.runtime.http..",
                        "top.sywyar.pixivdownload.plugin.api.http..",
                        "java..",
                        "org.springframework.http..",
                        "org.springframework.web.client..")
                .because("HTTP bridge 只转换 URI、方法、头、请求原始字节、live 响应流与 RestTemplate "
                        + "错误 / 关闭语义；代理选择、Apache 客户端、插件配置和业务策略不得进入 plugin-runtime")
                .check(CLASSES);

        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.runtime.http..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.apache.hc..", "top.sywyar.pixivdownload.config..")
                .because("具体 HTTP 客户端与代理解析只属于 app，runtime bridge 不得复制宿主实现")
                .check(CLASSES);

        assertThat(CLASSES.contain(ManagedPluginRestTemplate.class.getName())).isTrue();
        assertThat(CLASSES.contain(PluginRestTemplateAdapter.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin-runtime POM 依赖面固定且 HTTP bridge 只使用 Spring-Web")
    void pluginRuntimePomMatchesAllowlistWithoutPrivateHttpImplementation()
            throws IOException {
        List<DependencyCoordinate> dependencies = dependencyCoordinates(modulePom());

        assertThat(dependencies)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_POM_DEPENDENCIES);
        assertThat(dependencies)
                .as("dependency 坐标必须为不可隐藏实现的字面量")
                .noneMatch(DependencyCoordinate::hasPlaceholder);
        assertThat(dependencies)
                .as("Apache 等具体 HTTP 栈只属于 app 适配层")
                .noneMatch(dependency ->
                        dependency.groupId().startsWith("org.apache.httpcomponents")
                                || PRIVATE_HTTP_ARTIFACT.matcher(
                                        dependency.artifactId()).matches());
    }

    @Test
    @DisplayName("plugin-runtime 只能依赖签名模块公开接口，不得依赖 internal 实现包")
    void pluginRuntimeDoesNotDependOnSignatureInternals() {
        noClasses()
                .that().resideInAnyPackage("top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.plugin.runtime..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.plugin.signature.internal..")
                .because("统一验签由宿主提供公开门面，runtime 只消费请求 / 结果模型和 verifier，不能触碰 Ed25519、"
                        + "envelope、trust store 等内部实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 不得回指 app 组合根 / 注册中心：BuiltInPlugins / PluginRegistry / CorePlugin")
    void pluginRuntimeDoesNotReverseReferenceAppCompositionRoot() {
        noClasses()
                .that().resideInAnyPackage("top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.plugin.runtime..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.BuiltInPlugins")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.registry.PluginRegistry")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.CorePlugin")
                .because("关键解环手：plugin-runtime 在所有插件模块之下，绝不能回指 app 的组合根 BuiltInPlugins / "
                        + "运行时 PluginRegistry / CorePlugin（它们与本模块共享拆分包 top.sywyar.pixivdownload.plugin "
                        + "但留在 app）；OnPluginEnabledCondition 删掉 isRequired 短路分支后对 BuiltInPlugins 零引用，"
                        + "发现桥接也只产出 plugin-api 契约类型、不回指 app 注册中心，本守卫固化该解环（必选插件不可禁用由 "
                        + "app 侧 PluginRegistry 强制、必选插件无条件 Bean 由 app 侧 PluginApiDependencyGuardTest 守护）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 不得依赖任何 app 业务 / 具体插件包（plugin-api 跨插件契约允许）")
    void pluginRuntimeDoesNotDependOnBusinessPackages() {
        noClasses()
                .that().resideInAnyPackage("top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.plugin.runtime..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.gallery..",
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.stats..",
                        "top.sywyar.pixivdownload.duplicate..",
                        "top.sywyar.pixivdownload.schedule..",
                        "top.sywyar.pixivdownload.core..",
                        "top.sywyar.pixivdownload.gui..",
                        "top.sywyar.pixivdownload.author..",
                        "top.sywyar.pixivdownload.series..",
                        "top.sywyar.pixivdownload.setup..",
                        "top.sywyar.pixivdownload.quota..",
                        "top.sywyar.pixivdownload.push..",
                        "top.sywyar.pixivdownload.ai..",
                        "top.sywyar.pixivdownload.tts..",
                        "top.sywyar.pixivdownload.maintenance..",
                        "top.sywyar.pixivdownload.migration..",
                        "top.sywyar.pixivdownload.tools..",
                        "top.sywyar.pixivdownload.imageclassifier..",
                        "top.sywyar.pixivdownload.scripts..")
                .because("plugin-runtime 零 app 业务 / 具体插件反向依赖；它在所有插件模块之下，任何这类反向依赖都会让 "
                        + "reactor 成环。它对 plugin-api 的依赖是合法跨插件契约（发现桥接产出 PixivFeaturePlugin），不在禁用面内")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含三件套（防守卫 vacuous 通过）")
    void pluginRuntimeContainsToggleRuntimeTypes() {
        assertThat(CLASSES.contain(ConditionalOnPluginEnabled.class.getName())).isTrue();
        assertThat(CLASSES.contain(OnPluginEnabledCondition.class.getName())).isTrue();
        assertThat(CLASSES.contain(PluginToggleProperties.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含 PF4J 运行时骨架与发现桥接（防守卫 vacuous 通过）")
    void pluginRuntimeContainsPf4jRuntimeSkeletonAndDiscoveryBridge() {
        assertThat(CLASSES.contain(PluginRuntimeManager.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState.class.getName())).isTrue();
        assertThat(CLASSES.contain(PixivPluginDiscoveryBridge.class.getName())).isTrue();
        assertThat(CLASSES.contain(DiscoveredFeaturePlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(PluginDiscoveryResult.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含描述符 / 兼容性 / 状态模型（防守卫 vacuous 通过）")
    void pluginRuntimeContainsDescriptorAndStatusModel() {
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含每外置插件子 ApplicationContext 工厂与装配定义（防守卫 vacuous 通过）")
    void pluginRuntimeContainsChildContextFactory() {
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory.class.getName()))
                .isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin-runtime 应包含计划能力租约、迁移协议与生命周期准入视图")
    void pluginRuntimeContainsScheduleRuntimeBoundary() {
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry.class.getName()))
                .isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain.class.getName()))
                .isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter.class.getName()))
                .isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleAdmission.class.getName()))
                .isTrue();
    }

    private static Path modulePom() {
        Path reactorPom = Path.of("pixivdownload-plugin-runtime", "pom.xml");
        return Files.isRegularFile(reactorPom) ? reactorPom : Path.of("pom.xml");
    }

    private static List<DependencyCoordinate> dependencyCoordinates(Path pom)
            throws IOException {
        Document document = parsePom(pom);
        List<DependencyCoordinate> dependencies = new ArrayList<>();
        NodeList dependencyGroups = document.getElementsByTagNameNS("*", "dependencies");
        for (int groupIndex = 0; groupIndex < dependencyGroups.getLength(); groupIndex++) {
            Node dependencyGroup = dependencyGroups.item(groupIndex);
            String parentName = localName(dependencyGroup.getParentNode());
            if (!"project".equals(parentName) && !"profile".equals(parentName)) {
                continue;
            }
            NodeList children = dependencyGroup.getChildNodes();
            for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                Node child = children.item(childIndex);
                if (!(child instanceof Element dependency)
                        || !"dependency".equals(localName(dependency))) {
                    continue;
                }
                String artifactId = directChildText(dependency, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) {
                    String groupId = directChildText(dependency, "groupId");
                    String scope = directChildText(dependency, "scope");
                    dependencies.add(dependency(
                            groupId == null ? "" : groupId.trim(),
                            artifactId.trim(),
                            scope == null || scope.isBlank() ? "compile" : scope.trim()));
                }
            }
        }
        return List.copyOf(dependencies);
    }

    private static Document parsePom(Path pom) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (InputStream input = Files.newInputStream(pom)) {
                return factory.newDocumentBuilder().parse(input);
            }
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException failure) {
            throw new IllegalStateException("Failed to parse Maven POM safely: " + pom, failure);
        }
    }

    private static String directChildText(Element parent, String childName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element && childName.equals(localName(child))) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private static String localName(Node node) {
        if (node == null) {
            return null;
        }
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static DependencyCoordinate dependency(
            String groupId,
            String artifactId,
            String scope
    ) {
        return new DependencyCoordinate(groupId, artifactId, scope);
    }

    private record DependencyCoordinate(
            String groupId,
            String artifactId,
            String scope
    ) {
        private boolean hasPlaceholder() {
            return groupId.contains("${")
                    || artifactId.contains("${")
                    || scope.contains("${");
        }
    }
}
