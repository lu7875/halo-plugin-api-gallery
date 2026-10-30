package io.kbmp.gallery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
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
 * 对外**公开**的图库只读接口，供主题或前端页面匿名调用。
 *
 * <p>接口只返回图片**地址等元数据**，图片由第三方图源直接提供，
 * 请求方浏览器直连图源 CDN，不经过也不占用 Halo 的存储。
 *
 * <p>安全边界：这个组只暴露「看图」所需的两个只读接口。
 * 数据源明细（含图源地址）、诊断、同步、写入图库等能力全部下沉到
 * {@link GalleryAdminEndpoint} 所在的 {@code console.gallery.halo.run} 组，
 * 不向匿名访客放行，避免图源地址泄露与服务端请求被滥用。
 */
@Slf4j
@Component
public class GalleryEndpoint implements CustomEndpoint {

    private final GalleryService service;

    public GalleryEndpoint(GalleryService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions
            .route(RequestPredicates.GET("/items").and(RequestPredicates.accept(MediaType.APPLICATION_JSON)),
                this::items)
            .andRoute(RequestPredicates.GET("/items"), this::items)
            .andRoute(RequestPredicates.GET("/random"), this::random);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(GalleryAccessSetup.API_GROUP + "/v1alpha1");
    }

    /** GET /items?source=&page=1&size=20 */
    private Mono<ServerResponse> items(ServerRequest request) {
        String source = param(request, "source", null);
        int page = intParam(request, "page", 1);
        int size = intParam(request, "size", 20);

        List<GalleryImageItem> matched = service.bySource(source);
        int total = matched.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<GalleryImageItem> slice =
            from >= total ? new ArrayList<>() : new ArrayList<>(matched.subList(from, to));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", slice);
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        body.put("totalPages", size <= 0 ? 0 : (int) Math.ceil((double) total / size));
        body.put("lastSync", service.getLastSync() == null ? null
            : service.getLastSync().toString());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    /** GET /random?source= —— 随机一张，可用于站点头图/背景 */
    private Mono<ServerResponse> random(ServerRequest request) {
        String source = param(request, "source", null);
        List<GalleryImageItem> pool = service.bySource(source);
        if (pool.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("item", null);
            empty.put("message", "暂无数据，请先在后台配置并同步数据源");
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(empty);
        }
        GalleryImageItem one = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("item", one);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private String param(ServerRequest request, String name, String def) {
        String v = request.queryParam(name).orElse(def);
        return (v == null || v.isBlank()) ? null : v;
    }

    private int intParam(ServerRequest request, String name, int def) {
        try {
            String v = request.queryParam(name).orElse(null);
            if (v == null || v.isBlank()) {
                return def;
            }
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
