package acceptance.remotecontract.encoded;

import java.time.*;
import java.util.UUID;
import top.sywyar.pixivdownload.plugin.api.contract.schema.*;
import top.sywyar.pixivdownload.plugin.api.contract.remote.*;
import top.sywyar.pixivdownload.plugin.api.web.*;

/** 与另一包同名的独立服务，覆盖跨语言完整数值范围。 */
@RemoteService(owner="remote-ledger-contract")
public interface RemoteLedger {
    @RemoteOperation(path="/api/remote-values/round-trip", method=HttpMethod.POST, access=AccessPolicy.ADMIN)
    Values roundTrip(Values request);

    @ValueSchema record Values(
            @ValueProperty(required=true,nullable=false,encoding=ValueEncoding.DECIMAL_STRING) long sequence,
            @ValueProperty(required=true,nullable=false) UUID id,
            @ValueProperty(required=true,nullable=false) Instant instant,
            @ValueProperty(required=true,nullable=false) Duration duration,
            @ValueProperty(required=true,nullable=false) float score,
            @ValueProperty(required=true,nullable=false) double ratio) {}
}
