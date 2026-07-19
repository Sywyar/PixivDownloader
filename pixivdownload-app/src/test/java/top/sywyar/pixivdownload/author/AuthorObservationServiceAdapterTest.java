package top.sywyar.pixivdownload.author;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("AuthorObservationServiceAdapter 作者事实端口测试")
class AuthorObservationServiceAdapterTest {

    @Test
    @DisplayName("应原样委托作者观察语义")
    void delegatesAuthorObservation() {
        AuthorService authorService = mock(AuthorService.class);
        AuthorObservationServiceAdapter adapter = new AuthorObservationServiceAdapter(authorService);

        adapter.observe(42L, " Writer ");

        verify(authorService).observe(42L, " Writer ");
    }
}
