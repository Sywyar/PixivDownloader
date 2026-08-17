package top.sywyar.pixivdownload.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.io.IOException;

/** 仅供插件开发模式在本机触发真实 4xx / 5xx 容器错误分派。 */
@Controller
@ConditionalOnProperty(name = PluginDevelopmentArtifacts.ENABLED_PROPERTY, havingValue = "true")
public class StatusPagePreviewController {

    @GetMapping("/__dev/error/{status:[45]\\d{2}}")
    public void preview(@PathVariable int status, HttpServletResponse response) throws IOException {
        response.sendError(status);
    }
}
