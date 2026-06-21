package top.sywyar.pixivdownload.quota.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class AdminPackRequest {

    @NotEmpty(message = "{validation.archive.pack.artwork-ids.required}")
    private List<Long> artworkIds;
}
