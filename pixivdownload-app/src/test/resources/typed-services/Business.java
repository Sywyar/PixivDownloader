package acceptance.business;

import java.util.List;

/** 独立业务包；宿主与中央 SDK 不声明这些服务或 DTO。 */
public final class Business {
    private Business() { }
    public record Entry(String id, String text) { }
    public record Decision(boolean accepted, String code) { }
    public record Job(String id, String state) { }
    public interface Store {
        Entry save(Entry entry);
        List<Entry> query();
    }
    public interface Validator { Decision validate(Entry entry); }
    public interface EventSink { void received(Entry entry); }
    public interface Jobs {
        Job start(String value);
        Job query(String id);
        Job cancel(String id);
    }
}
