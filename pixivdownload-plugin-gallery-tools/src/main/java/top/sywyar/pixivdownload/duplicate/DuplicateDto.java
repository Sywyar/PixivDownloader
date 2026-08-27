package top.sywyar.pixivdownload.duplicate;

import java.util.List;

public final class DuplicateDto {

    private DuplicateDto() {
    }

    public record Item(long artworkId,
                       int page,
                       String title,
                       long authorId,
                       String authorName,
                       int xRestrict,
                       String thumbnailUrl) {
    }

    public record Group(String groupId,
                        int size,
                        int maxDistance,
                        List<Item> items) {
        public Group {
            items = List.copyOf(items);
        }
    }

    public record GroupsPage(int page,
                             int size,
                             int totalGroups,
                             List<Group> groups,
                             ScanStatus scan) {
        public GroupsPage {
            groups = List.copyOf(groups);
        }
    }

    public record ScanStatus(String state,
                             int processed,
                             int total,
                             Long startedTime) {
    }

    public static ScanStatus idleScanStatus() {
        return new ScanStatus("IDLE", 0, 0, null);
    }
}
