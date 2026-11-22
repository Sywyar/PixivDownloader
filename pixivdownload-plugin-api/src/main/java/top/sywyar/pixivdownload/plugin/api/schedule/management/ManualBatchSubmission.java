package top.sywyar.pixivdownload.plugin.api.schedule.management;

import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;

import java.util.UUID;

/** 使用调用方当前发布的来源提交一次性批次。 */
public interface ManualBatchSubmission {
    /**
     * 根据当前发布的来源定义创建批次，返回批次标识。
     * @param sourceOwner 发布此来源的功能插件
     * @param sourceType 已发布的来源标识
     * @param name 批次显示名称
     * @param definitionJson 来源定义的 JSON 对象，不含凭据
     * @param credential 可选的临时采集凭据
     * @param program 已认证的程序身份，获授权的人工操作传 null
     * @return 已接纳的批次标识
     */
    UUID submit(String sourceOwner, String sourceType, String name, String definitionJson,
                String credential, ProgramIdentity program);
}
