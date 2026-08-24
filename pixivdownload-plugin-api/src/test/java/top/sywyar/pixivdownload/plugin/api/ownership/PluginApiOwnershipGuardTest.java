package top.sywyar.pixivdownload.plugin.api.ownership;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.notification.SurveyInboxMessage;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotCatalog;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plugin-api 所有权白名单。生产类型只有在表达稳定的第三方插件协议时才能进入本模块；
 * 仅满足纯 JDK、被当前宿主使用或服务已有持久数据迁移，不足以成为 Plugin API。
 */
@DisplayName("plugin-api 所有权边界")
class PluginApiOwnershipGuardTest {

    private static final String API_PREFIX = "top.sywyar.pixivdownload.plugin.api.";
    private static final String REQUEST_OWNER_RESOLVER = API_PREFIX + "web.RequestOwnerIdentityResolver";
    private static final String HTTP_SERVLET_REQUEST = HttpServletRequest.class.getName();
    private static final Pattern JAVADOC_BLOCK = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([\\w.]+);");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^\\s*import\\s+([\\w.$]+);");
    private static final Pattern JAVADOC_TYPE_REFERENCE = Pattern.compile(
            "\\{@(?:link|linkplain|value)\\s+([^\\s}]+)");
    private static final Pattern JAVADOC_SEE_REFERENCE = Pattern.compile(
            "@see\\s+([^\\s*]+)");
    private static final Pattern JAVADOC_CODE_CONTEXT = Pattern.compile(
            "\\{@code\\s+([^}]*)}");
    private static final Pattern EXPLICIT_TYPE_TOKEN = Pattern.compile(
            "(?<![\\p{Alnum}_$.])"
                    + "((?:[a-z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*)"
                    + "(?![\\p{Alnum}_$])");
    private static final Pattern PROJECT_TYPE_REFERENCE = Pattern.compile(
            "\\btop\\.sywyar\\.pixivdownload(?:\\.[A-Za-z_$][\\w$]*)+\\b");
    private static final Pattern SIMPLE_TYPE_REFERENCE = Pattern.compile(
            "(?<![\\p{Alnum}_$.])([A-Z][A-Za-z0-9_$]*)(?![\\p{Alnum}_$.])");
    private static final Pattern TEST_TYPE_REFERENCE = Pattern.compile(
            "(?<![\\p{Alnum}_$])([A-Z][A-Za-z0-9_$]*Test)(?![\\p{Alnum}_$])");
    private static final Pattern HOST_IMPLEMENTATION_REFERENCE = Pattern.compile(
            "(?<![\\p{Alnum}_$@])"
                    + "([A-Z][A-Za-z0-9_$]*(?:Registry|Controller|Filter|Service|Manager|Bridge|"
                    + "Plugin|Config|Policy))"
                    + "(?![\\p{Alnum}_$])");
    private static final Pattern SOURCE_TYPE_DECLARATION = Pattern.compile(
            "\\b(?:class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern CONCRETE_CREDENTIAL_NAME = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:"
                    + "php[_-]?sessid|wordpress[_-]?logged[_-]?in|rtfa|cf[_-]?clearance|"
                    + "ttwid|odin[_-]?tt|uid[_-]?tt|s[_-]?v[_-]?web[_-]?id|"
                    + "sessionid[_-]?ss|sessid[_-]?ss|sid[_-]?(?:guard|tt)"
                    + ")(?![A-Za-z0-9])");
    private static final Set<String> FORBIDDEN_DOCUMENTATION_REFERENCES = Set.of(
            "AuthFilter",
            "BUILT_IN",
            "DrilldownRegistry",
            "LandingRegistryTest",
            "NavigationController",
            "PageSectionRegistry",
            "PluginRegistry",
            "PluginSource",
            "RequiredPluginPolicy",
            "RouteAccessRegistry",
            "WebUiSlotRegistry"
    );
    private static final Set<String> PRIMITIVE_TYPE_NAMES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
    );
    private static final Set<String> APPROVED_POM_DEPENDENCIES = Set.of(
            "jakarta.servlet:jakarta.servlet-api:provided",
            "org.junit.jupiter:junit-jupiter:test",
            "org.assertj:assertj-core:test",
            "org.junit.platform:junit-platform-launcher:test",
            "com.tngtech.archunit:archunit:test"
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
            Map.entry("插件入口与生命周期", types(API_PREFIX + "plugin",
                    "PixivFeaturePlugin", "PixivPluginProvider", "PluginKind", "PluginManagedBean")),
            Map.entry("GUI contribution 与桌面宿主契约", union(
                    types(API_PREFIX + "gui",
                    "DesktopAutomationSnapshot", "DesktopAutomationSource", "DesktopAutomationTaskContribution",
                    "DesktopControlCenterAvailability", "DesktopDashboardCardContribution", "DesktopDashboardSnapshot",
                    "DesktopDashboardSource", "DesktopRunningTaskContribution",
                    "DesktopUiContext", "DesktopUiHost", "DesktopUiIcon", "DesktopUiPluginSnapshot",
                    "DesktopUiProvider", "DesktopUiSession", "DesktopUiText", "DesktopUiTone", "DesktopUiToolHost",
                    "GuiActionInvocationHeaders",
                    "GuiConfigActionContribution", "GuiConfigActionPayloadField", "GuiConfigActionPayloadType",
                    "GuiConfigActionResultArgument", "GuiConfigActionResultCondition",
                    "GuiConfigActionResultOperator", "GuiConfigActionResultRule", "GuiConfigActionResultSource",
                    "GuiConfigActionResultSummary", "GuiConfigCondition", "GuiConfigConditionOperator",
                    "GuiConfigContribution", "GuiConfigEffect", "GuiConfigFieldContribution", "GuiConfigFieldLayoutContribution",
                    "GuiConfigFieldType", "GuiConfigGroupContribution", "GuiConfigGroups",
                    "GuiConfigPresetContribution", "GuiConfigPresetMatchMode", "GuiConfigSectionContribution",
                    "GuiConfigSectionLayout", "GuiConfigSectionNoticeContribution",
                    "GuiConfigSectionNoticeStyle", "GuiOnboardingStepContribution", "GuiThemeAppearance",
                    "GuiThemeApplier", "GuiThemeChangeListener", "GuiThemeContribution",
                    "GuiThemeListenerFactory", "GuiThemeListenerSession", "RepositoryConfigEntry",
                    "TrustedKeyConfigEntry"))),
            Map.entry("Web 与请求身份协议", types(API_PREFIX + "web",
                    "AccessPolicy", "ApiErrorResponse", "Audience", "DrilldownContribution", "DrilldownPlacements",
                    "HttpMethod", "I18nContribution",
                    "LandingContribution", "NavigationContribution", "NavigationMarkers", "NavigationPlacements",
                    "PageSectionContribution", "RequestOwnerIdentity",
                    "RequestOwnerIdentityResolver", "StartupRouteContext", "StartupRouteContribution",
                    "StaticResourceContribution", "UserscriptContribution",
                    "WebRouteContribution", "WebUiSlotCatalog", "WebUiSlotContribution")),
            Map.entry("油猴脚本宿主目录协议", types(API_PREFIX + "userscript",
                    "UserscriptArtifact", "UserscriptCatalog")),
            Map.entry("下载类型描述协议", types(API_PREFIX + "download.type",
                    "DownloadAcquisitionMode", "DownloadTypeDescriptor")),
            Map.entry("下载宿主控制协议", types(API_PREFIX + "download.control",
                    "DownloadControlPlane", "DownloadExtensionIdentity", "DownloadExtensionSnapshot",
                    "DownloadQueueCancelCommand", "DownloadQueueCancelResult", "DownloadTypePublication",
                    "DownloadUiSlotPublication")),
            Map.entry("插件自有 schema 声明", types(API_PREFIX + "schema",
                    "ColumnMigrationSpec", "ColumnSpec", "IndexOrigin", "IndexSpec", "PathColumnSpec",
                    "SchemaContribution", "TableSpec")),
            Map.entry("维护任务协议", types(API_PREFIX + "maintenance",
                    "MaintenanceContext", "MaintenanceProgressReporter", "MaintenanceTask")),
            Map.entry("通知贡献协议", types(API_PREFIX + "notification",
                    "ImmutableNotificationTemplateCatalog", "NotificationTemplateCatalog",
                    "NotificationTemplateContribution", "NotificationTemplateContributor",
                    "SurveyInboxMessage")),
            Map.entry("插件推流生命周期协议", types(API_PREFIX + "stream",
                    "PluginStream", "PluginStreamRegistrar")),
            Map.entry("插件运行期后台任务协议", types(API_PREFIX + "task",
                    "PluginRuntimeTask", "PluginRuntimeTaskDrain", "PluginRuntimeTaskRegistrar",
                    "PluginRuntimeTaskRejectedException")),
            Map.entry("插件 owner-scoped 存储协议", types(API_PREFIX + "storage",
                    "PluginDataSource", "RuntimePathProvider")),
            Map.entry("出站 HTTP 传输协议", types(API_PREFIX + "http",
                    "OutboundHttpClient", "OutboundHttpClientFactory", "OutboundHttpClientProfile",
                    "OutboundHttpCookiePolicy", "OutboundHttpProxyProvider", "OutboundHttpRedirectPolicy",
                    "OutboundHttpRequest", "OutboundHttpResponse", "OutboundHttpRoute",
                    "OutboundHttpStreamResponse",
                    "OutboundHttpRoutePolicy", "OutboundHttpTransportException")),
            Map.entry("出站 WebSocket 传输协议", types(API_PREFIX + "http.websocket",
                    "OutboundWebSocketClient", "OutboundWebSocketClientFactory",
                    "OutboundWebSocketClientProfile", "OutboundWebSocketRequest")),
            Map.entry("计划任务协议", union(
                    types(API_PREFIX + "schedule.capability",
                            "ScheduleCapabilityAccess", "ScheduleCapabilityLease",
                            "ScheduleCapabilityOwner", "ScheduleCapabilityOwnerSnapshot",
                            "ScheduleCapabilitySnapshot", "ScheduleExecutionLease",
                            "SchedulePlanningLease"),
                    types(API_PREFIX + "schedule.credential",
                            "ScheduledCredentialAccountActionPlan",
                            "ScheduledCredentialAccountActionRequest",
                            "ScheduledCredentialAccountIncident", "ScheduledCredentialBindResult",
                            "ScheduledCredentialContext", "ScheduledCredentialHandle",
                            "ScheduledCredentialIncidentPresentation", "ScheduledCredentialPolicy",
                            "ScheduledCredentialProbeResult", "ScheduledCredentialRequirement",
                            "ScheduledCredentialTaskPresentation",
                            "ScheduledCredentialTaskSnapshot", "ScheduledCredentialTaskStateUpdate"),
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
                            "ScheduledWorkNotificationPresentation", "ScheduledWorkPresentation",
                            "ScheduledWorkRelation", "ScheduledWorkResult",
                            "ScheduledWorkRunContext", "ScheduledWorkRunStatistics"))),
            Map.entry("队列生命周期协议", types(API_PREFIX + "download.queue",
                    "QueueDrain", "QueueGenerationDrain", "QueueNotAcceptingException", "QueueOperations",
                    "QueueTaskTracker"))
    );

    private static final Map<String, Integer> APPROVED_TYPE_COUNTS = Map.ofEntries(
            Map.entry("插件入口与生命周期", 4),
            Map.entry("GUI contribution 与桌面宿主契约", 51),
            Map.entry("Web 与请求身份协议", 21),
            Map.entry("油猴脚本宿主目录协议", 2),
            Map.entry("下载类型描述协议", 2),
            Map.entry("下载宿主控制协议", 7),
            Map.entry("插件自有 schema 声明", 7),
            Map.entry("维护任务协议", 3),
            Map.entry("通知贡献协议", 5),
            Map.entry("插件推流生命周期协议", 2),
            Map.entry("插件运行期后台任务协议", 4),
            Map.entry("插件 owner-scoped 存储协议", 2),
            Map.entry("出站 HTTP 传输协议", 12),
            Map.entry("出站 WebSocket 传输协议", 4),
            Map.entry("计划任务协议", 57),
            Map.entry("队列生命周期协议", 5)
    );

    private static final Set<String> APPROVED_PUBLIC_NESTED_TYPES = Set.of(
            API_PREFIX + "gui.DesktopAutomationTaskContribution$LastResult",
            API_PREFIX + "gui.DesktopAutomationTaskContribution$Status",
            API_PREFIX + "gui.DesktopRunningTaskContribution$Status",
            API_PREFIX + "gui.DesktopUiHost$BackendSnapshot",
            API_PREFIX + "gui.DesktopUiHost$BackendState",
            API_PREFIX + "gui.DesktopUiHost$ConfigFile",
            API_PREFIX + "gui.DesktopUiHost$ConfigSnapshot",
            API_PREFIX + "gui.DesktopUiHost$CredentialSnapshot",
            API_PREFIX + "gui.DesktopUiHost$GuiBodyFormat",
            API_PREFIX + "gui.DesktopUiHost$GuiRequest",
            API_PREFIX + "gui.DesktopUiHost$GuiResponse",
            API_PREFIX + "gui.DesktopUiHost$GuiValue",
            API_PREFIX + "gui.DesktopUiHost$IoOperation",
            API_PREFIX + "gui.DesktopUiHost$OnboardingSnapshot",
            API_PREFIX + "gui.DesktopUiHost$RepositoryProxyPolicy",
            API_PREFIX + "gui.DesktopUiHost$UiLocale",
            API_PREFIX + "gui.DesktopUiHost$UiLocaleResolution",
            API_PREFIX + "gui.DesktopUiPluginSnapshot$Fingerprint",
            API_PREFIX + "gui.DesktopUiSession$MessageLevel",
            API_PREFIX + "gui.DesktopUiToolHost$BackfillOptions",
            API_PREFIX + "gui.DesktopUiToolHost$BackfillSummary",
            API_PREFIX + "gui.DesktopUiToolHost$DatabaseColumn",
            API_PREFIX + "gui.DesktopUiToolHost$FfmpegInstallStage",
            API_PREFIX + "gui.DesktopUiToolHost$FfmpegInstallation",
            API_PREFIX + "gui.DesktopUiToolHost$FfmpegProgressListener",
            API_PREFIX + "gui.DesktopUiToolHost$FfmpegProxy",
            API_PREFIX + "gui.DesktopUiToolHost$FfmpegSource",
            API_PREFIX + "gui.DesktopUiToolHost$FolderArtwork",
            API_PREFIX + "gui.DesktopUiToolHost$FolderCheckResult",
            API_PREFIX + "gui.DesktopUiToolHost$ImageClassifierArtwork",
            API_PREFIX + "gui.DesktopUiToolHost$ImageClassifierDeleteFailureHandler",
            API_PREFIX + "gui.DesktopUiToolHost$ImageClassifierServer",
            API_PREFIX + "gui.DesktopUiToolHost$ImageClassifierSettings",
            API_PREFIX + "gui.DesktopUiToolHost$ImageClassifierTarget",
            API_PREFIX + "gui.DesktopUiToolHost$MaintenanceSnapshot",
            API_PREFIX + "gui.DesktopUiToolHost$MigrationOptions",
            API_PREFIX + "gui.DesktopUiToolHost$MigrationSummary",
            API_PREFIX + "gui.DesktopUiToolHost$ToolHistoryEntry",
            API_PREFIX + "gui.DesktopUiToolHost$ToolId",
            API_PREFIX + "gui.DesktopUiToolHost$ToolLogSession",
            API_PREFIX + "gui.DesktopUiToolHost$ToolOutcome",
            API_PREFIX + "download.queue.QueueTaskTracker$Snapshot",
            API_PREFIX + "download.queue.QueueTaskTracker$Task",
            API_PREFIX + "web.ApiErrorResponse$Basic",
            API_PREFIX + "schedule.credential.ScheduledCredentialContext$Purpose",
            API_PREFIX + "schedule.credential.ScheduledCredentialProbeResult$Status",
            API_PREFIX + "schedule.execution.ScheduledFailure$Category",
            API_PREFIX + "schedule.guard.ScheduledGuardDecision$Action",
            API_PREFIX + "schedule.network.ScheduledNetworkRoute$Mode",
            API_PREFIX + "schedule.work.ScheduledWorkResult$Outcome"
    );

    private enum SourceState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHARACTER
    }

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
        assertThat(List.of("java.sql.Connection", "java.sql.DriverManager", "javax.sql.DataSource"))
                .as("JDBC 即使属于 JDK 命名空间也不得进入纯契约模块")
                .noneMatch(PluginApiOwnershipGuardTest::isJdkOrPluginApi);

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
                if (!target.equals(HTTP_SERVLET_REQUEST)) {
                    violations.add(origin + " -> " + target + " (Servlet 例外只允许 HttpServletRequest)");
                }
                return;
            }
            if (origin.equals(PluginDataSource.class.getName())
                    && target.equals(javax.sql.DataSource.class.getName())) {
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
    @DisplayName("Servlet 例外必须精确限制为身份解析方法的 HttpServletRequest 入参")
    void servletExceptionHasExactRequestParameterShape() {
        assertThat(Arrays.stream(RequestOwnerIdentityResolver.class.getDeclaredMethods()))
                .isNotEmpty()
                .allSatisfy(method -> {
                    assertThat(method.getParameterTypes())
                            .as(method.getName() + " 的参数")
                            .containsExactly(HttpServletRequest.class);
                    assertThat(method.getGenericReturnType().getTypeName())
                            .as(method.getName() + " 的返回类型")
                            .doesNotContain("jakarta.servlet.");
                    assertThat(Arrays.stream(method.getExceptionTypes()).map(Class::getName).toList())
                            .as(method.getName() + " 的异常类型")
                            .noneMatch(name -> name.startsWith("jakarta.servlet."));
                });

        assertThat(Arrays.stream(RequestOwnerIdentityResolver.class.getDeclaredFields())
                .map(Field::getGenericType)
                .map(type -> type.getTypeName())
                .toList())
                .as("Servlet 请求不得成为身份解析接口的字段状态")
                .noneMatch(type -> type.contains("jakarta.servlet."));
    }

    @Test
    @DisplayName("运行路径与私有数据库能力由宿主固定 owner")
    void storageCapabilitiesHaveExactOwnerScopedShape() {
        assertThat(Arrays.stream(RuntimePathProvider.class.getDeclaredMethods())
                .map(method -> method.getName()).toList())
                .containsExactlyInAnyOrder("configFile", "stateDirectory", "dataDirectory");
        assertThat(Arrays.stream(RuntimePathProvider.class.getDeclaredMethods()))
                .allSatisfy(method -> {
                    assertThat(method.getReturnType()).isEqualTo(Path.class);
                    if (method.getName().equals("configFile")) {
                        assertThat(method.getParameterTypes()).containsExactly(String.class);
                    } else {
                        assertThat(method.getParameterCount()).isZero();
                    }
                });
        assertThat(PluginDataSource.class.getInterfaces())
                .containsExactly(javax.sql.DataSource.class);
        assertThat(PluginDataSource.class.getDeclaredMethods()).isEmpty();
    }

    @Test
    @DisplayName("模块 POM 只允许 provided Servlet API 与既定测试依赖")
    void pomDependenciesHaveExactCoordinatesAndScopes() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(modulePom().toFile());
        List<String> dependencies = new ArrayList<>();
        var nodes = document.getElementsByTagName("dependency");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element dependency = (Element) nodes.item(index);
            String groupId = directChildText(dependency, "groupId");
            String artifactId = directChildText(dependency, "artifactId");
            String scope = directChildText(dependency, "scope");
            dependencies.add(groupId + ":" + artifactId + ":"
                    + (scope == null || scope.isBlank() ? "compile" : scope.trim()));
        }

        assertThat(dependencies)
                .as("plugin-api 不得通过未使用的 POM 依赖绕过字节码纯净守卫")
                .containsExactlyInAnyOrderElementsOf(APPROVED_POM_DEPENDENCIES);
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
    @DisplayName("插件贡献不携带宿主负责盖章的 owner 身份")
    void contributionsDoNotSelfReportHostStampedOwner() {
        List<Class<?>> ownerFreeContributions = List.of(
                StaticResourceContribution.class,
                UserscriptContribution.class,
                StartupRouteContribution.class,
                LandingContribution.class,
                PageSectionContribution.class,
                DrilldownContribution.class,
                WebUiSlotContribution.class,
                SurveyInboxMessage.class,
                GuiOnboardingStepContribution.class);

        ownerFreeContributions.forEach(type -> assertThat(Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName()).toList())
                .as(type.getSimpleName())
                .doesNotContain("pluginId", "ownerPluginId", "packageId", "generation", "publicationId"));
        assertThat(Arrays.stream(WebUiSlotContribution.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("slotId", "target", "moduleUrl", "order", "metadata");
        assertThat(WebUiSlotCatalog.class.getDeclaredMethods())
                .singleElement()
                .satisfies(method -> assertThat(method.getName()).isEqualTo("uiSlots"));
    }

    @Test
    @DisplayName("桌面业务上下文不暴露插件实例类加载器或宿主实现")
    void desktopUiContextHasStableBusinessSurface() {
        List<String> surfaceTypes = new ArrayList<>();
        Arrays.stream(DesktopUiContext.class.getDeclaredFields())
                .map(field -> field.getGenericType().getTypeName()).forEach(surfaceTypes::add);
        Arrays.stream(DesktopUiContext.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getGenericParameterTypes()))
                .map(type -> type.getTypeName()).forEach(surfaceTypes::add);
        Arrays.stream(DesktopUiContext.class.getDeclaredMethods()).forEach(method -> {
            surfaceTypes.add(method.getGenericReturnType().getTypeName());
            Arrays.stream(method.getGenericParameterTypes())
                    .map(type -> type.getTypeName()).forEach(surfaceTypes::add);
        });

        assertThat(surfaceTypes).allSatisfy(type -> assertThat(type)
                .doesNotContain("PixivFeaturePlugin", "PluginSource", "ClassLoader", "org.pf4j"));
    }

    @Test
    @DisplayName("内置 GUI 配置分组只暴露宿主或跨插件共享语义")
    void builtInGuiConfigGroupsHaveNeutralOwners() {
        assertThat(publicStringConstants(GuiConfigGroups.class))
                .as("单一官方插件私有的配置分组不得提升进 plugin-api")
                .containsExactlyInAnyOrderEntriesOf(APPROVED_GUI_CONFIG_GROUPS);
    }

    @Test
    @DisplayName("生产 Javadoc 只引用本模块稳定契约")
    void productionDocumentationReferencesOnlyPluginApiContracts() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Path sourceRoot = repositoryRoot.resolve("pixivdownload-plugin-api/src/main/java");
        Set<String> localTypes = pluginApiSimpleTypeNames();
        Set<String> localQualifiedTypes = pluginApiQualifiedTypeNames();
        Set<String> localPackages = pluginApiPackageNames();
        Set<String> privateLocalTypes = pluginApiPrivateTypeNames();
        Set<String> externalTypes = externalProjectTypeNames(repositoryRoot);
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                violations.addAll(documentationOwnershipViolations(
                        repositoryRoot.relativize(source).toString(),
                        Files.readString(source, StandardCharsets.UTF_8),
                        localTypes,
                        localQualifiedTypes,
                        localPackages,
                        privateLocalTypes,
                        externalTypes));
            }
        }

        assertThat(violations)
                .as("plugin-api 文档不得引用宿主实现、其它模块类型、失效项目类型或测试类")
                .isEmpty();
    }

    @Test
    @DisplayName("生产契约不得固化具体来源或框架的凭据字段名")
    void productionContractsDoNotOwnConcreteCredentialNames() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Path sourceRoot = repositoryRoot.resolve("pixivdownload-plugin-api/src/main/java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                Matcher matcher = CONCRETE_CREDENTIAL_NAME.matcher(
                        Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    violations.add(repositoryRoot.relativize(source)
                            + " -> " + matcher.group());
                }
            }
        }

        assertThat(violations)
                .as("具体凭据字段名必须留在所属插件，plugin-api 只表达通用敏感语义")
                .isEmpty();
    }

    @Test
    @DisplayName("具体凭据名守卫不误伤跨页面 placement 与普通工程文本")
    void concreteCredentialNameScannerHasExactBoundary() {
        assertThat(List.of(
                "PHPSESSID",
                "wordpress_logged_in",
                "rtFa",
                "cf_clearance",
                "ttwid",
                "odin_tt",
                "uid_tt",
                "s_v_web_id",
                "sessionid_ss",
                "sid_guard",
                "sid_tt"))
                .allMatch(value -> CONCRETE_CREDENTIAL_NAME.matcher(value).find());
        assertThat(List.of(
                "gallery.sidebar",
                "novel.sidebar",
                "stats.top-authors",
                "duplicates.header-icons",
                "notification",
                "ai",
                "transport failure"))
                .noneMatch(value -> CONCRETE_CREDENTIAL_NAME.matcher(value).find());
    }

    @Test
    @DisplayName("文档所有权扫描精确识别宿主、外模块与测试类型")
    void documentationOwnershipScannerHasExactBoundary() {
        Set<String> localTypes = Set.of("AccessPolicy");
        Set<String> localQualifiedTypes = Set.of(
                "top.sywyar.pixivdownload.plugin.api.web.AccessPolicy");
        Set<String> localPackages = Set.of(
                "top.sywyar.pixivdownload.plugin.api",
                "top.sywyar.pixivdownload.plugin.api.web");
        Set<String> privateLocalTypes = Set.of("State");
        Set<String> externalTypes = Set.of("Entry", "MailConfig", "RegisteredPlugin");
        String rejected = """
                package fixture;

                import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
                import java.util.List;

                /**
                 * AuthFilter / @see MailConfig / RegisteredPlugin
                 * {@code new PluginRegistry()}
                 * {@link top.sywyar.pixivdownload.app.plugin.PluginRegistry}
                 * {@link MissingContract}
                 * {@link top.sywyar.pixivdownload.plugin.api.web.MissingContract}
                 * {@link top.sywyar.pixivdownload.plugin.api.web.AccessPolicy.MissingNested}
                 * {@link AccessPolicy.MissingNested}
                 * {@code AccessPolicy.MissingNested}
                 * {@code new AccessPolicy.MissingNested()}
                 * {@link Entry}
                 * {@code new Entry()}
                 * {@code List<Entry>}
                 * {@link java.util.List<Entry>}
                 * {@link java.util.List<MissingNestedContract>}
                 * {@code BUILT_IN}
                 * {@code State}
                 * 由 LandingRegistryTest 覆盖。
                 */
                final class RejectedFixture {
                }
                """;

        assertThat(documentationOwnershipViolations(
                "RejectedFixture.java",
                rejected,
                localTypes,
                localQualifiedTypes,
                localPackages,
                privateLocalTypes,
                externalTypes))
                .anyMatch(violation -> violation.contains("AuthFilter"))
                .anyMatch(violation -> violation.contains("MailConfig"))
                .anyMatch(violation -> violation.contains("RegisteredPlugin"))
                .anyMatch(violation -> violation.contains(
                        "top.sywyar.pixivdownload.app.plugin.PluginRegistry"))
                .anyMatch(violation -> violation.endsWith("unapproved type MissingContract"))
                .anyMatch(violation -> violation.contains(
                        "top.sywyar.pixivdownload.plugin.api.web.MissingContract"))
                .anyMatch(violation -> violation.contains(
                        "top.sywyar.pixivdownload.plugin.api.web.AccessPolicy.MissingNested"))
                .anyMatch(violation -> violation.endsWith(
                        "unapproved type AccessPolicy.MissingNested"))
                .anyMatch(violation -> violation.endsWith(
                        "unapproved code type AccessPolicy.MissingNested"))
                .anyMatch(violation -> violation.endsWith("unapproved type Entry"))
                .anyMatch(violation -> violation.endsWith("unapproved code type Entry"))
                .anyMatch(violation -> violation.endsWith("unapproved explicit type Entry"))
                .anyMatch(violation -> violation.endsWith(
                        "unapproved explicit type MissingNestedContract"))
                .anyMatch(violation -> violation.contains("BUILT_IN"))
                .anyMatch(violation -> violation.contains("State"))
                .anyMatch(violation -> violation.contains("LandingRegistryTest"));

        String allowed = """
                package fixture;

                import java.util.Map.Entry;
                import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

                /**
                 * {@link AccessPolicy}
                 * {@link top.sywyar.pixivdownload.plugin.api.web.AccessPolicy}
                 * {@link Entry}
                 * {@link Thread.State}
                 * {@code Entry} / {@code new Entry()} / {@code Thread.State}
                 * {@code java.util.List} / {@code @Service}
                 */
                final class AllowedFixture {
                    String text = "top.sywyar.pixivdownload.app.AuthFilter LandingRegistryTest";
                }
                """;
        assertThat(documentationOwnershipViolations(
                "AllowedFixture.java",
                allowed,
                localTypes,
                localQualifiedTypes,
                localPackages,
                privateLocalTypes,
                externalTypes)).isEmpty();

        String poisonedImport = String.join("\n",
                "package fixture;",
                "final class PoisonedImportFixture {",
                "    String text = \"\"\"",
                "import java.util.Map.Entry;",
                "\"\"\";",
                "    /** {@link Entry} */",
                "    static final class Nested {",
                "    }",
                "}");
        assertThat(documentationOwnershipViolations(
                "PoisonedImportFixture.java",
                poisonedImport,
                localTypes,
                localQualifiedTypes,
                localPackages,
                privateLocalTypes,
                externalTypes))
                .anyMatch(violation -> violation.endsWith("unapproved type Entry"));

        String fakeJavadoc = String.join("\n",
                "package fixture;",
                "final class FakeJavadocFixture {",
                "    String text = \"\"\"",
                "/** {@link Entry} */",
                "\"\"\";",
                "}");
        assertThat(documentationOwnershipViolations(
                "FakeJavadocFixture.java",
                fakeJavadoc,
                localTypes,
                localQualifiedTypes,
                localPackages,
                privateLocalTypes,
                externalTypes)).isEmpty();

        String declarations = """
                package example;
                // record CommentOnly() {}
                final class Container {
                    interface RegisteredPlugin {
                    }
                    String ignored = "enum StringOnly { VALUE }";
                }
                """;
        assertThat(declaredTypeNames(declarations))
                .containsExactlyInAnyOrder("Container", "RegisteredPlugin");
    }

    private static boolean isJdkOrPluginApi(String typeName) {
        if (typeName.startsWith("java.sql.") || typeName.startsWith("javax.sql.")) {
            return false;
        }
        return typeName.startsWith("java.")
                || typeName.startsWith(API_PREFIX)
                || typeName.startsWith("[")
                || PRIMITIVE_TYPE_NAMES.contains(typeName);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path nested = current.resolve("pixivdownload-plugin-api").resolve("pom.xml");
            if (Files.isRegularFile(nested) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            if (current.getFileName() != null
                    && current.getFileName().toString().equals("pixivdownload-plugin-api")) {
                Path direct = current.resolve("pom.xml");
                if (Files.isRegularFile(direct) && current.getParent() != null) {
                    return current.getParent();
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    private static Path modulePom() {
        return repositoryRoot().resolve("pixivdownload-plugin-api/pom.xml");
    }

    private static String directChildText(Element parent, String childName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && childName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
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

    private static List<String> documentationOwnershipViolations(String sourceName,
                                                                 String source,
                                                                 Set<String> localTypes,
                                                                 Set<String> localQualifiedTypes,
                                                                 Set<String> localPackages,
                                                                 Set<String> privateLocalTypes,
                                                                 Set<String> externalTypes) {
        String packageName = requiredPackageName(source);
        Map<String, String> imports = importedTypes(source);
        Set<String> violations = new LinkedHashSet<>();

        Matcher blocks = JAVADOC_BLOCK.matcher(commentOnlyText(source));
        while (blocks.find()) {
            String javadoc = blocks.group(1);
            int offset = blocks.start(1);

            Matcher typeReference = JAVADOC_TYPE_REFERENCE.matcher(javadoc);
            while (typeReference.find()) {
                String reference = typeReference.group(1);
                if (!isAllowedTypeReference(
                        reference, packageName, imports, localQualifiedTypes)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + typeReference.start(1),
                            "unapproved type",
                            reference));
                }
                collectKnownTypeTokenViolations(
                        sourceName,
                        source,
                        reference,
                        offset + typeReference.start(1),
                        packageName,
                        imports,
                        localTypes,
                        localQualifiedTypes,
                        privateLocalTypes,
                        externalTypes,
                        false,
                        "unapproved explicit type",
                        violations);
            }

            Matcher seeReference = JAVADOC_SEE_REFERENCE.matcher(javadoc);
            while (seeReference.find()) {
                String reference = seeReference.group(1);
                if (!isAllowedTypeReference(
                        reference, packageName, imports, localQualifiedTypes)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + seeReference.start(1),
                            "unapproved @see type",
                            reference));
                }
                collectKnownTypeTokenViolations(
                        sourceName,
                        source,
                        reference,
                        offset + seeReference.start(1),
                        packageName,
                        imports,
                        localTypes,
                        localQualifiedTypes,
                        privateLocalTypes,
                        externalTypes,
                        false,
                        "unapproved explicit type",
                        violations);
            }

            Matcher codeContext = JAVADOC_CODE_CONTEXT.matcher(javadoc);
            while (codeContext.find()) {
                collectKnownTypeTokenViolations(
                        sourceName,
                        source,
                        codeContext.group(1),
                        offset + codeContext.start(1),
                        packageName,
                        imports,
                        localTypes,
                        localQualifiedTypes,
                        privateLocalTypes,
                        externalTypes,
                        true,
                        "unapproved code type",
                        violations);
            }

            Matcher projectType = PROJECT_TYPE_REFERENCE.matcher(javadoc);
            while (projectType.find()) {
                String reference = projectType.group();
                if (!isApprovedProjectReference(reference, localQualifiedTypes, localPackages)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + projectType.start(),
                            "unapproved project reference",
                            reference));
                }
            }

            Matcher testType = TEST_TYPE_REFERENCE.matcher(javadoc);
            while (testType.find()) {
                violations.add(documentationViolation(
                        sourceName,
                        source,
                        offset + testType.start(1),
                        "test type",
                        testType.group(1)));
            }

            Matcher hostImplementation = HOST_IMPLEMENTATION_REFERENCE.matcher(javadoc);
            while (hostImplementation.find()) {
                String reference = hostImplementation.group(1);
                if (!isAllowedTypeReference(
                        reference, packageName, imports, localQualifiedTypes)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + hostImplementation.start(1),
                            "host implementation name",
                            reference));
                }
            }

            Matcher simpleType = SIMPLE_TYPE_REFERENCE.matcher(javadoc);
            while (simpleType.find()) {
                String reference = simpleType.group(1);
                if (FORBIDDEN_DOCUMENTATION_REFERENCES.contains(reference)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + simpleType.start(1),
                            "forbidden host reference",
                            reference));
                } else if (isAllowedTypeReference(
                        reference, packageName, imports, localQualifiedTypes)) {
                    continue;
                } else if (privateLocalTypes.contains(reference)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + simpleType.start(1),
                            "private plugin-api type",
                            reference));
                } else if (!localTypes.contains(reference)
                        && externalTypes.contains(reference)
                        && isMultiwordTypeName(reference)) {
                    violations.add(documentationViolation(
                            sourceName,
                            source,
                            offset + simpleType.start(1),
                            "external project type",
                            reference));
                }
            }
        }

        return List.copyOf(violations);
    }

    private static void collectKnownTypeTokenViolations(String sourceName,
                                                        String source,
                                                        String context,
                                                        int contextOffset,
                                                        String packageName,
                                                        Map<String, String> imports,
                                                        Set<String> localTypes,
                                                        Set<String> localQualifiedTypes,
                                                        Set<String> privateLocalTypes,
                                                        Set<String> externalTypes,
                                                        boolean requireKnownType,
                                                        String category,
                                                        Set<String> violations) {
        Matcher typeToken = EXPLICIT_TYPE_TOKEN.matcher(context);
        while (typeToken.find()) {
            String reference = typeToken.group(1);
            if (looksLikeTypeReference(reference)
                    && (!requireKnownType || isKnownCodeTypeReference(
                            reference, imports, localTypes, privateLocalTypes, externalTypes))
                    && !isAllowedTypeReference(
                            reference, packageName, imports, localQualifiedTypes)) {
                violations.add(documentationViolation(
                        sourceName,
                        source,
                        contextOffset + typeToken.start(1),
                        category,
                        reference));
            }
        }
    }

    private static boolean isAllowedTypeReference(String rawReference,
                                                  String packageName,
                                                  Map<String, String> imports,
                                                  Set<String> localQualifiedTypes) {
        if (rawReference.startsWith("#")) {
            return true;
        }
        String reference = rawReference.trim();
        int memberSeparator = reference.indexOf('#');
        if (memberSeparator >= 0) {
            reference = reference.substring(0, memberSeparator);
        }
        int genericSeparator = reference.indexOf('<');
        if (genericSeparator >= 0) {
            reference = reference.substring(0, genericSeparator);
        }
        while (reference.endsWith("[]")) {
            reference = reference.substring(0, reference.length() - 2);
        }
        if (reference.isBlank()) {
            return false;
        }
        if (reference.startsWith("java.")) {
            return isLoadableJdkType(reference);
        }
        if (reference.startsWith("jakarta.")) {
            return reference.equals(HTTP_SERVLET_REQUEST);
        }
        if (reference.startsWith("top.sywyar.pixivdownload.")) {
            return isApprovedTypeReference(reference, localQualifiedTypes);
        }

        String rootSimpleName = reference.contains(".")
                ? reference.substring(0, reference.indexOf('.'))
                : reference;
        String imported = imports.get(rootSimpleName);
        if (imported != null) {
            String resolved = imported + reference.substring(rootSimpleName.length());
            if (resolved.startsWith("java.")) {
                return isLoadableJdkType(resolved);
            }
            if (resolved.equals(HTTP_SERVLET_REQUEST)) {
                return true;
            }
            return isApprovedTypeReference(resolved, localQualifiedTypes);
        }

        if (isApprovedTypeReference(packageName + "." + reference, localQualifiedTypes)) {
            return true;
        }
        if (approvedSimpleTypeReferences(localQualifiedTypes).contains(reference)) {
            return true;
        }
        return isLoadableJdkType("java.lang." + reference);
    }

    private static boolean isKnownCodeTypeReference(String reference,
                                                    Map<String, String> imports,
                                                    Set<String> localTypes,
                                                    Set<String> privateLocalTypes,
                                                    Set<String> externalTypes) {
        if (reference.startsWith("java.")
                || reference.startsWith("jakarta.")
                || reference.startsWith("top.sywyar.pixivdownload.")) {
            return true;
        }
        String rootSimpleName = reference.contains(".")
                ? reference.substring(0, reference.indexOf('.'))
                : reference;
        return imports.containsKey(rootSimpleName)
                || localTypes.contains(rootSimpleName)
                || privateLocalTypes.contains(rootSimpleName)
                || externalTypes.contains(rootSimpleName)
                || FORBIDDEN_DOCUMENTATION_REFERENCES.contains(rootSimpleName);
    }

    private static Set<String> approvedSimpleTypeReferences(Set<String> localQualifiedTypes) {
        Set<String> references = new LinkedHashSet<>();
        for (String qualifiedType : localQualifiedTypes) {
            String sourceName = qualifiedType.replace('$', '.');
            String[] segments = sourceName.split("\\.");
            for (int index = 0; index < segments.length; index++) {
                if (!segments[index].isBlank()
                        && Character.isUpperCase(segments[index].charAt(0))) {
                    references.add(String.join(".", Arrays.copyOfRange(
                            segments, index, segments.length)));
                    references.add(segments[segments.length - 1]);
                    break;
                }
            }
        }
        return Set.copyOf(references);
    }

    private static boolean isApprovedProjectReference(String reference,
                                                      Set<String> localQualifiedTypes,
                                                      Set<String> localPackages) {
        return isApprovedTypeReference(reference, localQualifiedTypes)
                || localPackages.contains(reference);
    }

    private static boolean isApprovedTypeReference(String reference,
                                                   Set<String> localQualifiedTypes) {
        return localQualifiedTypes.contains(reference)
                || localQualifiedTypes.contains(reference.replace('$', '.'));
    }

    private static boolean isLoadableJdkType(String reference) {
        if (!reference.startsWith("java.")) {
            return false;
        }
        String candidate = reference;
        while (true) {
            try {
                Class.forName(candidate, false, PluginApiOwnershipGuardTest.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
                int separator = candidate.lastIndexOf('.');
                if (separator < "java.".length()) {
                    return false;
                }
                candidate = candidate.substring(0, separator)
                        + '$'
                        + candidate.substring(separator + 1);
            }
        }
    }

    private static boolean looksLikeTypeReference(String reference) {
        String leaf = reference.substring(reference.lastIndexOf('.') + 1);
        return !leaf.equals(leaf.toUpperCase(Locale.ROOT));
    }

    private static String requiredPackageName(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        if (!matcher.find()) {
            throw new IllegalArgumentException("source fixture has no package declaration");
        }
        return matcher.group(1);
    }

    private static Map<String, String> importedTypes(String source) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher matcher = IMPORT_DECLARATION.matcher(maskCommentsAndLiterals(source));
        while (matcher.find()) {
            String imported = matcher.group(1);
            imports.put(simpleTypeName(imported), imported);
        }
        return Map.copyOf(imports);
    }

    private static String documentationViolation(String sourceName,
                                                 String source,
                                                 int offset,
                                                 String category,
                                                 String reference) {
        long line = source.substring(0, offset).chars().filter(value -> value == '\n').count() + 1;
        return sourceName + ":" + line + " -> " + category + " " + reference;
    }

    private static Set<String> pluginApiSimpleTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        approvedTypes().stream().map(PluginApiOwnershipGuardTest::simpleTypeName).forEach(names::add);
        APPROVED_PUBLIC_NESTED_TYPES.stream()
                .map(PluginApiOwnershipGuardTest::simpleTypeName)
                .forEach(names::add);
        return Set.copyOf(names);
    }

    private static Set<String> pluginApiQualifiedTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(approvedTypes());
        APPROVED_PUBLIC_NESTED_TYPES.forEach(name -> {
            names.add(name);
            names.add(name.replace('$', '.'));
        });
        return Set.copyOf(names);
    }

    private static Set<String> pluginApiPrivateTypeNames() {
        Set<String> stableTypes = pluginApiSimpleTypeNames();
        Set<String> privateTypes = new LinkedHashSet<>();
        CLASSES.stream()
                .map(javaClass -> simpleTypeName(javaClass.getName()))
                .filter(name -> !name.isBlank() && Character.isUpperCase(name.charAt(0)))
                .filter(name -> !stableTypes.contains(name))
                .forEach(privateTypes::add);
        return Set.copyOf(privateTypes);
    }

    private static Set<String> pluginApiPackageNames() {
        Set<String> packages = new LinkedHashSet<>();
        for (String typeName : approvedTypes()) {
            String packageName = typeName.substring(0, typeName.lastIndexOf('.'));
            while (packageName.startsWith("top.sywyar.pixivdownload.plugin.api")) {
                packages.add(packageName);
                int separator = packageName.lastIndexOf('.');
                if (separator < 0) {
                    break;
                }
                packageName = packageName.substring(0, separator);
            }
        }
        return Set.copyOf(packages);
    }

    private static Set<String> externalProjectTypeNames(Path repositoryRoot) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> modules = Files.list(repositoryRoot)) {
            for (Path module : modules
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("pixivdownload-"))
                    .filter(path -> !path.getFileName().toString().equals("pixivdownload-plugin-api"))
                    .sorted()
                    .toList()) {
                collectJavaFileTypeNames(module.resolve("src/main/java"), names);
                collectJavaFileTypeNames(module.resolve("src/test/java"), names);
            }
        }
        return Set.copyOf(names);
    }

    private static void collectJavaFileTypeNames(Path sourceRoot, Set<String> names) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java")
                            && !path.getFileName().toString().equals("module-info.java"))
                    .sorted()
                    .toList()) {
                Set<String> declaredTypes = declaredTypeNames(
                        Files.readString(source, StandardCharsets.UTF_8));
                String primaryType = source.getFileName().toString()
                        .substring(0, source.getFileName().toString().length() - ".java".length());
                if (!declaredTypes.contains(primaryType)) {
                    throw new IllegalStateException("cannot derive project type from " + source);
                }
                names.addAll(declaredTypes);
            }
        }
    }

    private static Set<String> declaredTypeNames(String source) {
        Matcher declarations = SOURCE_TYPE_DECLARATION.matcher(maskCommentsAndLiterals(source));
        Set<String> names = new LinkedHashSet<>();
        while (declarations.find()) {
            names.add(declarations.group(1));
        }
        return Set.copyOf(names);
    }

    private static String simpleTypeName(String reference) {
        int packageSeparator = reference.lastIndexOf('.');
        int nestedSeparator = reference.lastIndexOf('$');
        return reference.substring(Math.max(packageSeparator, nestedSeparator) + 1);
    }

    private static boolean isMultiwordTypeName(String reference) {
        return reference.chars().filter(Character::isUpperCase).count() >= 2;
    }

    private static String commentOnlyText(String source) {
        String sourceWithoutComments = sanitizeSource(source, true);
        StringBuilder comments = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char original = source.charAt(index);
            if (original != sourceWithoutComments.charAt(index)) {
                comments.append(original);
            } else {
                appendMasked(comments, original);
            }
        }
        return comments.toString();
    }

    private static String maskCommentsAndLiterals(String source) {
        return sanitizeSource(source, false);
    }

    private static String sanitizeSource(String source, boolean preserveLiterals) {
        StringBuilder sanitized = new StringBuilder(source.length());
        SourceState state = SourceState.CODE;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            switch (state) {
                case CODE -> {
                    if (current == '/' && index + 1 < source.length()) {
                        char next = source.charAt(index + 1);
                        if (next == '/' || next == '*') {
                            appendMasked(sanitized, current);
                            appendMasked(sanitized, next);
                            index += 2;
                            state = next == '/' ? SourceState.LINE_COMMENT : SourceState.BLOCK_COMMENT;
                            continue;
                        }
                    }
                    if (startsWithTripleQuote(source, index)) {
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        index += 3;
                        state = SourceState.TEXT_BLOCK;
                        continue;
                    }
                    if (current == '"' || current == '\'') {
                        appendLiteral(sanitized, current, preserveLiterals);
                    } else {
                        sanitized.append(current);
                    }
                    index++;
                    if (current == '"') {
                        state = SourceState.STRING;
                    } else if (current == '\'') {
                        state = SourceState.CHARACTER;
                    }
                }
                case LINE_COMMENT -> {
                    appendMasked(sanitized, current);
                    index++;
                    if (current == '\n' || current == '\r') {
                        state = SourceState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && index + 1 < source.length()
                            && source.charAt(index + 1) == '/') {
                        appendMasked(sanitized, current);
                        appendMasked(sanitized, '/');
                        index += 2;
                        state = SourceState.CODE;
                    } else {
                        appendMasked(sanitized, current);
                        index++;
                    }
                }
                case STRING -> {
                    appendLiteral(sanitized, current, preserveLiterals);
                    index++;
                    if (current == '\\' && index < source.length()) {
                        appendLiteral(sanitized, source.charAt(index++), preserveLiterals);
                    } else if (current == '"') {
                        state = SourceState.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (startsWithTripleQuote(source, index)) {
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        index += 3;
                        state = SourceState.CODE;
                    } else {
                        appendLiteral(sanitized, current, preserveLiterals);
                        index++;
                        if (current == '\\' && index < source.length()) {
                            appendLiteral(sanitized, source.charAt(index++), preserveLiterals);
                        }
                    }
                }
                case CHARACTER -> {
                    appendLiteral(sanitized, current, preserveLiterals);
                    index++;
                    if (current == '\\' && index < source.length()) {
                        appendLiteral(sanitized, source.charAt(index++), preserveLiterals);
                    } else if (current == '\'') {
                        state = SourceState.CODE;
                    }
                }
            }
        }
        return sanitized.toString();
    }

    private static void appendLiteral(StringBuilder output,
                                      char value,
                                      boolean preserveLiterals) {
        if (preserveLiterals) {
            output.append(value);
        } else {
            appendMasked(output, value);
        }
    }

    private static boolean startsWithTripleQuote(String source, int index) {
        return index + 2 < source.length()
                && source.charAt(index) == '"'
                && source.charAt(index + 1) == '"'
                && source.charAt(index + 2) == '"';
    }

    private static void appendMasked(StringBuilder output, char value) {
        output.append(value == '\n' || value == '\r' ? value : ' ');
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
