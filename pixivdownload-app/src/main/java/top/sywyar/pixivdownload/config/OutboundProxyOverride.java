package top.sywyar.pixivdownload.config;

import org.apache.hc.core5.http.HttpHost;

import java.util.regex.Pattern;

/**
 * 线程级出站代理覆盖（{@code host:port}）。
 *
 * <p>供计划任务的「任务级单独代理」使用：{@code ScheduleExecutor} 在一轮运行的两类执行线程上
 * （调度主线程的发现 / 元数据 / 站内信检测；下载池线程上的阻塞下载）以 try/finally 包裹
 * {@link #set} / {@link #clear}，{@code RestTemplateConfig.DynamicProxyRoutePlanner} 在确定路由时
 * <b>优先</b>读取本覆盖——存在覆盖时无论全局 {@code proxy.enabled} 与否都改走覆盖代理。
 *
 * <p>覆盖只对设置它的线程生效；HttpClient 的连接池按路由（含代理）区分连接，不会串用。
 * 使用方必须保证 {@code finally} 清除，避免线程池线程残留污染后续无关请求。
 */
public final class OutboundProxyOverride {

    /**
     * 独立保存“是否已覆盖”和代理值：活动覆盖中的 {@code null} 明确表示直连，
     * 未设置覆盖才允许路由规划器回退到全局代理。
     */
    private static final ThreadLocal<RouteOverride> OVERRIDE = new ThreadLocal<>();

    private record RouteOverride(HttpHost proxy) {
    }

    /**
     * host 段允许的字符：主机名 / IPv4（字母、数字、{@code .}、{@code -}、{@code _}）。
     * 借此一并拒绝带 scheme（{@code http://…}，含 {@code /} 与额外 {@code :}）、用户名密码
     * （{@code user:pass@…}，含 {@code @} 与额外 {@code :}）、路径（{@code …/path}）、内嵌空白、
     * IPv6（含 {@code :}）等「非纯 host:port」形式。
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private OutboundProxyOverride() {
    }

    /**
     * 为当前线程设置代理覆盖；{@code null} / 空白 / 无法解析的值等同于不设置。
     * 解析规则与 {@link #parse} 一致（{@code host:port}，HTTP 代理）。
     */
    public static void set(String hostPort) {
        HttpHost host = parse(hostPort);
        if (host == null) {
            OVERRIDE.remove();
        } else {
            OVERRIDE.set(new RouteOverride(host));
        }
    }

    /** 为当前线程设置显式直连覆盖；即使全局代理已启用也不会使用代理。 */
    public static void setDirect() {
        OVERRIDE.set(new RouteOverride(null));
    }

    /** 清除当前线程的代理覆盖（必须在 finally 中调用）。 */
    public static void clear() {
        OVERRIDE.remove();
    }

    /**
     * 在当前线程上以任务级代理覆盖运行 {@code task}，结束（含异常）后<b>必定清除</b>覆盖——任务级上下文清理契约的
     * 统一入口。{@code hostPort} 为 {@code null} / 空白 / 非法时等同不设置（仍保证 finally 清除）。
     *
     * <p>下载 / 计划任务线程池是共享池：任何在池线程上套用代理覆盖跑插件 / 计划任务的代码都应经本方法，避免忘记
     * {@code finally clear()} 导致覆盖残留、污染后续无关请求（跨插件 / 跨任务上下文串用）。本覆盖只承载传输级
     * {@link HttpHost}，不持有任何插件类型引用。
     */
    public static void runScoped(String hostPort, Runnable task) {
        set(hostPort);
        try {
            task.run();
        } finally {
            clear();
        }
    }

    /** 在当前线程上显式直连运行任务，并在结束时清除覆盖。 */
    public static void runDirectScoped(Runnable task) {
        setDirect();
        try {
            task.run();
        } finally {
            clear();
        }
    }

    /** 当前线程是否存在覆盖；活动覆盖的代理值可以为 {@code null}（显式直连）。 */
    public static boolean isActive() {
        return OVERRIDE.get() != null;
    }

    /** 当前线程的代理覆盖；未设置返回 {@code null}。 */
    public static HttpHost current() {
        RouteOverride override = OVERRIDE.get();
        return override == null ? null : override.proxy();
    }

    /**
     * 把<b>严格</b> {@code host:port} 解析为 HTTP 代理 {@link HttpHost}；
     * {@code null} / 空白 / 缺少端口 / 端口非法（不在 1-65535）返回 {@code null}。
     *
     * <p>host 段必须为纯主机名 / IPv4（{@link #HOST_PATTERN}）：带 scheme（{@code http://127.0.0.1:7890}）、
     * 用户名密码（{@code user:pass@host:7890}）、路径、空白或 IPv6 等形式一律拒绝——它们虽能被「最后一个
     * 冒号」切出貌似合法的 host，却会在运行时被当作错误主机名解析、连接失败。
     */
    public static HttpHost parse(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return null;
        }
        String trimmed = hostPort.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String host = trimmed.substring(0, colon);
        if (!HOST_PATTERN.matcher(host).matches()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new HttpHost("http", host, port);
    }
}
