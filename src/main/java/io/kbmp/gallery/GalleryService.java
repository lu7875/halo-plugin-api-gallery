package io.kbmp.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;

/**
 * 图库核心服务：读取后台配置的数据源，定时拉取并把**图片元数据**缓存到内存。
 *
 * <p>图片文件本身始终由第三方图源提供，插件不会下载、不会写入 Halo 附件库，
 * 因此不占用任何本地存储。
 */
@Slf4j
@Component
public class GalleryService {

    /** 与 plugin.yaml 的 spec.configMapName 对应 */
    public static final String CONFIG_MAP_NAME = "api-gallery-config";
    public static final String SOURCES_KEY = "sources";
    public static final String BASIC_KEY = "basic";
    public static final long DEFAULT_INTERVAL_MINUTES = 60;
    /** 两次同步之间的最小间隔，用于合并重复触发 */
    private static final long MIN_SYNC_INTERVAL_MILLIS = 2000;

    private final ExtensionClient client;
    private final PhotoBridgeService photoBridge;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 当前生效的数据源配置 */
    private volatile List<GallerySourceConfig> sources = List.of();
    /** 图片元数据缓存 */
    private final List<GalleryImageItem> cache = new CopyOnWriteArrayList<>();
    /** 每个数据源上次同步的时间 */
    private final Map<String, Instant> lastSyncAt = new LinkedHashMap<>();
    /** 上次同步中失败的数据源及原因 */
    private final Map<String, String> lastError = new LinkedHashMap<>();

    private volatile Instant lastSync = null;
    /** 下次周期同步的时间，供后台/接口确认「自动同步到底排上了没」 */
    private volatile Instant nextSyncAt = null;
    private volatile long intervalMinutes = DEFAULT_INTERVAL_MINUTES;
    /** 上一轮已经写进 Halo 图库的条目，用于淘汰被滚动掉的旧图 */
    private volatile List<GalleryImageItem> lastPushed = List.of();

    public GalleryService(ExtensionClient client, PhotoBridgeService photoBridge) {
        this.client = client;
        this.photoBridge = photoBridge;
    }

    // ------------------------------------------------------------ 配置

    /** 从 Halo 后台保存的配置中读取数据源列表；未配置时回退到内置示例源 */
    public List<GallerySourceConfig> loadSources() {
        try {
            var cm = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
            if (cm.isEmpty()) {
                return defaultSources();
            }
            var data = cm.get().getData();
            if (data == null) {
                return defaultSources();
            }
            Object raw = data.get(SOURCES_KEY);
            if (raw == null) {
                return defaultSources();
            }
            List<GallerySourceConfig> parsed = parseSources(raw);
            return parsed.isEmpty() ? defaultSources() : parsed;
        } catch (Exception e) {
            log.warn("[api-gallery] 读取数据源配置失败", e);
            return defaultSources();
        }
    }

    /** 内置示例源：装完即有图可看，用户在后台保存自己的配置后自动失效 */
    private List<GallerySourceConfig> defaultSources() {
        GallerySourceConfig pai = new GallerySourceConfig();
        pai.setName("派次元（示例）");
        pai.setApiUrl("https://t.paiii.cn/api/list");
        pai.setParser("auto");
        pai.setEnabled(true);
        return List.of(pai);
    }

    /** 读取全局同步周期（分钟），默认 60 */
    public long loadSyncIntervalMinutes() {
        try {
            var cm = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
            if (cm.isEmpty() || cm.get().getData() == null) {
                return DEFAULT_INTERVAL_MINUTES;
            }
            Object raw = cm.get().getData().get(BASIC_KEY);
            if (raw == null) {
                return DEFAULT_INTERVAL_MINUTES;
            }
            Object parsed = raw instanceof String s && !s.isBlank()
                ? mapper.readValue(s, Object.class) : raw;
            if (parsed instanceof Map<?, ?> map) {
                Object v = map.get("syncIntervalMinutes");
                if (v != null) {
                    long n = Long.parseLong(String.valueOf(v));
                    return Math.max(5, Math.min(1440, n));
                }
            }
        } catch (Exception e) {
            log.debug("[api-gallery] 读取同步周期失败，使用默认值", e);
        }
        return DEFAULT_INTERVAL_MINUTES;
    }

