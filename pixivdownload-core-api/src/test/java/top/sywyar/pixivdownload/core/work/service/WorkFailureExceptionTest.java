package top.sywyar.pixivdownload.core.work.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("作品操作失败纯领域契约")
class WorkFailureExceptionTest {

    @Test
    @DisplayName("删除失败只携失败原因、作品类型和作品 ID")
    void deletionFailureCarriesDomainFields() {
        WorkDeletionException exception = new WorkDeletionException(
                WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED,
                WorkType.ARTWORK,
                42L);

        assertThat(exception.reason())
                .isEqualTo(WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED);
        assertThat(exception.workType()).isEqualTo(WorkType.ARTWORK);
        assertThat(exception.workId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("可见性失败只携作品类型和作品 ID")
    void visibilityFailureCarriesDomainFields() {
        WorkVisibilityDeniedException exception =
                new WorkVisibilityDeniedException(WorkType.NOVEL, 43L);

        assertThat(exception.workType()).isEqualTo(WorkType.NOVEL);
        assertThat(exception.workId()).isEqualTo(43L);
    }
}
