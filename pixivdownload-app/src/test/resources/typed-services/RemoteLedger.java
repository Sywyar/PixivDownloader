package acceptance.remotecontract;
import java.util.*;
import top.sywyar.pixivdownload.plugin.api.contract.schema.*;
import top.sywyar.pixivdownload.plugin.api.contract.remote.*;
import top.sywyar.pixivdownload.plugin.api.web.*;

/** 独立构建夹具；宿主与 SDK 不编译此业务接口。 */
@RemoteService(owner="remote-ledger-contract")
public interface RemoteLedger {
    @RemoteOperation(path="/api/remote-ledger/record", method=HttpMethod.POST, access=AccessPolicy.ADMIN)
    Receipt record(Command command);
    Receipt record(String localOnly);
    @RemoteOperation(path="/api/remote-ledger/proto", method=HttpMethod.POST, access=AccessPolicy.ADMIN)
    NamedValue __proto__(NamedValue value);
    @RemoteOperation(path="/api/remote-ledger/routes", method=HttpMethod.POST, access=AccessPolicy.ADMIN)
    NamedValue routes(NamedValue value);
    @ValueSchema record NamedValue(@ValueProperty(required=true, nullable=false) String __proto__) {}
    enum Mode { NORMAL, FAIL, BLOCK }
    @ValueSchema record Command(
        @ValueProperty(required=true, nullable=false, minimum="-9007199254740991", maximum="9007199254740991") long sequence,
        @ValueProperty(required=true, nullable=false, maxLength=8) String text,
        @ValueProperty(required=false, nullable=true) String note,
        @ValueProperty(required=true, nullable=false, elementsNullable=false, maxItems=8) List<Integer> numbers,
        @ValueProperty(required=true, nullable=false, elementsNullable=false) Map<String,String> labels,
        @ValueProperty(required=true, nullable=false) Mode mode) {}
    @ValueSchema record Receipt(
        @ValueProperty(required=true, nullable=false, minimum="-9007199254740991", maximum="9007199254740991") long sequence,
        @ValueProperty(required=true, nullable=false, maxLength=8) String text,
        @ValueProperty(required=true, nullable=true) String note,
        @ValueProperty(required=true, nullable=false, elementsNullable=false, maxItems=8) List<Integer> numbers,
        @ValueProperty(required=true, nullable=false, elementsNullable=false) Map<String,String> labels,
        @ValueProperty(required=true, nullable=false) Mode mode) {}
}
