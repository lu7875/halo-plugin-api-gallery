package io.kbmp.gallery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 管理与诊断类接口，**不向访客放行**。
 *
 * <p>这些接口会暴露图源地址、触发实时的服务端 HTTP 请求、或写入/删除图库记录，
 * 因此独立于 {@link GalleryEndpoint} 的公开组，只保留给已授权的管理端使用者。
 * 插件不会为这个组向匿名角色做任何授权，匿名访问会被 Halo 直接拦下。
 */
@Slf4j
@Component
public class GalleryAdminEndpoint implements CustomEndpoint {

    private final GalleryService service;

    public GalleryAdminEndpoint(GalleryService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions
            .route(RequestPredicates.GET("/sources"), this::sources)
            .andRoute(RequestPredicates.GET("/diagnose"), this::diagnose)
            .andRoute(RequestPredicates.POST("/sync"), this::sync)
            .andRoute(RequestPredicates.POST("/push-to-photos"), this::pushToPhotos)
            .andRoute(RequestPredicates.DELETE("/photos"), this::retractPhotos);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(GalleryAccessSetup.ADMIN_API_GROUP + "/v1alpha1");
    }

    /** GET /sources —— 各数据源与其同步状态（含图源地址，仅管理端） */
    private Mono<ServerResponse> sources(ServerRequest request) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GallerySourceConfig src : service.getSources()) {
            String key = src.displayNameOrDefault(src.getApiUrl());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", key);
            m.put("apiUrl", src.getApiUrl());
            m.put("enabled", src.isEnabled());
            m.put("parser", src.getParser());
            m.put("count", service.bySource(key).size());
            Instant at = service.getLastSyncAt().get(key);
            m.put("lastSyncAt", at == null ? null : at.toString());
            m.put("error", service.getLastError().get(key));
            list.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", list);
        body.put("total", service.count());
        body.put("intervalMinutes", service.getIntervalMinutes());
        body.put("nextSyncAt", service.getNextSyncAt() == null ? null
            : service.getNextSyncAt().toString());
        body.put("secondsUntilNextSync", service.secondsUntilNextSync());
        body.put("lastSync", service.getLastSync() == null ? null
            : service.getLastSync().toString());
        body.put("errors", service.getLastError());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    /**
     * GET /diagnose —— 真实请求一次各数据源，返回状态码与解析概况，用于排查没图的问题。
     *
     * <p>仅管理端可访问，且不回显响应正文与调用堆栈。
     */
    private Mono<ServerResponse> diagnose(ServerRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", service.diagnose());
        body.put("cachedCount", service.count());
        body.put("lastSync", service.getLastSync() == null ? null
            : service.getLastSync().toString());
        body.put("intervalMinutes", service.getIntervalMinutes());
        body.put("nextSyncAt", service.getNextSyncAt() == null ? null
            : service.getNextSyncAt().toString());
        body.put("secondsUntilNextSync", service.secondsUntilNextSync());
        body.put("errors", service.getLastError());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    /** POST /sync —— 手动触发同步（后台按钮用，强制立即执行） */
    private Mono<ServerResponse> sync(ServerRequest request) {
        int n = service.sync(true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", n);
        body.put("syncedAt", Instant.now().toString());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    /** POST /push-to-photos —— 手动把当前缓存推送到图库 */
    private Mono<ServerResponse> pushToPhotos(ServerRequest request) {
        PhotoBridgeService.PushResult r = service.pushToPhotos();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", r.created);
        body.put("updated", r.updated);
        body.put("failed", r.failed);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    /** DELETE /photos —— 撤回本插件写入图库的记录（不动你手工上传的照片） */
    private Mono<ServerResponse> retractPhotos(ServerRequest request) {
        int n = service.retractFromPhotos();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", n);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
