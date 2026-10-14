package com.lux.backend.asset;

import com.lux.backend.user.UserService;
import com.lux.backend.common.Ids;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 资源 HTTP 入口：上传、元数据查询和私有内容下载。
 * 路径中的 user_id 用于选择本地用户范围，当前并不代表登录认证。
 */
@RestController
public class AssetController {
    /** 用户查询服务，用于确认路径用户存在并取得用户身份。 */
    private final UserService users;
    /** 资源业务服务，负责上传幂等、归属查询和元数据协调。 */
    private final AssetService assets;
    /** 文件 HTTP 响应组件，封装 HEAD、Range 和流式传输。 */
    private final AssetContentResponse contentResponse;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public AssetController(
        AssetService assets,
        AssetStore store,
        UserService users
    ) {
        this.users = users;
        this.assets = assets;
        this.contentResponse = new AssetContentResponse(store);
    }

    /** 接收 multipart 文件，交给上传服务按操作 ID 幂等保存。 */
    @PostMapping(path = "/api/v1/users/{user_id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssetRecord upload(
        @PathVariable("user_id") String userId,
        @RequestPart("file") MultipartFile file,
        @RequestHeader("Idempotency-Key") String operationId
    ) {
        String owner = users.get(Ids.require(userId)).id();
        return assets.upload(owner, file, operationId);
    }

    /** 在当前用户范围内读取资源元数据，并禁止 HTTP 缓存。 */
    @GetMapping({"/api/v1/users/{user_id}/assets/{id}", "/internal/v1/users/{user_id}/assets/{id}"})
    public ResponseEntity<AssetRecord> get(
        @PathVariable("user_id") String userId,
        @PathVariable String id
    ) {
        String owner = users.get(Ids.require(userId)).id();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                             .header("X-Content-Type-Options", "nosniff")
                             .body(assets.get(owner, id));
    }

    /** 先读取并校验资源归属，再把底层字节流写入 HTTP 响应。 */
    @GetMapping({"/api/v1/users/{user_id}/assets/{id}/content", "/internal/v1/users/{user_id}/assets/{id}/content"})
    public void content(
        @PathVariable("user_id") String userId,
        @PathVariable String id,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws IOException {
        String owner = users.get(Ids.require(userId)).id();
        AssetRecord asset = assets.get(owner, id);
        contentResponse.write(asset, request, response);
    }
}
