package top.sywyar.pixivdownload.notificationbase;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;

/** 管理员站内信只读查询与已读标记接口；访问控制由插件 ADMIN 路由统一执行。 */
@PluginManagedBean
@RestController
@RequestMapping("/api/notifications")
public class NotificationInboxController {

    private final NotificationInboxService inbox;

    public NotificationInboxController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    public ResponseEntity<InboxSnapshot> latest(@RequestParam(required = false) String category,
                                                @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                @RequestParam(defaultValue = "20") int limit) {
        NotificationCategory selectedCategory = category(category);
        long globalUnreadCount = inbox.unreadCount();
        long categoryUnreadCount = selectedCategory == null
                ? globalUnreadCount
                : inbox.unreadCount(selectedCategory);
        return noStore(new InboxSnapshot(globalUnreadCount, categoryUnreadCount,
                inbox.latest(selectedCategory, unreadOnly, limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationMessage> detail(@PathVariable String id) {
        return noStore(requireMessage(inbox.find(id)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationMessage> markRead(@PathVariable String id) {
        return noStore(requireMessage(inbox.markRead(id)));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Integer> markAllRead(@RequestParam(required = false) String category) {
        return noStore(inbox.markAllRead(category(category)));
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

    public record InboxSnapshot(long unreadCount, long categoryUnreadCount, List<NotificationMessage> messages) {
        public InboxSnapshot {
            messages = List.copyOf(messages);
        }
    }
}
