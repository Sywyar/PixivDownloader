package top.sywyar.pixivdownload.gui.controller;

import lombok.Builder;
import lombok.Data;

/**
 * /api/gui/status 响应体。
 * 仅返回 monitor.html 不展示的服务器元信息；统计数据、下载队列由 monitor.html 专职展示。
 */
@Data
@Builder
public class GuiStatusResponse {
    private int port;
    private String mode;
    private String startTime;
}
