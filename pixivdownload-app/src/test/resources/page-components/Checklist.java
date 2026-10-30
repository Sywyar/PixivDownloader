package example.pages.contract;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.contract.schema.*;
import top.sywyar.pixivdownload.plugin.api.contract.remote.*;
import top.sywyar.pixivdownload.plugin.api.web.*;

/** 示例业务协议独立于页面协议，SDK 不认识清单字段或校验操作。 */
@RemoteService(owner = "page-checklist-contract")
public interface Checklist {
    @RemoteOperation(path = "/api/page-checklist/verify", method = HttpMethod.POST, access = AccessPolicy.ADMIN)
    Result verify(Config configuration);

    @ValueSchema record Item(
            @ValueProperty(required = true, nullable = false, maxLength = 80) String label,
            @ValueProperty(required = true, nullable = false) boolean checked) {}
    @ValueSchema record Config(
            @ValueProperty(required = true, nullable = false, maxLength = 80) String title,
            @ValueProperty(required = true, nullable = false, elementsNullable = false, maxItems = 20) List<Item> items) {}
    @ValueSchema record Result(
            @ValueProperty(required = true, nullable = false, minimum = "0", maximum = "20") int checked,
            @ValueProperty(required = true, nullable = false, minimum = "0", maximum = "20") int total) {}
}
