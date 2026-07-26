package top.sywyar.pixivdownload.author;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("AuthorObservationServiceAdapter 作者事实端口测试")
class AuthorObservationServiceAdapterTest {

    @Test
    @DisplayName("应原样委托作者观察与缺失补齐语义")
    void delegatesAuthorObservation() {
        AuthorService authorService = mock(AuthorService.class);
        AuthorObservationServiceAdapter adapter = new AuthorObservationServiceAdapter(authorService);

        adapter.observe(42L, " Writer ");
        adapter.resolveMissing(84L, "credential");

        verify(authorService).observe(42L, " Writer ");
        verify(authorService).asyncLookupMissing(84L, "credential");
    }
}
