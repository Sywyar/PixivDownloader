package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划任务「过度访问警告」检测 + cookie 存活探测（共享，仅服务端调度用）。
 *
 * <p>一次站内信读取两用：既判定 Pixiv 是否发来「过度访问」警告（需暂停同账号计划任务避免删号），
 * 也作为绑定 cookie 的存活探测——读不到（4xx / 登录重定向）即 cookie 已死。
 *
 * <p>判定「当前应处理的过度访问警告」需<b>同时</b>满足以下业务规则：
 * <ol>
 *   <li>{@code thread_name == "pixiv事務局"} 且 {@code is_official} 为真；</li>
 *   <li>{@code latest_content} 同时含 {@code policies.pixiv.net} 与 {@code 14}（不匹配随语言变化的中文文案）；</li>
 *   <li>{@code modified_at} 在最近 1 小时窗口内（超过自动不触发）；</li>
 *   <li>{@code modified_at > ackWarningTime}（管理员已显式放行的不再触发）。</li>
 * </ol>
 * 返回三态 {@link Result}：{@code CLEAN} / {@code WARNED(modifiedAt)} / {@code COOKIE_DEAD}。
 * modifiedAt 统一返回 Unix epoch <b>毫秒</b>（项目时间不变量）。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class OveruseWarningService {

    static final String OFFICIAL_THREAD_NAME = "pixiv事務局";
    static final String POLICY_MARKER = "policies.pixiv.net";
    static final String ARTICLE_MARKER = "14";
    /** 过度访问警告的有效窗口：超过 1 小时的旧警告自动不再触发（毫秒）。 */
    static final long WINDOW_MS = 3_600_000L;

    private final PixivFetchService pixivFetchService;

    /**
     * 读站内信并判定。
     *
     * @param cookie         任务的绑定 cookie 快照
     * @param ackWarningTime 管理员已显式放行的最新警告 modifiedAt（毫秒，可空）
     * @param now            当前时刻（毫秒）
     */
    public Result check(String cookie, Long ackWarningTime, long now) {
        if (cookie == null || cookie.isBlank()) {
            return Result.cookieDead();
        }
        JsonNode body;
        try {
            body = pixivFetchService.fetchMessageThreads(cookie);
        } catch (PixivFetchService.PixivFetchException e) {
            return Result.cookieDead();
        } catch (Exception e) {
            // 瞬时网络 / 解析异常：不误判 cookie 死、也不误暂停——视为 CLEAN，让本轮正常进行
            // （依赖型任务若 cookie 真死，发现阶段会再次失败并挂起）。
            log.debug("Overuse check transient error: {}", e.getClass().getSimpleName());
            return Result.clean();
        }
        long ack = ackWarningTime == null ? 0L : ackWarningTime;
        long best = 0L;
        String bestExcerpt = "";
        for (JsonNode thread : collectThreads(body)) {
            if (!isActionableWarning(thread)) {
                continue;
            }
            long modifiedMs = toMillis(thread.path("modified_at").asLong(0));
            if (modifiedMs < now - WINDOW_MS) {
                continue; // 超过 1 小时窗口
            }
            if (modifiedMs <= ack) {
                continue; // 管理员已显式放行
            }
            if (modifiedMs > best) {
                best = modifiedMs;
                bestExcerpt = excerpt(thread.path("latest_content").asText(""));
            }
        }
        return best > 0 ? Result.warned(best, bestExcerpt) : Result.clean();
    }

    /** 把站内信正文做成可放进邮件的摘要：去 HTML 标签、折叠空白、截断 300 字（绝不含凭证）。 */
    static String excerpt(String content) {
        if (content == null || content.isBlank()) return "";
        String text = content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static boolean isActionableWarning(JsonNode thread) {
        if (!OFFICIAL_THREAD_NAME.equals(thread.path("thread_name").asText(""))) {
            return false;
        }
        if (!isTruthy(thread.path("is_official"))) {
            return false;
        }
        String content = thread.path("latest_content").asText("");
        return content.contains(POLICY_MARKER) && content.contains(ARTICLE_MARKER);
    }

    /** 容忍布尔 / 数值（1/0）/ 文本（"1"/"true"）多种表达。 */
    private static boolean isTruthy(JsonNode node) {
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asInt() != 0;
        String t = node.asText("");
        return "1".equals(t) || "true".equalsIgnoreCase(t);
    }

    /** Pixiv 站内信 modified_at 为秒级 epoch；统一转毫秒（已是毫秒的大值则原样保留）。 */
    private static long toMillis(long modifiedAt) {
        if (modifiedAt <= 0) return 0L;
        return modifiedAt < 1_000_000_000_000L ? modifiedAt * 1000L : modifiedAt;
    }

    /**
     * 从 body 中收集线程对象。优先 {@code message_threads} 数组；否则容忍 body 本身是数组，
     * 或其它直接子数组字段——只要元素含 {@code thread_name} 即视为线程。
     */
    private static List<JsonNode> collectThreads(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null || body.isMissingNode() || body.isNull()) {
            return out;
        }
        JsonNode named = body.path("message_threads");
        if (named.isArray()) {
            named.forEach(out::add);
            return out;
        }
        if (body.isArray()) {
            body.forEach(out::add);
            return out;
        }
        body.forEach(child -> {
            if (child.isArray()) {
                child.forEach(el -> {
                    if (el.isObject() && el.has("thread_name")) {
                        out.add(el);
                    }
                });
            }
        });
        return out;
    }

    /** 检测三态。WARNED 携带触发警告的 modifiedAt（毫秒）。 */
    public enum State {CLEAN, WARNED, COOKIE_DEAD}

    public record Result(State state, long modifiedAt, String excerpt) {
        public static Result clean() {
            return new Result(State.CLEAN, 0L, "");
        }

        public static Result warned(long modifiedAt, String excerpt) {
            return new Result(State.WARNED, modifiedAt, excerpt == null ? "" : excerpt);
        }

        public static Result cookieDead() {
            return new Result(State.COOKIE_DEAD, 0L, "");
        }

        public boolean isClean() {
            return state == State.CLEAN;
        }

        public boolean isWarned() {
            return state == State.WARNED;
        }

        public boolean isCookieDead() {
            return state == State.COOKIE_DEAD;
        }
    }
}
