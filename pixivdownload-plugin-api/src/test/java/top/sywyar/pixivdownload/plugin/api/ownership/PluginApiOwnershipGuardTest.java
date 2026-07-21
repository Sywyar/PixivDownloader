package top.sywyar.pixivdownload.plugin.api.ownership;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plugin-api 所有权白名单。生产类型只有在表达稳定的第三方插件协议时才能进入本模块；
 * 仅满足纯 JDK、被当前宿主使用或服务已有持久数据迁移，不足以成为 Plugin API。
 */
@DisplayName("plugin-api 所有权边界")
class PluginApiOwnershipGuardTest {

    private static final String API_PREFIX = "top.sywyar.pixivdownload.plugin.api.";
    private static final String REQUEST_OWNER_RESOLVER = API_PREFIX + "web.RequestOwnerIdentityResolver";
    private static final Set<String> PRIMITIVE_TYPE_NAMES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
    );
    private static final Map<String, String> APPROVED_GUI_CONFIG_GROUPS = Map.ofEntries(
            Map.entry("SERVER", "server"),
            Map.entry("DOWNLOAD", "download"),
            Map.entry("PLUGINS", "plugins"),
            Map.entry("PROXY", "proxy"),
            Map.entry("MULTI_MODE", "multi-mode"),
            Map.entry("GUEST_INVITE", "guest-invite"),
            Map.entry("SECURITY", "security"),
            Map.entry("MAINTENANCE", "maintenance"),
            Map.entry("HTTPS", "https"),
            Map.entry("UPDATE", "update"),
            Map.entry("SCHEDULE", "schedule"),
            Map.entry("AI", "ai"),
            Map.entry("NOTIFICATION", "notification")
    );

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.plugin.api");

    private static final Map<String, Set<String>> APPROVED_TYPES_BY_OWNER = Map.ofEntries(
            Map.entry("插件入口、版本与生命周期", union(
                    types("top.sywyar.pixivdownload.plugin.api", "PluginApiVersion"),
                    types(API_PREFIX + "plugin",
                            "PixivFeaturePlugin", "PixivPluginProvider", "PluginKind", "PluginManagedBean"))),
            Map.entry("GUI 纯数据 contribution", types(API_PREFIX + "gui",
                    "GuiConfigActionContribution", "GuiConfigActionPayloadField", "GuiConfigActionPayloadType",
                    "GuiConfigActionResultArgument", "GuiConfigActionResultCondition",
                    "GuiConfigActionResultOperator", "GuiConfigActionResultRule", "GuiConfigActionResultSource",
                    "GuiConfigActionResultSummary", "GuiConfigCondition", "GuiConfigConditionOperator",
                    "GuiConfigContribution", "GuiConfigFieldContribution", "GuiConfigFieldLayoutContribution",
                    "GuiConfigFieldType", "GuiConfigGroupContribution", "GuiConfigGroups",
                    "GuiConfigPresetContribution", "GuiConfigPresetMatchMode", "GuiConfigSectionContribution",
                    "GuiConfigSectionLayout", "GuiConfigSectionNoticeContribution",
                    "GuiConfigSectionNoticeStyle", "GuiOnboardingStepContribution", "GuiThemeAppearance",
                    "GuiThemeApplier", "GuiThemeChangeListener", "GuiThemeContribution",
                    "GuiThemeListenerFactory", "GuiThemeListenerSession")),
            Map.entry("Web、下载描述与请求身份协议", types(API_PREFIX + "web",
                    "AccessPolicy", "Audience", "DownloadAcquisitionMode", "DownloadGalleryCapabilities",
                    "DownloadQueueCapabilities", "DownloadScheduleCapabilities", "DownloadTypeDescriptor",
                    "DrilldownContribution", "DrilldownPlacements", "HttpMethod", "I18nContribution",
                    "LandingContribution", "NavigationContribution", "NavigationMarkers", "NavigationPlacements",
                    "PageSectionContribution", "QueueTypeContribution", "RequestOwnerIdentity",
                    "RequestOwnerIdentityResolver", "StartupRouteContext", "StartupRouteContribution",
                    "StaticResourceContribution", "TabContribution", "UserscriptContribution",
                    "WebRouteContribution", "WebUiSlotContribution")),
            Map.entry("插件自有 schema 声明", types(API_PREFIX + "schema",
                    "ColumnMigrationSpec", "ColumnSpec", "IndexOrigin", "IndexSpec", "PathColumnSpec",
                    "SchemaContribution", "TableSpec")),
            Map.entry("维护任务协议", types(API_PREFIX + "maintenance",
                    "MaintenanceContext", "MaintenanceProgressReporter", "MaintenanceTask")),
            Map.entry("计划任务协议", union(
                    types(API_PREFIX + "schedule.credential",
                            "ScheduledCredentialBindResult", "ScheduledCredentialContext",
                            "ScheduledCredentialHandle", "ScheduledCredentialPolicy",
                            "ScheduledCredentialProbeResult", "ScheduledCredentialRequirement"),
                    types(API_PREFIX + "schedule.execution",
                            "ScheduledCancellation", "ScheduledExecutionContext", "ScheduledExecutionException",
                            "ScheduledExecutionPlan", "ScheduledFailure"),
                    types(API_PREFIX + "schedule.guard",
                            "ScheduledExecutionGuard", "ScheduledGuardBinding", "ScheduledGuardContext",
                            "ScheduledGuardDecision", "ScheduledGuardEvidence", "ScheduledGuardPoint",
                            "ScheduledGuardResult"),
                    types(API_PREFIX + "schedule.network", "ScheduledNetworkRoute"),
                    types(API_PREFIX + "schedule.security",
                            "ScheduledCredentialText", "ScheduledSensitiveFieldNames"),
                    types(API_PREFIX + "schedule.source",
                            "ScheduledCheckpoint", "ScheduledDiscoveryResult", "ScheduledPendingReplayPolicy",
                            "ScheduledSourceContext", "ScheduledSourceDescriptor", "ScheduledSourceExecutor",
                            "ScheduledSourceFrontendContribution", "ScheduledSourcePresentation",
                            "ScheduledTaskDefinition", "ScheduledTaskDraft", "ScheduledTaskPresentation",
                            "ScheduledWorkSink"),
                    types(API_PREFIX + "schedule.work",
                            "ScheduledWork", "ScheduledWorkContext", "ScheduledWorkExecutor", "ScheduledWorkKey",
                            "ScheduledWorkPresentation", "ScheduledWorkRelation", "ScheduledWorkResult",
                            "ScheduledWorkRunContext", "ScheduledWorkRunStatistics"))),
            Map.entry("队列生命周期协议", types(API_PREFIX + "download.queue",
                    "QueueDrain", "QueueGenerationDrain", "QueueNotAcceptingException", "QueueOperations",
                    "QueueTaskTracker"))
    );

    private static final Map<String, Integer> APPROVED_TYPE_COUNTS = Map.of(
            "插件入口、版本与生命周期", 5,
            "GUI 纯数据 contribution", 30,
            "Web、下载描述与请求身份协议", 26,
            "插件自有 schema 声明", 7,
            "维护任务协议", 3,
            "计划任务协议", 42,
            "队列生命周期协议", 5
    );

    private static final Set<String> APPROVED_PUBLIC_NESTED_TYPES = Set.of(
            API_PREFIX + "download.queue.QueueTaskTracker$Task",
            API_PREFIX + "schedule.credential.ScheduledCredentialContext$Purpose",
            API_PREFIX + "schedule.credential.ScheduledCredentialProbeResult$Status",
            API_PREFIX + "schedule.execution.ScheduledFailure$Category",
            API_PREFIX + "schedule.guard.ScheduledGuardDecision$Action",
            API_PREFIX + "schedule.network.ScheduledNetworkRoute$Mode",
            API_PREFIX + "schedule.work.ScheduledWorkResult$Outcome"
    );

    @Test
    @DisplayName("每个生产类型都必须有稳定协议 owner")
    void everyProductionTypeHasAnExplicitProtocolOwner() {
        assertThat(APPROVED_TYPES_BY_OWNER.keySet()).containsExactlyInAnyOrderElementsOf(APPROVED_TYPE_COUNTS.keySet());
        APPROVED_TYPES_BY_OWNER.forEach((owner, types) ->
                assertThat(types).as(owner).hasSize(APPROVED_TYPE_COUNTS.get(owner)));

        Set<String> actualTopLevelTypes = new LinkedHashSet<>();
        CLASSES.stream()
                .map(javaClass -> javaClass.getName())
                .filter(name -> !name.contains("$"))
                .sorted()
                .forEach(actualTopLevelTypes::add);

        assertThat(actualTopLevelTypes)
                .as("新增 plugin-api 类型必须先证明它是稳定第三方插件协议")
                .containsExactlyInAnyOrderElementsOf(approvedTypes());

        Set<String> actualPublicNestedTypes = new LinkedHashSet<>();
        CLASSES.stream()
                .filter(javaClass -> javaClass.getName().contains("$"))
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .map(javaClass -> javaClass.getName())
                .sorted()
                .forEach(actualPublicNestedTypes::add);

        assertThat(actualPublicNestedTypes)
                .as("公开嵌套类型同样属于 Plugin API，必须逐项确认协议 owner")
                .containsExactlyInAnyOrderElementsOf(APPROVED_PUBLIC_NESTED_TYPES);
    }

    @Test
    @DisplayName("生产依赖只允许 JDK、本模块与身份解析接口的 Servlet 请求")
    void productionDependenciesStayInsideApprovedSurface() {
        List<String> violations = new ArrayList<>();
        Set<String> servletConsumers = new LinkedHashSet<>();

        CLASSES.forEach(javaClass -> javaClass.getDirectDependenciesFromSelf().forEach(dependency -> {
            String origin = dependency.getOriginClass().getName();
            String target = dependency.getTargetClass().getName();
            if (target.startsWith("jakarta.servlet.")) {
                servletConsumers.add(origin);
                if (!origin.equals(REQUEST_OWNER_RESOLVER)) {
                    violations.add(origin + " -> " + target + " (Servlet 例外不属于身份解析接口)");
                }
                return;
            }
            if (!isJdkOrPluginApi(target)) {
                violations.add(origin + " -> " + target);
            }
        }));

        assertThat(violations)
                .as("plugin-api 必须保持纯 JDK；Servlet 只允许 RequestOwnerIdentityResolver 的请求签名")
                .isEmpty();
        assertThat(servletConsumers).containsExactly(REQUEST_OWNER_RESOLVER);
    }

    @Test
    @DisplayName("SchemaContribution 只表达宿主盖章后的三类 schema 声明")
    void schemaContributionHasExactOwnerNeutralShape() {
        assertThat(SchemaContribution.class.isRecord()).isTrue();
        assertThat(Arrays.stream(SchemaContribution.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("tables", "columnMigrations", "pathColumns");
        assertThat(Arrays.stream(SchemaContribution.class.getRecordComponents())
                .map(component -> component.getType().getName()).toList())
                .containsExactly(List.class.getName(), List.class.getName(), List.class.getName());
        assertThat(Arrays.stream(SchemaContribution.class.getRecordComponents())
                .map(component -> component.getGenericType().getTypeName()).toList())
                .containsExactly(
                        listOf(TableSpec.class),
                        listOf(ColumnMigrationSpec.class),
                        listOf(PathColumnSpec.class)
                );
        assertThat(Arrays.stream(SchemaContribution.class.getDeclaredMethods())
                .map(method -> method.getName()).toList())
                .doesNotContain("ownerPluginId", "indexes");
    }

    @Test
    @DisplayName("内置 GUI 配置分组只暴露宿主或跨插件共享语义")
    void builtInGuiConfigGroupsHaveNeutralOwners() {
        assertThat(publicStringConstants(GuiConfigGroups.class))
                .as("单一官方插件私有的配置分组不得提升进 plugin-api")
                .containsExactlyInAnyOrderEntriesOf(APPROVED_GUI_CONFIG_GROUPS);
    }

    private static boolean isJdkOrPluginApi(String typeName) {
        return typeName.startsWith("java.")
                || typeName.startsWith(API_PREFIX)
                || typeName.startsWith("[")
                || PRIMITIVE_TYPE_NAMES.contains(typeName);
    }

    private static String listOf(Class<?> elementType) {
        return List.class.getName() + "<" + elementType.getName() + ">";
    }

    private static Map<String, String> publicStringConstants(Class<?> type) {
        Map<String, String> constants = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers)
                    || !Modifier.isStatic(modifiers)
                    || field.getType() != String.class) {
                continue;
            }
            try {
                constants.put(field.getName(), (String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read public GUI config group constant " + field.getName(), e);
            }
        }
        return Map.copyOf(constants);
    }

    private static Set<String> approvedTypes() {
        Set<String> approved = new LinkedHashSet<>();
        int declaredCount = 0;
        for (Set<String> ownerTypes : APPROVED_TYPES_BY_OWNER.values()) {
            declaredCount += ownerTypes.size();
            approved.addAll(ownerTypes);
        }
        if (approved.size() != declaredCount) {
            throw new IllegalStateException("plugin-api owner whitelist contains duplicate types");
        }
        return Set.copyOf(approved);
    }

    private static Set<String> types(String packageName, String... simpleNames) {
        Set<String> types = new LinkedHashSet<>();
        Arrays.stream(simpleNames).map(name -> packageName + "." + name).forEach(types::add);
        return Set.copyOf(types);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... groups) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(groups).forEach(result::addAll);
        return Set.copyOf(result);
    }
}
