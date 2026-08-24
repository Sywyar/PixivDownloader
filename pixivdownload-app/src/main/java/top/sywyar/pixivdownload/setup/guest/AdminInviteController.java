package top.sywyar.pixivdownload.setup.guest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.setup.guest.dto.HourlyBucket;
import top.sywyar.pixivdownload.setup.guest.dto.InviteCreateRequest;
import top.sywyar.pixivdownload.setup.guest.dto.InviteCreateResponse;
import top.sywyar.pixivdownload.setup.guest.dto.InviteDetail;
import top.sywyar.pixivdownload.setup.guest.dto.InviteSummary;

import java.util.List;

/**
 * 管理员后台 CRUD：创建/列表/详情/编辑/暂停/恢复/删除/访问统计。
 *
 * <p>所有 {@code /api/admin/**} 路径在 AuthFilter 中视为管理员专属，访客邀请会话即使持 cookie 也 403。
 */
@RestController
@RequestMapping("/api/admin/invites")
@RequiredArgsConstructor
public class AdminInviteController {

    private final GuestInviteService guestInviteService;

    @GetMapping("/access-check")
    public AccessCheckResponse accessCheck() {
        return new AccessCheckResponse(true);
    }

    @PostMapping
    public InviteCreateResponse create(@RequestBody InviteCreateRequest request) {
        long id = guestInviteService.createInvite(request);
        InviteDetail detail = guestInviteService.detail(id);
        return new InviteCreateResponse(id, detail.code(), detail.url());
    }

    @GetMapping
    public InviteListResponse list() {
        List<InviteSummary> items = guestInviteService.list();
        return new InviteListResponse(items);
    }

    @GetMapping("/{id}")
    public InviteDetail detail(@PathVariable long id) {
        return guestInviteService.detail(id);
    }

    @PutMapping("/{id}")
    public InviteDetail update(@PathVariable long id, @RequestBody InviteCreateRequest request) {
        guestInviteService.updateInvite(id, request);
        return guestInviteService.detail(id);
    }

    @PostMapping("/{id}/pause")
    public SuccessResponse pause(@PathVariable long id) {
        guestInviteService.pause(id);
        return new SuccessResponse(true);
    }

    @PostMapping("/{id}/resume")
    public SuccessResponse resume(@PathVariable long id) {
        guestInviteService.resume(id);
        return new SuccessResponse(true);
    }

    @DeleteMapping("/expired")
    public DeleteExpiredResponse deleteExpired() {
        int deleted = guestInviteService.deleteExpired(System.currentTimeMillis());
        return new DeleteExpiredResponse(true, deleted);
    }

    @DeleteMapping("/{id}")
    public SuccessResponse delete(@PathVariable long id) {
        guestInviteService.delete(id);
        return new SuccessResponse(true);
    }

    @GetMapping("/{id}/stats")
    public InviteStatsResponse stats(
            @PathVariable long id,
            @RequestParam(defaultValue = "7") int days) {
        List<HourlyBucket> buckets = guestInviteService.getAccessStats(id, days);
        int normalized = switch (days) {
            case 1 -> 1;
            case 30 -> 30;
            default -> 7;
        };
        return new InviteStatsResponse(normalized, buckets);
    }

    public record AccessCheckResponse(boolean admin) {}

    public record InviteListResponse(List<InviteSummary> invites) {}

    public record SuccessResponse(boolean success) {}

    public record DeleteExpiredResponse(boolean success, int deleted) {}

    public record InviteStatsResponse(int days, List<HourlyBucket> buckets) {}
}
