package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DownloadedResponse {
    private final Long artworkId;
    private final String title;
    private final String folder;
    private final int count;
    private final Long time;
    private final boolean moved;
    private final String moveFolder;
    private final Long moveTime;

    public static class DownloadedResponseBuilder {
        private Long artworkId;
        private String title;
        private String folder;
        private int count = 0;
        private Long time;
        private boolean moved = false;
        private String moveFolder;
        private Long moveTime;

        public DownloadedResponseBuilder setArtworkId(Long artworkId) {
            this.artworkId = artworkId;
            return this;
        }

        public DownloadedResponseBuilder setTitle(String title) {
            this.title = title;
            return this;
        }

        public DownloadedResponseBuilder setFolder(String folder) {
            this.folder = folder;
            return this;
        }

        public DownloadedResponseBuilder setCount(int count) {
            this.count = count;
            return this;
        }

        public DownloadedResponseBuilder setTime(Long time) {
            this.time = time;
            return this;
        }

        public DownloadedResponseBuilder setMoved(boolean moved) {
            this.moved = moved;
            return this;
        }

        public DownloadedResponseBuilder setMoveFolder(String moveFolder) {
            this.moveFolder = moveFolder;
            return this;
        }

        public DownloadedResponseBuilder setMoveTime(Long moveTime) {
            this.moveTime = moveTime;
            return this;
        }

        public DownloadedResponse build() {
            boolean flag = artworkId != null && title != null && folder != null && count != 0 && time != null;
            boolean flag2 = !moved || (moveFolder != null && moveTime != null);

            if (flag && flag2) {
                return new DownloadedResponse(artworkId, title, folder, count, time, moved, moveFolder, moveTime);
            } else {
                throw new RuntimeException("缺少必要值");
            }
        }
    }
}
