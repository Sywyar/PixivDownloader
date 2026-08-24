package top.sywyar.pixivdownload.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.collection.CollectionController;
import top.sywyar.pixivdownload.maintenance.MaintenanceController;
import top.sywyar.pixivdownload.setup.guest.AdminInviteController;
import top.sywyar.pixivdownload.setup.guest.InviteRedeemController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("控制器显式响应 DTO JSON 契约")
class ControllerResponseSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        objectMapper.valueToTree(value).fieldNames().forEachRemaining(names::add);
        return names;
    }
}
