package top.sywyar.pixivdownload.ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.ai.AiChatClient;

/**
 * 文本模型（LLM）配置状态查询端点。
 * <p>
 * 路径 {@code /api/admin/ai/status} 由 {@code AuthFilter} 的 {@code /api/admin/} 前缀按 monitor 语义保护——
 * solo 与 multi 两种模式下都仅管理员可访问，限流绝不作用于 solo / 已登录管理员。前端据此决定是否展示依赖 LLM 的
 * 入口（如小说 / 系列详情页的「AI 翻译」按钮）：未配置时不展示。
 * <p>
 * 仅下发一个 {@code configured} 布尔值，<b>绝不</b>回显 base-url / api-key / model 等配置明文。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AiStatusController {

    private final AiChatClient aiService;

    /** 文本模型是否已配置就绪（纯配置检查、不触网）。 */
    public record AiStatusResponse(boolean configured) {}

    @GetMapping("/status")
    public AiStatusResponse status() {
        return new AiStatusResponse(aiService.isConfigured());
    }
}
