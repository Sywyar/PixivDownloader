package top.sywyar.pixivdownload.gui;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

/** 已退役图片分类工具的兼容入口。 */
@HttpApiGroup("core-classifier")
@RestController
@RequestMapping("/api")
@ResponseStatus(HttpStatus.GONE)
@ApiResponse(responseCode = "410", content = @Content)
public final class ImageClassifierController {
    @Operation(deprecated = true)
    @GetMapping("/tools/classifier/settings")
    public void settings() {}

    @Operation(deprecated = true)
    @PutMapping("/tools/classifier/settings")
    public void configure() {}

    @Operation(deprecated = true)
    @GetMapping("/tools/classifier/folders")
    public void folders() {}

    @Operation(deprecated = true)
    @DeleteMapping("/tools/classifier/folders")
    public void deleteEmpty() {}

    @Operation(deprecated = true)
    @GetMapping("/tools/classifier/images")
    public void images() {}

    @Operation(deprecated = true)
    @GetMapping("/tools/classifier/image")
    public void image() {}

    @Operation(deprecated = true)
    @PostMapping("/tools/classifier/moves")
    public void move() {}

    @Operation(deprecated = true)
    @GetMapping("/downloaded/by-move-folder")
    public void getArtworkByMoveFolder() {}

    @Operation(deprecated = true)
    @Parameter(name = "artworkId", in = ParameterIn.PATH, required = true, schema = @Schema(type = "string"))
    @PostMapping("/downloaded/move/{artworkId}")
    public void legacyMove() {}
}
