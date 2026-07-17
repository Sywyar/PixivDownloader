package com.example.pixivdownload.downloadtype.web;

import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleDownloadControllerTest {

    private final AtomicReference<RequestOwnerIdentity> identity =
            new AtomicReference<>(RequestOwnerIdentity.adminScope());
    private final ExampleDownloadController controller =
            new ExampleDownloadController(new ExampleDownloadQueue(), ignored -> identity.get());

    @Test
    @DisplayName("user series search range 与 quick 返回 workbench 所需稳定形状")
    void acquisitionResponsesMatchWorkbenchShapes() {
        var user = controller.userWorks("author", 1, 12);
        assertEquals(12, user.items().size());
        assertFalse(user.lastPage());
        assertTrue(user.hasMore());
        assertEquals("2", user.nextCursor());

        var series = controller.series("collection", 4, 12);
        assertNotNull(series.series());
        assertEquals(12, series.items().size());
        assertTrue(series.isLastPage());
        assertFalse(series.hasMore());
        assertNull(series.nextCursor());

        var range = controller.searchRange("tag", 2, 4, 3);
        assertEquals(9, range.items().size());
        assertEquals(2, range.startPage());
        assertEquals(4, range.endPage());
        assertEquals(3, range.requestedPages());
        assertEquals(3, range.acceptedPages());
        assertEquals(3, range.fetchedPages());
        assertEquals(0, range.limitPage());

        var truncatedRange = controller.searchRange("tag", 1, 10, 12);
        assertEquals(48, truncatedRange.total());
        assertEquals(10, truncatedRange.requestedPages());
        assertEquals(10, truncatedRange.acceptedPages());
        assertEquals(4, truncatedRange.fetchedPages());
        assertEquals(4, truncatedRange.endPage());

        var quick = controller.quick(7);
        assertEquals(7, quick.items().size());
        assertEquals(7, quick.total());
        assertTrue(quick.lastPage());
        assertFalse(quick.hasMore());
        assertNull(quick.nextCursor());
    }

    @Test
    @DisplayName("queue 与status 只信任宿主解析的 owner，管理员可跨 owner 读取")
    void queueAndStatusUseTrustedRequestOwner() {
        identity.set(RequestOwnerIdentity.owner("owner-a"));
        var ownerA = controller.queue(new ExampleDownloadController.QueueRequest("100", "A"), null);
        assertEquals(200, ownerA.getStatusCode().value());

        identity.set(RequestOwnerIdentity.owner("owner-b"));
        var ownerB = controller.queue(new ExampleDownloadController.QueueRequest("100", "B"), null);
        assertEquals(200, ownerB.getStatusCode().value());

        identity.set(RequestOwnerIdentity.owner("owner-a"));
        var ownerAStatus = controller.status("100", null);
        assertEquals(200, ownerAStatus.getStatusCode().value());
        assertEquals("A", ((ExampleDownloadQueue.QueueItem) ownerAStatus.getBody()).title());

        identity.set(RequestOwnerIdentity.owner("owner-c"));
        assertEquals(404, controller.status("100", null).getStatusCode().value());

        identity.set(RequestOwnerIdentity.adminScope());
        assertEquals(200, controller.status("100", null).getStatusCode().value());
    }
}
