package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ScriptRegistry 元数据解析测试")
class ScriptRegistryTest {

    @Test
    @DisplayName("带 UTF-8 BOM 的多合一脚本仍可解析 name/version/description，且忽略 @description:en")
    void parseScriptMetadata_handlesBomAndLocalizedDescription() throws IOException {
        String content = """
                \uFEFF// ==UserScript==
                // @name         Pixiv All-in-One Downloader
                // @version      1.2.3
                // @description  中文描述
                // @description:en  English description
                // ==/UserScript==
                (function(){})();
                """;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            ScriptResource resource = ScriptRegistry.parseScriptMetadata("Pixiv All-in-One.user.js", reader);

            assertEquals("all-in-one", resource.id());
            assertEquals("Pixiv All-in-One Downloader", resource.displayName());
            assertEquals("1.2.3", resource.version());
            assertEquals("中文描述", resource.description());
        }
    }
}
