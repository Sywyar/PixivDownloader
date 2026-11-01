package top.sywyar.pixivdownload.pageeditor.components;

import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 用户组件共用可信适配器；源码版本独立于 SDK 配置版本。 */
public final class CustomComponentContribution implements PageContribution {
    private final ComponentLibrary library;
    public CustomComponentContribution(ComponentLibrary library) { this.library = library; }
    @Override public List<PageComponent> components() {
        return List.of(new PageComponent(ComponentLibrary.TYPE, 1, "custom.component", "page-editor",
                "/page-components/runtime.mjs", "/page-editor/custom-properties.mjs", """
                {"type":"object","additionalProperties":false,
                 "required":["componentId","version","properties"],
                 "properties":{"componentId":{"type":"string","maxLength":36},
                 "version":{"type":"string","maxLength":64},
                 "properties":{"type":"object","additionalProperties":{"type":"string","maxLength":16384}}}}
                """, "{\"componentId\":\"\",\"version\":\"\",\"properties\":{}}", true, List.of(), List.of(), null));
    }
    @Override public void validate(String type, String configuration) {
        library.validateConfiguration(ComponentLibrary.parse(configuration.getBytes(StandardCharsets.UTF_8)), false);
    }
}