    /** 是否允许访客（未登录）读取图库接口，默认 true */
    public boolean loadAllowAnonymous() {
        try {
            var cm = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
            if (cm.isEmpty() || cm.get().getData() == null) {
                return true;
            }
            Object raw = cm.get().getData().get(BASIC_KEY);
            if (raw == null) {
                return true;
            }
            Object parsed = raw instanceof String s && !s.isBlank()
                ? mapper.readValue(s, Object.class) : raw;
            if (parsed instanceof Map<?, ?> map && map.get("allowAnonymous") != null) {
                return Boolean.parseBoolean(String.valueOf(map.get("allowAnonymous")));
            }
        } catch (Exception e) {
            log.debug("[api-gallery] 读取访客访问开关失败，按允许处理", e);
        }
        return true;
    }

    /** 是否把图片元数据同步进「图库管理」插件（/console/photos），默认 true */
    public boolean loadPushToPhotos() {
        try {
            var cm = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
            if (cm.isEmpty() || cm.get().getData() == null) {
                return true;
            }
            Object raw = cm.get().getData().get(BASIC_KEY);
            if (raw == null) {
                return true;
            }
            Object parsed = raw instanceof String s && !s.isBlank()
                ? mapper.readValue(s, Object.class) : raw;
            if (parsed instanceof Map<?, ?> map && map.get("pushToPhotos") != null) {
                return Boolean.parseBoolean(String.valueOf(map.get("pushToPhotos")));
            }
        } catch (Exception e) {
            log.debug("[api-gallery] 读取图库同步开关失败，按开启处理", e);
        }
        return true;
    }

