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
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthorService authorService;

    @BeforeEach
    void setUp() {
        authorService = new AuthorService(authorMapper, pixivDatabase, restTemplate, taskScheduler);
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

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }
}
