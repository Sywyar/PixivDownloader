package top.sywyar.pixivdownload.core.work.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkMetadata 中性作品元数据契约测试")
class WorkMetadataTest {

    @Test
    @DisplayName("公共模型不携带来源插件私有详情块")
    void shouldKeepOwnerPrivateDetailsOutOfSharedModel() {
        assertThat(Arrays.stream(WorkMetadata.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "workType", "workId", "title", "description", "xRestrict", "isAi",
                        "authorId", "authorName", "seriesId", "seriesOrder", "seriesTitle",
                        "tags", "downloadTime", "pageCount", "extensions", "folder", "moved",
                        "moveFolder", "moveTime", "fileNameTemplateId", "fileNameTemplate",
                        "fileAuthorNameId", "uploadTime", "isOriginal");
        assertThatThrownBy(() -> Class.forName(
                "top.sywyar.pixivdownload.core.work.model.NovelWorkDetails"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("标签列表为防御性拷贝且 null 归一为空列表")
    void shouldCopyTagsDefensively() {
        List<WorkTag> mutable = new ArrayList<>();
        mutable.add(new WorkTag(1L, "tag", null));
        WorkMetadata metadata = new WorkMetadata(WorkType.NOVEL, 1L, "标题", null, 0, false,
                null, null, null, null, null, mutable, 100L, 1, "txt", "/p/1",
                false, null, null, null, null, null, null, null);
        mutable.clear();

        assertThat(metadata.tags()).containsExactly(new WorkTag(1L, "tag", null));
        assertThatThrownBy(() -> metadata.tags().add(new WorkTag(2L, "x", null)))
                .isInstanceOf(UnsupportedOperationException.class);

        WorkMetadata noTags = new WorkMetadata(WorkType.ARTWORK, 2L, "标题", null, 0, false,
                null, null, null, null, null, null, 100L, 1, "jpg", "/p/2",
                false, null, null, null, null, null, null, null);
        assertThat(noTags.tags()).isEmpty();
    }
}
