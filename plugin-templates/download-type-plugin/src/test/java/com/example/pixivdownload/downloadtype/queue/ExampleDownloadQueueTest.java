package com.example.pixivdownload.downloadtype.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueDrain;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleDownloadQueueTest {

    @Test
    @DisplayName("同步示例队列使用立即归零的零代哨兵")
    void synchronousQueueUsesCompletedDrain() {
        ExampleDownloadQueue queue = new ExampleDownloadQueue();
        QueueDrain drain = queue.prepareQuiesce(queue.queueType());

        assertEquals(queue.queueType(), drain.queueType());
        assertEquals(QueueDrain.COMPLETED_GENERATION, drain.generation());
        assertTrue(drain.isDrained());
        assertEquals(0, drain.activeCount());
        assertTrue(drain.awaitDrained());
    }

    @Test
    @DisplayName("清空和可选取消操作保持 owner 作用域")
    void clearAndCancelRespectOwnerScope() {
        ExampleDownloadQueue queue = new ExampleDownloadQueue();
        queue.complete("100", "A", RequestOwnerIdentity.owner("owner-a"));
        queue.complete("101", "B", RequestOwnerIdentity.owner("owner-b"));

        queue.cancel("100", "owner-b", false);
        assertTrue(queue.find("100", RequestOwnerIdentity.owner("owner-a")).isPresent());
        queue.cancel("100", "owner-a", false);
        assertFalse(queue.find("100", RequestOwnerIdentity.owner("owner-a")).isPresent());
        assertEquals(1, queue.clearForOwner("owner-b"));
        assertEquals(0, queue.clearAll());
    }

    @Test
    @DisplayName("同 workKey 可按 owner 并存，访客隔离而管理员可跨 owner 取消")
    void compositeOwnerAndWorkKeyPreventCrossOwnerOverwrite() {
        ExampleDownloadQueue queue = new ExampleDownloadQueue();
        RequestOwnerIdentity ownerA = RequestOwnerIdentity.owner("owner-a");
        RequestOwnerIdentity ownerB = RequestOwnerIdentity.owner("owner-b");
        queue.complete("100", "A", ownerA);
        queue.complete("100", "B", ownerB);

        assertEquals("A", queue.find("100", ownerA).orElseThrow().title());
        assertEquals("B", queue.find("100", ownerB).orElseThrow().title());
        assertEquals(2, queue.snapshot().size());

        queue.cancel("100", null, true);
        assertFalse(queue.find("100", ownerA).isPresent());
        assertFalse(queue.find("100", ownerB).isPresent());
    }
}
