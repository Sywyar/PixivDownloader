package top.sywyar.pixivdownload.core.work.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkMetadata 中性作品元数据契约测试")
class WorkMetadataTest {

    @Test
    @DisplayName("标签列表为防御性拷贝且 null 归一为空列表")
    void shouldCopyTagsDefensively() {
        List<WorkTag> mutable = new ArrayList<>();
        mutable.add(new WorkTag(1L, "tag", null));
        WorkMetadata metadata = new WorkMetadata(1L, "标题", null, 0, false,
                null, null, null, null, null, mutable, 100L, 1, "txt", "/p/1",
                false, null, null, null, null, null);
        mutable.clear();

        assertThat(metadata.tags()).containsExactly(new WorkTag(1L, "tag", null));
        assertThatThrownBy(() -> metadata.tags().add(new WorkTag(2L, "x", null)))
                .isInstanceOf(UnsupportedOperationException.class);

        WorkMetadata noTags = new WorkMetadata(2L, "标题", null, 0, false,
                null, null, null, null, null, null, 100L, 1, "jpg", "/p/2",
                false, null, null, null, null, null);
        assertThat(noTags.tags()).isEmpty();
    }

}
