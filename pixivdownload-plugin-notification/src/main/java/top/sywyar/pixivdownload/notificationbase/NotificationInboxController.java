package top.sywyar.pixivdownload.notificationbase;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理员站内信查询、已读与显式删除接口；访问控制由插件 ADMIN 路由统一执行。 */
@PluginManagedBean
@RestController
@RequestMapping("/api/notifications")
public class NotificationInboxController {

    private static final Pattern DOCTYPE_PREFIX = Pattern.compile("(?is)^\\uFEFF?\\s*<!doctype[^>]*>");
    private static final String HTML_CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'nonce-%s';"
            + " style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; connect-src 'none';"
            + " media-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none';"
            + " worker-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'self';"
            + " sandbox allow-scripts";
    private static final String CONTENT_BRIDGE_SCRIPT = String.join("\n",
            "(function () {",
            "    var source = document.currentScript.getAttribute('data-source') || document.baseURI;",
            "    var heightObserver = null;",
            "    function reportHeight() {",
            "        var body = document.body;",
            "        if (!body) return;",
            "        var style = getComputedStyle(body);",
            "        var height = Math.ceil(Math.max(body.scrollHeight, body.offsetHeight,",
            "            body.getBoundingClientRect().height)",
            "            + (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0));",
            "        if (height > 0) parent.postMessage({type: 'pixiv-content-height', height: height}, '*');",
            "    }",
            "    function observeHeight() {",
            "        reportHeight();",
            "        if (typeof ResizeObserver !== 'function') return;",
            "        heightObserver = new ResizeObserver(reportHeight);",
            "        heightObserver.observe(document.body);",
            "    }",
            "    function forwardLink(event) {",
            "        if (event.defaultPrevented || (event.button != null && event.button !== 0 && event.button !== 1)) return;",
            "        var link = event.target;",
            "        while (link && link.tagName !== 'A') link = link.parentElement;",
            "        if (!link || typeof link.getAttribute !== 'function') return;",
            "        try {",
            "            var url = new URL(link.getAttribute('href'), source);",
            "            if (url.protocol !== 'http:' && url.protocol !== 'https:') return;",
            "            event.preventDefault();",
            "            if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();",
            "            else if (typeof event.stopPropagation === 'function') event.stopPropagation();",
            "            parent.postMessage({",
            "                type: 'pixiv-external-link',",
            "                href: url.href,",
            "                newTab: event.button === 1 || event.ctrlKey || event.metaKey || event.shiftKey",
            "                    || String(link.getAttribute('target') || '').toLowerCase() === '_blank'",
            "            }, '*');",
            "        } catch (ignored) {",
            "            // 非 URL 链接交给正文自身处理。",
            "        }",
            "    }",
            "    if (document.readyState === 'loading')",
            "        document.addEventListener('DOMContentLoaded', observeHeight, {once: true});",
            "    else observeHeight();",
            "    window.addEventListener('load', reportHeight, {once: true});",
            "    document.addEventListener('click', forwardLink, true);",
            "    document.addEventListener('auxclick', forwardLink, true);",
            "})();");

    private final NotificationInboxService inbox;

    public NotificationInboxController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    public ResponseEntity<InboxSnapshot> latest(@RequestParam(required = false) String category,
                                                @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(required = false) String lang) {
        inbox.synchronizePersistentSurveys();
        NotificationCategory selectedCategory = category(category);
        long globalUnreadCount = inbox.unreadCount();
        long categoryUnreadCount = selectedCategory == null
                ? globalUnreadCount
                : inbox.unreadCount(selectedCategory);
        return noStore(new InboxSnapshot(globalUnreadCount, categoryUnreadCount,
                inbox.latest(selectedCategory, unreadOnly, limit, lang)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationMessage> detail(@PathVariable String id,
                                                      @RequestParam(required = false) String lang) {
        inbox.synchronizePersistentSurveys();
        return noStore(requireMessage(inbox.find(id, lang)));
    }

    @GetMapping(value = "/{id}/content", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> htmlContent(@PathVariable String id,
                                              @RequestParam(required = false) String lang) {
        NotificationHtmlContent content = inbox.htmlContent(id, lang);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("Content-Security-Policy", HTML_CONTENT_SECURITY_POLICY.formatted(nonce))
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(withContentBridge(content, nonce));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationMessage> markRead(@PathVariable String id,
                                                        @RequestParam(required = false) String lang) {
        inbox.synchronizePersistentSurveys();
        return noStore(requireMessage(inbox.markRead(id, lang)));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Integer> markAllRead(@RequestParam(required = false) String category) {
        inbox.synchronizePersistentSurveys();
        return noStore(inbox.markAllRead(category(category)));
    }

    @PostMapping("/{id}/survey-unavailable")
    public ResponseEntity<Void> surveyUnavailable(@PathVariable String id) {
        inbox.synchronizePersistentSurveys();
        if (!inbox.dismissUnavailableSurvey(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        inbox.synchronizePersistentSurveys();
        if (!inbox.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .build();
    }

    private static NotificationCategory category(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NotificationCategory.fromToken(value);
        } catch (IllegalArgumentException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static NotificationMessage requireMessage(NotificationMessage message) {
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return message;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }

    private static String withContentBridge(NotificationHtmlContent content, String nonce) {
        String sourceUrl = content.sourceUrl() == null ? "" : escapeHtmlAttribute(content.sourceUrl());
        String bridge = "<script nonce=\"" + nonce + "\" data-source=\"" + sourceUrl + "\">"
                + CONTENT_BRIDGE_SCRIPT + "</script>";
        Matcher doctype = DOCTYPE_PREFIX.matcher(content.html());
        if (!doctype.find()) {
            return bridge + content.html();
        }
        return content.html().substring(0, doctype.end()) + bridge + content.html().substring(doctype.end());
    }

    private static String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public record InboxSnapshot(long unreadCount, long categoryUnreadCount, List<NotificationMessage> messages) {
        public InboxSnapshot {
            messages = List.copyOf(messages);
        }
    }
}
