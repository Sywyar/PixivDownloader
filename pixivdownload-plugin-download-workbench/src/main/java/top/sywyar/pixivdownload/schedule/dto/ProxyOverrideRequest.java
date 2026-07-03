package top.sywyar.pixivdownload.schedule.dto;

import lombok.Data;

/**
 * 设置 / 清除计划任务「任务级单独代理」的请求体。
 *
 * <p>{@code proxy} 为 {@code host:port}（HTTP 代理，如 {@code 127.0.0.1:7890}）；
 * {@code null} / 空白表示清除单独代理、回退使用全局代理设置。
 */
@Data
public class ProxyOverrideRequest {

    private String proxy;
}