    /** 图库分组名，留空表示按数据源名自动分组 */
    public String loadPhotoGroupName() {
        try {
            var cm = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
            if (cm.isEmpty() || cm.get().getData() == null) {
                return null;
            }
            Object raw = cm.get().getData().get(BASIC_KEY);
            if (raw == null) {
                return null;
            }
            Object parsed = raw instanceof String s && !s.isBlank()
                ? mapper.readValue(s, Object.class) : raw;
            if (parsed instanceof Map<?, ?> map) {
                Object v = map.get("photoGroupName");
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    return s.isEmpty() ? null : s;
                }
            }
        } catch (Exception e) {
            log.debug("[api-gallery] 读取图库分组名失败", e);
        }
        return null;
    }

    /** 手动推送到图库（后台按钮用） */
    public PhotoBridgeService.PushResult pushToPhotos() {
        return photoBridge.push(all(), loadPhotoGroupName());
    }

    /** 撤回本插件写入图库的记录（连带清理本插件建的分组） */
    public int retractFromPhotos() {
        int n = photoBridge.retract(all());
        photoBridge.retractGroups();
        return n;
    }

    /** 兼容 repeater 存成数组或 {"sources":[...]} 两种形态（包可见以便单测） */
    @SuppressWarnings("unchecked")
    List<GallerySourceConfig> parseSources(Object raw) throws Exception {
        Object parsed = raw;
        if (raw instanceof String s) {
            if (s.isBlank()) {
                return List.of();
            }
            try {
                parsed = mapper.readValue(s, Object.class);
            } catch (Exception ignore) {
                // 不是合法 JSON，可能是空占位
                return List.of();
            }
        }
        if (parsed instanceof Map<?, ?> map && map.containsKey(SOURCES_KEY)) {
            parsed = map.get(SOURCES_KEY);
        }
        if (parsed instanceof Collection<?> coll) {
            List<GallerySourceConfig> out = new ArrayList<>();
            for (Object o : coll) {
                GallerySourceConfig c = mapper.convertValue(o, GallerySourceConfig.class);
                if (c != null && c.getApiUrl() != null && !c.getApiUrl().isBlank()) {
                    out.add(c);
                }
            }
            return out;
        }
        return List.of();
    }

    /** 重新加载配置（后台新增/修改数据源后调用） */
    public void reloadSources() {
        this.sources = loadSources();
        log.info("[api-gallery] 数据源已重载，共 {} 个", this.sources.size());
    }

    public List<GallerySourceConfig> getSources() {
        if (sources.isEmpty()) {
            reloadSources();
        }
        return sources;
    }

    // ------------------------------------------------------------ 同步

    /** 同步所有启用的数据源（2 秒内重复调用会被节流，避免多条触发路径重复拉图源） */
    public synchronized int sync() {
        return sync(false);
    }

    /** force = true 时忽略节流，立即同步（后台手动点「立即同步」用） */
    public synchronized int sync(boolean force) {
        if (!force && lastSync != null
            && Duration.between(lastSync, Instant.now()).toMillis() < MIN_SYNC_INTERVAL_MILLIS) {
            log.debug("[api-gallery] 距上次同步不足 {} ms，跳过本次", MIN_SYNC_INTERVAL_MILLIS);
            return cache.size();
        }
        reloadSources();
        List<GalleryImageItem> fresh = new ArrayList<>();
        lastError.clear();
        int ok = 0;
        for (GallerySourceConfig src : sources) {
            if (!src.usable()) {
                continue;
            }
            String key = keyOf(src);
            try {
                List<GalleryImageItem> items = fetchSource(src);
                if (src.isSampled()) {
                    // 随机源：新采样的排前面、旧缓存排后面。
                    // 达到 maxItems 上限时被截掉的是最老的那些，
                    // 这样每一轮同步都会换进一批新图（滚动更新），不会攒满就静止。
                    fresh.addAll(items);
                    fresh.addAll(cache.stream().filter(i -> key.equals(i.getSource())).toList());
                } else {
                    fresh.addAll(items);
                }
                lastSyncAt.put(key, Instant.now());
                ok++;
                log.info("[api-gallery] 数据源「{}」同步到 {} 张", key, items.size());
            } catch (Throwable t) {
                lastError.put(key, describe(t));
                // 随机源拉失败时也要保住已经攒下的图，不能清空
                if (src.isSampled()) {
                    fresh.addAll(cache.stream().filter(i -> key.equals(i.getSource())).toList());
                }
                log.warn("[api-gallery] 数据源「{}」同步失败", src.getApiUrl(), t);
            }
        }
        cache.clear();
        cache.addAll(dedupe(fresh, sources));
        lastSync = Instant.now();
        // 周期任务是「跑完再等一个周期」，所以下次时间从这里重新算
        nextSyncAt = Instant.now().plus(Duration.ofMinutes(intervalMinutes));
        syncPhotosIfNeeded();
        return fresh.size();
    }

    /** 图源地址安全校验策略 */
    @FunctionalInterface
    interface UrlChecker {
        String check(String url);
    }

    /** 默认走严格的 {@link ApiUrlSafety#check(String)}；单元测试本地起服务时可替换 */
    private volatile UrlChecker urlChecker = ApiUrlSafety::check;

    void setUrlChecker(UrlChecker checker) {
        this.urlChecker = checker == null ? ApiUrlSafety::check : checker;
    }

    /**
     * 构建请求用的 URI，发请求前强制做一次 SSRF 安全校验。
     *
     * <p>插件的所有对外请求都必须经过这里，不允许直接 {@code URI.create(apiUrl)}，
     * 否则图源地址可能被指向内网服务，造成服务端请求伪造。
     */
    URI safeUri(GallerySourceConfig src) {
        return URI.create(urlChecker.check(src.getApiUrl()));
    }

    /**
     * 构建**不跟随重定向**的 HttpClient。
     *
     * <p>所有对外请求一律关闭自动跟随跳转（{@code Redirect.NEVER}）。需要跨跳转的请求
     * 由 {@link #sendFollowingRedirects(String, java.util.function.Function, int)}
     * 手动逐跳跟随，并在每一跳跳转前重新校验目标地址，防止 3xx 把服务器引导到
     * 内网、回环或云元数据地址。
     *
     * <p>JDK 的 HttpClient 默认**不读** http.proxyHost 等系统属性，也不会走系统代理，
     * 这在需要代理才能出网的服务器上会直接 ConnectException。
     * 所以这里按「后台配置 → JVM 系统属性 → 直连」的顺序决定代理。
     */
    private HttpClient http() {
        var builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER);
        var proxy = resolveProxy();
        if (proxy.isPresent()) {
            builder.proxy(java.net.ProxySelector.of(proxy.get()));
            log.info("[api-gallery] 使用代理访问图源：{}", proxy.get());
        }
        return builder.build();
    }

    /**
     * 发送请求并**安全地手动跟随重定向**。
     *
     * <p>JDK 的 {@link HttpClient} 在 {@code Redirect.NORMAL} 下会自动跟随 301/302/303/307/308，
     * 但跳转后的目标地址不会再次经过 SSRF 校验——恶意图源可以先返回一个公网地址的 302，
     * 再在 Location 里指向内网、回环或云元数据地址，从而绕过地址校验。
     *
     * <p>因此这里关闭自动跟随（{@code Redirect.NEVER}），改为手动跟随：每一跳跳转前都对目标地址
     * 重新执行 {@link #urlChecker}（协议 + 主机 + DNS 解析 IP）校验，确认跳转目标同样是
     * 公网 http/https 地址后才继续请求，并限制最大跳数防止重定向循环。
     *
     * @param startUrl       起始地址（须为已校验的公网地址）
     * @param customize      对每跳请求补充 header 等（可为 null）
     * @param timeoutSeconds 每跳的超时秒数
     * @return 最终响应（未发生跳转时为第一跳响应）
     */
    private HttpResponse<String> sendFollowingRedirects(String startUrl,
        java.util.function.Function<HttpRequest.Builder, HttpRequest.Builder> customize,
        int timeoutSeconds) throws Exception {
        String currentUrl = urlChecker.check(startUrl);
        HttpClient client = http();
        int maxHops = 5;
        for (int hop = 0; hop <= maxHops; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(currentUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
            if (customize != null) {
                builder = customize.apply(builder);
            }
            HttpResponse<String> resp =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (!isRedirect(code)) {
                return resp;
            }
            String location = firstHeader(resp, "Location", "location");
            if (location == null || location.isBlank()) {
                // 3xx 但没有 Location，无法继续，返回该响应让上层按状态码处理
                return resp;
            }
            String next = resolveLocation(currentUrl, location);
            log.debug("[api-gallery] 跟随重定向（第 {} 跳）：{} -> {}", hop + 1, currentUrl, next);
            currentUrl = urlChecker.check(next);
        }
        throw new IllegalStateException("重定向次数超过 " + maxHops + " 次，已中止");
    }

    /** 3xx 重定向状态码 */
    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** 把 Location 解析成基于当前地址的绝对 URL（支持相对路径与协议相对路径） */
    private static String resolveLocation(String base, String location) {
        try {
            return URI.create(base).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("重定向地址不合法：" + location);
        }
    }

    /** 后台配置优先，其次 JVM 系统属性 */
    private java.util.Optional<java.net.InetSocketAddress> resolveProxy() {
        String host = loadProxyConfig("proxyHost");
        int port = loadProxyPort();
        if (host == null || host.isBlank() || port <= 0) {
            host = System.getProperty("http.proxyHost");
            String p = System.getProperty("http.proxyPort");
            if (host != null && !host.isBlank()) {
                try {
                    port = Integer.parseInt(p == null ? "80" : p.trim());
                } catch (NumberFormatException e) {
                    port = 80;
                }
            }
        }
        if (host == null || host.isBlank() || port <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new java.net.InetSocketAddress(host.trim(), port));
    }

    /** 读取同步设置里的代理配置 */
    private String loadProxyConfig(String key) {
        try {
            var cm = client.fetch(ConfigMap.class, CONFIG_MAP_NAME);
            if (cm.isEmpty() || cm.get().getData() == null) {
                return null;
            }
            Object raw = cm.get().getData().get(BASIC_KEY);
            if (raw == null) {
                return null;
            }
            Object parsed = raw instanceof String s && !s.isBlank()
                ? mapper.readValue(s, Object.class) : raw;
            if (parsed instanceof Map<?, ?> map) {
                Object v = map.get(key);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    return s.isEmpty() ? null : s;
                }
            }
        } catch (Exception e) {
            log.debug("[api-gallery] 读取代理配置失败", e);
        }
        return null;
    }

    private int loadProxyPort() {
        try {
            String v = loadProxyConfig("proxyPort");
            return v == null ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 把异常压成一行可读文本：类名 + 消息 + 堆栈首行 */
    public static String describe(Throwable t) {
        String head = t.getClass().getName();
        String msg = t.getMessage();
        StringBuilder sb = new StringBuilder(head);
        if (msg != null && !msg.isBlank()) {
            sb.append(": ").append(msg);
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append(" @ ").append(st[0]);
        }
        return sb.toString();
    }

    /** 判断响应是不是 JSON，用于替代「回显正文」这一有泄露风险的做法 */
    private static boolean looksLikeJson(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String trimmed = body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * 诊断：逐个数据源真实请求一次，返回状态码、解析条数或异常类型，
     * 用于在「配好了却没图」时快速定位。
     *
     * <p>该结果**只对管理员开放**（见 GalleryAdminEndpoint），且不回显响应正文与调用堆栈，
     * 避免被用于探测内网或泄露服务端细节。
     */
    public List<Map<String, Object>> diagnose() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GallerySourceConfig src : getSources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            String name = src.displayNameOrDefault(src.getApiUrl());
            m.put("name", name);
            m.put("apiUrl", src.getApiUrl());
            m.put("enabled", src.isEnabled());
            m.put("usable", src.usable());
            m.put("parser", src.getParser());
            long t0 = System.currentTimeMillis();
            if (src.isSampled()) {
                diagnoseSampled(m, src);
                m.put("elapsedMs", System.currentTimeMillis() - t0);
                out.add(m);
                continue;
            }
            try {
                HttpResponse<String> resp = sendFollowingRedirects(src.getApiUrl(),
                    b -> b.header("Accept", "application/json, text/plain, */*")
                        .header("User-Agent", "halo-plugin-api-gallery/1.0"),
                    30);
                m.put("httpStatus", resp.statusCode());
                String body = resp.body();
                m.put("bodyLength", body == null ? 0 : body.length());
                // 出于安全考虑不回显响应正文，只给「看起来是不是 JSON」这一结论，
                // 足够判断「地址填错导致返回了图片二进制」这类问题
                m.put("looksLikeJson", looksLikeJson(body));
                Object json = mapper.readValue(body, Object.class);
                List<Map<String, Object>> rows = extractRows(json, src.getParser());
                m.put("rows", rows.size());
                m.put("firstRow", rows.isEmpty() ? null : rows.get(0));
            } catch (Throwable t) {
                m.put("exception", t.getClass().getName());
                m.put("message", String.valueOf(t.getMessage()));
                // 不返回调用堆栈，避免向调用方泄露内部实现细节
            }
            m.put("elapsedMs", System.currentTimeMillis() - t0);
            out.add(m);
        }
        return out;
    }

    /** 随机图源的诊断：小样本采几次，看每次拿到的图是不是不一样 */
    private void diagnoseSampled(Map<String, Object> m, GallerySourceConfig src) {
        boolean redirect = src.isRedirect();
        m.put("mode", redirect ? "redirect" : "random-json");
        m.put("sampleCount", src.sampleCountOrDefault());
        m.put("maxItems", src.maxItemsOrDefault());
        java.util.Set<String> urls = new java.util.LinkedHashSet<>();
        int status = 0;
        String location = null;
        String failure = null;
        try {
            HttpClient client = redirect ? http() : null;
            for (int i = 0; i < 5; i++) {
                String one = null;
                if (redirect) {
                    HttpRequest req = HttpRequest.newBuilder(safeUri(src))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "image/*,*/*")
                        .header("User-Agent", "halo-plugin-api-gallery/1.2")
                        .GET()
                        .build();
                    HttpResponse<Void> resp =
                        client.send(req, HttpResponse.BodyHandlers.discarding());
                    status = resp.statusCode();
                    one = firstHeader(resp, "Location", "location");
                } else {
                    HttpResponse<String> resp = sendFollowingRedirects(src.getApiUrl(),
                        b -> b.header("Accept", "application/json, */*")
                            .header("User-Agent", "halo-plugin-api-gallery/1.2"),
                        20);
                    status = resp.statusCode();
                    String body = resp.body();
                    if (body != null && !body.isBlank()) {
                        List<Map<String, Object>> rows =
                            extractRows(mapper.readValue(body, Object.class), "auto");
                        if (!rows.isEmpty()) {
                            one = pickString(rows.get(0), src.getUrlField(),
                                GallerySourceConfig.defaultUrlFields());
                        }
                    }
                }
                if (one != null && !one.isBlank()) {
                    location = one;
                    urls.add(one);
                }
                Thread.sleep(120);
            }
        } catch (Throwable t) {
            failure = describe(t);
        }
        m.put("httpStatus", status);
        m.put("location", location);
        m.put("uniqueUrlsIn5Tries", urls.size());
        m.put("sampleUrls", urls.stream().limit(3).toList());
        if (failure != null) {
            m.put("exception", failure);
        }
        if (redirect && status > 0 && status < 300) {
            m.put("hint", "该地址没有返回 3xx 跳转，确认是不是随机图接口（如 /random、/meinv）");
        } else if (urls.isEmpty() && failure == null) {
            m.put("hint", redirect
                ? "拿到了跳转状态码但读不到 Location 头"
                : "响应里没有解析出图片地址，确认返回的是不是图片对象 JSON");
        } else if (urls.size() <= 1) {
            m.put("hint", "5 次采样只得到 1 张不同的图，这个接口可能是固定内容而不是随机的——"
                + "固定列表源同步多少次都一样，请改用「自动识别」类型");
        }
    }

    /** 同步完成后按需把元数据写进图库（失败不影响图库接口本身） */
    private void syncPhotosIfNeeded() {
        try {
            if (photoBridge == null || !loadPushToPhotos()) {
                return;
            }
            List<GalleryImageItem> current = List.copyOf(cache);
            // 滚动更新会淘汰一批旧图，图库里对应的记录也要跟着删，否则只增不减
            List<GalleryImageItem> dropped = new ArrayList<>();
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (GalleryImageItem item : current) {
                if (item != null && item.getUrl() != null) {
                    keep.add(item.getUrl());
                }
            }
            for (GalleryImageItem old : lastPushed) {
                if (old != null && old.getUrl() != null && !keep.contains(old.getUrl())) {
                    dropped.add(old);
                }
            }
            if (!dropped.isEmpty()) {
                int n = photoBridge.retract(dropped);
                log.info("[api-gallery] 已从图库移除 {} 张被滚动淘汰的旧图", n);
            }
            photoBridge.push(current, loadPhotoGroupName());
            lastPushed = current;
        } catch (Exception e) {
            log.warn("[api-gallery] 同步到图库时出错（图库接口不受影响）：{}", e.getMessage());
        }
    }

    /** 只同步指定数据源（后台新增时即时生效用） */
    public synchronized int syncOne(GallerySourceConfig src) {
        if (!src.usable()) {
            return 0;
        }
        try {
            List<GalleryImageItem> items = fetchSource(src);
            String key = keyOf(src);
            if (src.isSampled()) {
                // 与 sync() 保持一致：新采样的排前面，达到上限时淘汰最老的
                items = dedupe(concat(items, cache.stream().filter(i -> key.equals(i.getSource()))
                    .toList()), getSources());
            }
            // 先剔除该数据源的旧数据，再写入新的
            cache.removeIf(i -> key.equals(i.getSource()));
            cache.addAll(items);
            lastSyncAt.put(key, Instant.now());
            lastError.remove(key);
            lastSync = Instant.now();
            return items.size();
        } catch (Exception e) {
            lastError.put(keyOf(src), String.valueOf(e.getMessage()));
            log.warn("[api-gallery] 数据源同步失败：{}", e.getMessage());
            return 0;
        }
    }

    private String keyOf(GallerySourceConfig src) {
        return src.displayNameOrDefault(src.getApiUrl());
    }

    /**
     * 按 (数据源, 图片地址) 去重，并对随机源套用累积上限。
     *
     * <p>随机源每次同步都会抽到新图，不加上限的话图库会一直膨胀，
     * 最后同步进 Halo 图库时会有成千上万条记录。
     */
    List<GalleryImageItem> dedupe(List<GalleryImageItem> items,
        List<GallerySourceConfig> sources) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (GallerySourceConfig src : sources) {
            if (src.isSampled()) {
                limits.put(keyOf(src), src.maxItemsOrDefault());
            }
        }
        Map<String, Integer> used = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<GalleryImageItem> out = new ArrayList<>();
        for (GalleryImageItem item : items) {
            if (item == null || item.getUrl() == null || item.getUrl().isBlank()) {
                continue;
            }
            String dedupeKey = item.getSource() + "|" + item.getUrl();
            if (!seen.add(dedupeKey)) {
                continue;
            }
            Integer limit = limits.get(item.getSource());
            if (limit != null) {
                int n = used.getOrDefault(item.getSource(), 0);
                if (n >= limit) {
                    continue;
                }
                used.put(item.getSource(), n + 1);
            }
            out.add(item);
        }
        return out;
    }

    /** 拉取并解析一个数据源 */
    List<GalleryImageItem> fetchSource(GallerySourceConfig src) throws Exception {
        if (src.isSampled()) {
            return fetchSampledSource(src);
        }
        HttpResponse<String> resp = sendFollowingRedirects(src.getApiUrl(),
            b -> b.header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "halo-plugin-api-gallery/1.0"),
            30);
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }

        Object json = mapper.readValue(body, Object.class);
        List<Map<String, Object>> rows = extractRows(json, src.getParser());
        String sourceName = keyOf(src);

        List<GalleryImageItem> out = new ArrayList<>();
        int seq = 0;
        for (Map<String, Object> row : rows) {
            String url = pickString(row, src.getUrlField(), GallerySourceConfig.defaultUrlFields());
            if (url == null || url.isBlank()) {
                continue;
            }
            String id = pickString(row, src.getIdField(), GallerySourceConfig.defaultIdFields());
            if (id == null || id.isBlank()) {
                id = sourceName + "#" + (seq++);
            }
            String title = pickString(row, src.getTitleField(),
                GallerySourceConfig.defaultTitleFields());
            out.add(new GalleryImageItem(id, url, title == null ? "" : title, sourceName,
                pickTags(row, src.getTagsField())));
        }
        return out;
    }

    /**
     * 拉取「采样型」随机图源：每次请求只给一张随机图，重复采样攒成图集。
     *
     * <p>两种形态：
     * <ul>
     *   <li>{@code redirect}：302 跳转到图片地址（如 random-pictures 部署的站点），读 Location 头;</li>
     *   <li>{@code random-json}：返回单张图片的 JSON（如 {@code /api/random?format=json}），解析 url 字段。</li>
     * </ul>
     *
     * <p>串行 + 小间隔，避免把免费图源打挂或触发限流。
     */
    List<GalleryImageItem> fetchSampledSource(GallerySourceConfig src) throws Exception {
        String sourceName = keyOf(src);
        String displayName = src.displayNameOrDefault(sourceName);
        int samples = src.sampleCountOrDefault();
        boolean redirect = src.isRedirect();

        HttpClient client = redirect ? http() : null;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<GalleryImageItem> out = new ArrayList<>();
        int failures = 0;

        for (int i = 0; i < samples; i++) {
            String url = null;
            List<String> tags = List.of(displayName);
            String title = null;
            try {
                if (redirect) {
                    HttpRequest req = HttpRequest.newBuilder(safeUri(src))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "image/*,*/*")
                        .header("User-Agent", "halo-plugin-api-gallery/1.2")
                        .GET()
                        .build();
                    HttpResponse<Void> resp =
                        client.send(req, HttpResponse.BodyHandlers.discarding());
                    url = firstHeader(resp, "Location", "location");
                    // 少数图源不返回 3xx 而是直接给 200 + 图片地址文本
                    if (url == null && resp.statusCode() == 200) {
                        url = readBodyAsUrl(src);
                    }
                } else {
                    HttpResponse<String> resp = sendFollowingRedirects(src.getApiUrl(),
                        b -> b.header("Accept", "application/json, text/plain, */*")
                            .header("User-Agent", "halo-plugin-api-gallery/1.2"),
                        20);
                    if (resp.statusCode() >= 400) {
                        throw new IllegalStateException("HTTP " + resp.statusCode());
                    }
                    String body = resp.body();
                    if (body != null && !body.isBlank()) {
                        Object json = mapper.readValue(body, Object.class);
                        List<Map<String, Object>> rows = extractRows(json, "auto");
                        if (!rows.isEmpty()) {
                            Map<String, Object> row = rows.get(0);
                            url = pickString(row, src.getUrlField(),
                                GallerySourceConfig.defaultUrlFields());
                            String t = pickString(row, src.getTitleField(),
                                GallerySourceConfig.defaultTitleFields());
                            title = (t == null || t.isBlank()) ? null : t;
                            List<String> picked = pickTags(row, src.getTagsField());
                            if (!picked.isEmpty()) {
                                tags = picked;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                failures++;
                if (failures >= 3) {
                    throw new IllegalStateException(
                        "随机图源连续 3 次请求失败：" + e.getClass().getSimpleName() + " "
                            + e.getMessage());
                }
            }
            if (url != null && !url.isBlank() && seen.add(url)) {
                String id = redirectId(url);
                // 文件名多为哈希串，直接当标题没法看，用「分类 + 短 ID」更好认
                String finalTitle = title != null && !title.isBlank()
                    ? displayName + " " + title
                    : displayName + " " + id.substring(4, 8);
                out.add(new GalleryImageItem(id, url, finalTitle, sourceName, tags));
            }
            // 图源多为免费服务，串行慢一点更稳妥
            if (i < samples - 1) {
                Thread.sleep(120);
            }
        }
        log.info("[api-gallery] 随机图源「{}」采样 {} 次，得到 {} 张不重复的图（失败 {} 次）",
            displayName, samples, out.size(), failures);
        return out;
    }

    private static List<GalleryImageItem> concat(List<GalleryImageItem> a,
        List<GalleryImageItem> b) {
        List<GalleryImageItem> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** 用地址本身做稳定 id，重复同步不会在图库里产生新记录 */
    private static String redirectId(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return "rnd-" + sb;
        } catch (Exception e) {
            return "rnd-" + Math.abs(url.hashCode());
        }
    }

    private static String firstHeader(HttpResponse<?> resp, String... names) {
        for (String n : names) {
            java.util.Optional<String> v = resp.headers().firstValue(n);
            if (v.isPresent() && !v.get().isBlank()) {
                return v.get();
            }
        }
        return null;
    }

    /** 少数图源返回 200 + 纯文本地址，兜底读一次 */
    private String readBodyAsUrl(GallerySourceConfig src) {
        try {
            HttpRequest req = HttpRequest.newBuilder(safeUri(src))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/plain,*/*")
                .header("User-Agent", "halo-plugin-api-gallery/1.1")
                .GET()
                .build();
            HttpResponse<String> resp =
                http().send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (body != null && body.trim().startsWith("http")) {
                return body.trim().split("\\s+")[0];
            }
        } catch (Exception ignore) {
            // 兜底失败不影响主流程
        }
        return null;
    }

    /** 按 parser 规则把响应归一成「行」的列表 */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> extractRows(Object json, String parser) {
        if (json == null) {
            return List.of();
        }
        String mode = (parser == null || parser.isBlank()) ? "auto" : parser;

        if (!"array".equals(mode) && json instanceof Map<?, ?> map) {
            Object items = map.get("items");
            if (items instanceof Collection<?> coll) {
                return toRows(coll);
            }
            // random：顶层就是一张图
            if (map.containsKey("url") || map.containsKey("id")) {
                return List.of((Map<String, Object>) map);
            }
            // 兜底：找一个数组字段
            for (Object v : map.values()) {
                if (v instanceof Collection<?> coll && !coll.isEmpty()) {
                    return toRows(coll);
                }
            }
            return List.of();
        }
        if (json instanceof Collection<?> coll) {
            return toRows(coll);
        }
        return List.of();
    }

    private List<Map<String, Object>> toRows(Collection<?> coll) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : coll) {
            if (o instanceof Map<?, ?> m) {
                rows.add((Map<String, Object>) m);
            }
        }
        return rows;
    }

    private String pickString(Map<String, Object> row, String custom, List<String> defaults) {
        if (custom != null && !custom.isBlank()) {
            Object v = row.get(custom);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        for (String f : defaults) {
            Object v = row.get(f);
            if (v != null && !(v instanceof Map) && !(v instanceof Collection)) {
                String s = String.valueOf(v);
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> pickTags(Map<String, Object> row, String custom) {
        Object v = custom != null && !custom.isBlank()
            ? row.get(custom)
            : row.get("tags");
        if (v == null) {
            for (String f : GallerySourceConfig.defaultTagsFields()) {
                if (row.get(f) != null) {
                    v = row.get(f);
                    break;
                }
            }
        }
        if (v instanceof Collection<?> coll) {
            List<String> out = new ArrayList<>();
            for (Object o : coll) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        if (v != null) {
            return List.of(String.valueOf(v));
        }
        return List.of();
    }

    // ------------------------------------------------------------ 查询

    public List<GalleryImageItem> all() {
        return List.copyOf(cache);
    }

    public List<GalleryImageItem> bySource(String source) {
        if (source == null || source.isBlank()) {
            return all();
        }
        return cache.stream().filter(i -> source.equals(i.getSource())).toList();
    }

    public int count() {
        return cache.size();
    }

    public Instant getLastSync() {
        return lastSync;
    }

    /** 排定周期同步时调用，记下下次该什么时候跑 */
    public void markNextSync(long intervalMinutes) {
        this.intervalMinutes = Math.max(1, intervalMinutes);
        this.nextSyncAt = Instant.now().plus(Duration.ofMinutes(this.intervalMinutes));
    }

    public Instant getNextSyncAt() {
        return nextSyncAt;
    }

    public long getIntervalMinutes() {
        return intervalMinutes;
    }

    /** 距下次同步还有多少秒；已过期返回 0 */
    public long secondsUntilNextSync() {
        if (nextSyncAt == null) {
            return -1;
        }
        long s = Duration.between(Instant.now(), nextSyncAt).toSeconds();
        return Math.max(0, s);
    }

    public Map<String, Instant> getLastSyncAt() {
        return Map.copyOf(lastSyncAt);
    }

    public Map<String, String> getLastError() {
        return Map.copyOf(lastError);
    }
}
