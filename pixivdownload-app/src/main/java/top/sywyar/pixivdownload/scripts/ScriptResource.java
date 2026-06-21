package top.sywyar.pixivdownload.scripts;

/**
 * 单个油猴脚本的元数据
 */
public record ScriptResource(
        String id,
        String displayName,
        String fileName,
        String description,
        String version
) {

    public String displayNameCode() {
        return "script.meta." + id + ".name";
    }

    public String descriptionCode() {
        return "script.meta." + id + ".description";
    }
}
