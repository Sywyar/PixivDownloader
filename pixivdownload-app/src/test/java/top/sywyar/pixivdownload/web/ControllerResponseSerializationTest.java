package top.sywyar.pixivdownload.web;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.collection.CollectionController;
import top.sywyar.pixivdownload.maintenance.MaintenanceController;
import top.sywyar.pixivdownload.setup.guest.controller.AdminInviteController;
import top.sywyar.pixivdownload.setup.guest.controller.InviteRedeemController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("控制器显式响应 DTO JSON 契约")
class ControllerResponseSerializationTest {

    private final ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults().build();

    @Test
    @DisplayName("实际宿主 Jackson 自动配置保留响应顺序、日期文本与未知字段容忍")
    void hostMapperPreservesWireFormat() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    assertThat(mapper.valueToTree(new MaintenanceController.StatusResponse(
                            true, false, 1L, 2L, "manual")).propertyNames())
                            .containsExactly("enabled", "paused", "lastStartedAt", "lastFinishedAt", "lastTriggeredBy");
                    assertThat(mapper.writeValueAsString(java.time.Instant.parse("2020-01-02T03:04:05Z")))
                            .isEqualTo("\"2020-01-02T03:04:05Z\"");
                    assertThat(mapper.readValue("{\"added\":true,\"extra\":1}", CollectionController.AddedResponse.class))
                            .isEqualTo(new CollectionController.AddedResponse(true));
                });
    }

    @Test
    @DisplayName("收藏夹响应保留原有字段名")
    void collectionResponsesKeepFieldNames() {
        assertThat(fieldNames(new CollectionController.AddedResponse(true))).containsExactly("added");
        assertThat(fieldNames(new CollectionController.RemovedResponse(true))).containsExactly("removed");
        assertThat(fieldNames(new CollectionController.CollectionIdsResponse(List.of(1L))))
                .containsExactly("collectionIds");
        assertThat(fieldNames(new CollectionController.MembershipsResponse(Map.of(1L, List.of(2L)))))
                .containsExactly("memberships");
    }

    @Test
    @DisplayName("维护与邀请响应保留原有字段名")
    void administrativeResponsesKeepFieldNames() {
        assertThat(fieldNames(new MaintenanceController.StatusResponse(
                true, false, 1L, 2L, "manual")))
                .containsExactly("enabled", "paused", "lastStartedAt", "lastFinishedAt", "lastTriggeredBy");
        assertThat(fieldNames(new MaintenanceController.RunResponse(true))).containsExactly("started");
        assertThat(fieldNames(new AdminInviteController.AccessCheckResponse(true))).containsExactly("admin");
        assertThat(fieldNames(new AdminInviteController.InviteListResponse(List.of())))
                .containsExactly("invites");
        assertThat(fieldNames(new AdminInviteController.SuccessResponse(true))).containsExactly("success");
        assertThat(fieldNames(new AdminInviteController.DeleteExpiredResponse(true, 2)))
                .containsExactly("success", "deleted");
        assertThat(fieldNames(new AdminInviteController.InviteStatsResponse(7, List.of())))
                .containsExactly("days", "buckets");
        assertThat(fieldNames(new InviteRedeemController.RedeemResponse(true, "/pixiv-gallery.html")))
                .containsExactly("success", "redirect");
    }

    private List<String> fieldNames(Object value) {
        List<String> names = new ArrayList<>();
        objectMapper.valueToTree(value).propertyNames().iterator().forEachRemaining(names::add);
        return names;
    }
}
