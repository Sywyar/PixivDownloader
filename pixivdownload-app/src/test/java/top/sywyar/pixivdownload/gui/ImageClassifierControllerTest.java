package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageClassifierControllerTest {
    @ParameterizedTest
    @CsvSource({
        "GET,/api/tools/classifier/settings",
        "HEAD,/api/tools/classifier/settings",
        "PUT,/api/tools/classifier/settings",
        "GET,/api/tools/classifier/folders",
        "HEAD,/api/tools/classifier/folders",
        "DELETE,/api/tools/classifier/folders",
        "GET,/api/tools/classifier/images",
        "HEAD,/api/tools/classifier/images",
        "GET,/api/tools/classifier/image",
        "HEAD,/api/tools/classifier/image",
        "POST,/api/tools/classifier/moves",
        "GET,/api/downloaded/by-move-folder",
        "HEAD,/api/downloaded/by-move-folder",
        "POST,/api/downloaded/move/bad-id"
    })
    void retiredOperationIgnoresParametersPayloadAndNegotiation(String method, String path) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ImageClassifierController()).build();
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType("text/plain").content("{").accept("image/png"))
                .andExpect(status().isGone()).andExpect(content().bytes(new byte[0]));
    }
}
