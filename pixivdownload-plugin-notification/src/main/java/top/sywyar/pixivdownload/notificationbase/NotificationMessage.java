package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 已渲染并持久化的管理员站内信。时间字段均为 epoch millis。 */
public record NotificationMessage(
        String id,
        String category,
        String severity,
        String scenarioId,
        String title,
        String body,
        String contentUrl,
        @JsonIgnore String contentHtml,
        String actionUrl,
        long createdTime,
        Long readTime
) {

    static final String PERSISTENT_SURVEY_ID_PREFIX = "persistent-survey:";

    public NotificationMessage(String id,
                               String category,
                               String severity,
                               String scenarioId,
                               String title,
                               String body,
                               String contentUrl,
                               String actionUrl,
                               long createdTime,
                               Long readTime) {
        this(id, category, severity, scenarioId, title, body,
                contentUrl, null, actionUrl, createdTime, readTime);
    }

    @JsonProperty("hasHtmlContent")
    public boolean hasHtmlContent() {
        return contentHtml != null;
    }

    @JsonProperty("deletable")
    public boolean deletable() {
        return !persistentSurvey();
    }

    @JsonProperty("embeddedContentUrl")
    public String embeddedContentUrl() {
        return persistentSurvey() ? actionUrl : null;
    }

    @JsonIgnore
    public boolean persistentSurvey() {
        return id != null && id.startsWith(PERSISTENT_SURVEY_ID_PREFIX);
    }
}
