package top.sywyar.pixivdownload.author;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorService 单元测试")
class AuthorServiceTest {

    @Mock
    private AuthorMapper authorMapper;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private AppMessages messages;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthorService authorService;

    @BeforeEach
    void setUp() {
        authorService = new AuthorService(authorMapper, pixivDatabase, restTemplate, taskScheduler, messages, null);
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("首次观察作者时不应发起 AJAX")
    void shouldInsertWithoutAjaxWhenFirstObserved() {
        when(authorMapper.findById(123L)).thenReturn(null);

        authorService.observe(123L, "Alice");

        verify(authorMapper).insertIfAbsent(eq(123L), eq("Alice"), anyLong());
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    @DisplayName("提示名与数据库一致时不应发起 AJAX")
    void shouldSkipAjaxWhenHintMatchesExistingName() {
        when(authorMapper.findById(123L)).thenReturn(new Author(123L, "Alice", 100L));

        authorService.observe(123L, "Alice");

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    @DisplayName("提示名不一致时应校验并更新作者名")
    void shouldValidateRenameAndUpdateName() throws Exception {
        when(authorMapper.findById(123L))
                .thenReturn(new Author(123L, "Old", 100L), new Author(123L, "Old", 100L));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(json("""
                        {"error":false,"body":{"name":"New"}}
                        """)));

        authorService.observe(123L, "Hint");

        verify(authorMapper).updateName(eq(123L), eq("New"), anyLong());
    }

    @Test
    @DisplayName("Pixiv 返回同名时不应更新")
    void shouldNotUpdateWhenPixivNameMatchesExisting() throws Exception {
        when(authorMapper.findById(123L))
                .thenReturn(new Author(123L, "Old", 100L), new Author(123L, "Old", 100L));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(json("""
                        {"error":false,"body":{"name":"Old"}}
                        """)));

        authorService.observe(123L, "Hint");

        verify(authorMapper, never()).updateName(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("重命名校验应做 10 分钟节流")
    void shouldThrottleRenameValidation() throws Exception {
        when(authorMapper.findById(123L))
                .thenReturn(new Author(123L, "Old", 100L), new Author(123L, "Old", 100L));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(json("""
                        {"error":false,"body":{"name":"New"}}
                        """)));

        authorService.asyncValidateRename(123L);
        authorService.asyncValidateRename(123L);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    @DisplayName("Pixiv 返回 error 时应静默跳过")
    void shouldIgnorePixivErrorResponse() throws Exception {
        when(authorMapper.findById(123L)).thenReturn(new Author(123L, "Old", 100L));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(json("""
                        {"error":true,"message":"error"}
                        """)));

        authorService.asyncValidateRename(123L);

        verify(authorMapper, never()).updateName(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("缺失作者信息时应回填 artwork 并记录作者")
    void shouldLookupMissingAuthorAndPersist() throws Exception {
        when(authorMapper.findById(456L)).thenReturn(null);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(json("""
                        {"error":false,"body":{"userId":"456","userName":"Bob"}}
                        """)));

        authorService.asyncLookupMissing(999L, "cookie=value");

        verify(pixivDatabase).updateAuthorId(999L, 456L);
        verify(authorMapper).insertIfAbsent(eq(456L), eq("Bob"), anyLong());
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("getPagedAuthorsWithArtworks 边界")
    class PagedAuthorsTests {

        @org.junit.jupiter.api.Test
        @DisplayName("无搜索词应使用通配符并归一化排序、计算 totalPages")
        void shouldComputePagedAuthorsWithDefaultsAndTotalPages() {
            when(authorMapper.countAuthorsWithArtworks("%")).thenReturn(45L);
            when(authorMapper.findAuthorsWithArtworks(eq("%"), eq("name"), eq(20), eq(0)))
                    .thenReturn(java.util.List.of(new AuthorSummary(1L, "A", 2L)));

            AuthorService.PagedAuthors paged = authorService.getPagedAuthorsWithArtworks(0, 20, null, null);

            org.assertj.core.api.Assertions.assertThat(paged.totalElements()).isEqualTo(45L);
            org.assertj.core.api.Assertions.assertThat(paged.totalPages()).isEqualTo(3);
            org.assertj.core.api.Assertions.assertThat(paged.page()).isZero();
            org.assertj.core.api.Assertions.assertThat(paged.size()).isEqualTo(20);
            org.assertj.core.api.Assertions.assertThat(paged.content()).hasSize(1);
        }

        @org.junit.jupiter.api.Test
        @DisplayName("page 应被 clamp 到 [0, +∞)，size 应被 clamp 到 [1, 200]")
        void shouldClampPageAndSize() {
            when(authorMapper.countAuthorsWithArtworks("%")).thenReturn(10L);
            when(authorMapper.findAuthorsWithArtworks(anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(java.util.List.of());

            AuthorService.PagedAuthors paged =
                    authorService.getPagedAuthorsWithArtworks(-3, 9999, null, "artworks");

            org.assertj.core.api.Assertions.assertThat(paged.page()).isZero();
            org.assertj.core.api.Assertions.assertThat(paged.size()).isEqualTo(200);
            // sort='artworks' 应被透传
            verify(authorMapper).findAuthorsWithArtworks(eq("%"), eq("artworks"), eq(200), eq(0));
        }

        @org.junit.jupiter.api.Test
        @DisplayName("非法 sort 应回退为 'name'，size<1 应被 clamp 到 1")
        void shouldFallbackInvalidSortAndClampSizeUp() {
            when(authorMapper.countAuthorsWithArtworks("%")).thenReturn(0L);

            AuthorService.PagedAuthors paged =
                    authorService.getPagedAuthorsWithArtworks(2, 0, null, "weird");

            org.assertj.core.api.Assertions.assertThat(paged.totalElements()).isZero();
            org.assertj.core.api.Assertions.assertThat(paged.totalPages()).isZero();
            org.assertj.core.api.Assertions.assertThat(paged.size()).isEqualTo(1);
            // total=0 不应再调 mapper.findAuthorsWithArtworks
            verify(authorMapper, never()).findAuthorsWithArtworks(anyString(), anyString(), anyInt(), anyInt());
        }

        @org.junit.jupiter.api.Test
        @DisplayName("搜索词应被 trim 并包成 %xxx%")
        void shouldWrapSearchTermAsLikePattern() {
            when(authorMapper.countAuthorsWithArtworks("%alice%")).thenReturn(1L);
            when(authorMapper.findAuthorsWithArtworks(eq("%alice%"), eq("authorId"), eq(20), eq(20)))
                    .thenReturn(java.util.List.of(new AuthorSummary(7L, "Alice", 1L)));

            AuthorService.PagedAuthors paged =
                    authorService.getPagedAuthorsWithArtworks(1, 20, "  alice  ", "authorId");

            org.assertj.core.api.Assertions.assertThat(paged.content())
                    .singleElement()
                    .matches(s -> s.authorId() == 7L);
        }
    }

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }
}
