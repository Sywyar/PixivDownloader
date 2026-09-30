package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.*;

class DesktopApplicationResourcesTest {
    @Test
    @DisplayName("内置头像只解码一次，读取保持不可变，损坏资源仍拒绝")
    void retainsValidatedAvatarBytes() {
        String catalog = """
                {"maintainers":[{"id":1,"login":"fixture","role":"author-core",
                "avatarUrl":"https://avatars.githubusercontent.com/u/1",
                "profileUrl":"https://github.com/fixture","avatarMediaType":"image/png","avatarBase64":"AAEC"}]}
                """;
        var people = DesktopApplicationResources.loadMaintainers(new java.io.ByteArrayInputStream(
                catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, people.size());
        var avatar = people.get(0).avatar();
        assertSame(avatar, people.get(0).avatar());
        avatar.bytes()[0] = 9;
        assertArrayEquals(new byte[]{0, 1, 2}, people.get(0).avatar().bytes());
        assertTrue(DesktopApplicationResources.loadMaintainers(new java.io.ByteArrayInputStream(
                catalog.replace("AAEC", "bad!").getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEmpty());
    }

    @Test
    void missingMaintainerCatalogFallsBackToEmptyList() {
        assertTrue(DesktopApplicationResources.loadMaintainers(null).isEmpty());
    }
}
