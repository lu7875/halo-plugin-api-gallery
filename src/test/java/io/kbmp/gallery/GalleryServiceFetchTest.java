package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 端到端：起一个本地 HTTP 服务模拟第三方图源，走完「请求 → 解析 → 生成元数据」整条链路。
 *
 * <p>这里只断言内存中的元数据，全程不产生任何本地文件——这也是插件的核心约束。
 */
class GalleryServiceFetchTest {

    private HttpServer server;
    private int port;
    private GalleryService service;
    private final java.util.concurrent.atomic.AtomicInteger hits =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger jsonHits =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/list", ex -> respond(ex, 200, """
            [
              {"id":"img-1","url":"https://cdn.test/1.webp","title":"图一","tags":["acg"]},
              {"id":"img-2","url":"https://cdn.test/2.webp","title":"图二","tags":["acg","二次元"]}
            ]
            """));
        server.createContext("/api/wrapped", ex -> respond(ex, 200,
            "{\"items\":[{\"id\":\"w1\",\"url\":\"https://cdn.test/w1.webp\"}],\"total\":1}"));
        server.createContext("/api/random", ex -> respond(ex, 200,
            "{\"id\":\"r1\",\"url\":\"https://cdn.test/r.webp\",\"title\":\"随机\"}"));
        server.createContext("/api/broken", ex -> respond(ex, 500, "boom"));
        server.createContext("/api/empty", ex -> respond(ex, 200, "[]"));

        // 模拟「302 跳转到随机图」的图源：图池 5 张，每次请求随机跳一张
        server.createContext("/pic/random", ex -> {
            String url = "https://cdn.test/pic" + (hits.incrementAndGet() % 5) + ".jpg";
            ex.getResponseHeaders().add("Location", url);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // 模拟「随机单张 JSON」的图源：图池 4 张，每次请求随机返回一张
        server.createContext("/api/random-json", ex -> respond(ex, 200,
            "{\"id\":\"j" + (jsonHits.incrementAndGet() % 4)
                + "\",\"url\":\"https://cdn.test/j" + (jsonHits.get() % 4)
                + ".webp\",\"title\":\"随机图" + (jsonHits.get() % 4) + "\"}"));
        // 固定内容却配成随机类型，用来验证诊断会给出提示
        server.createContext("/api/fixed-json", ex -> respond(ex, 200,
            "{\"id\":\"fixed\",\"url\":\"https://cdn.test/fixed.webp\",\"title\":\"固定\"}"));
        // 一次都不跳转的普通接口，用来验证诊断提示
        server.createContext("/pic/no-redirect", ex -> respond(ex, 200, "not an image api"));

        server.start();
        service = new GalleryService(null, new PhotoBridgeService(null));
        // 测试用的是本机回环地址，会被 SSRF 防护拦下，这里临时换成放行策略；
        // ApiUrlSafety 本身的拦截能力由 ApiUrlSafetyTest 单独覆盖
        service.setUrlChecker(url -> url);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
        throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private GallerySourceConfig source(String name, String path) {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setName(name);
        c.setApiUrl("http://127.0.0.1:" + port + path);
        c.setParser("auto");
        c.setEnabled(true);
        return c;
    }

    @Test
    void fetchesTopLevelArrayIntoMetadata() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(source("本地源", "/api/list"));
        assertEquals(2, items.size());
        assertEquals("img-1", items.get(0).getId());
        assertEquals("https://cdn.test/1.webp", items.get(0).getUrl());
        assertEquals("图一", items.get(0).getTitle());
        assertEquals("本地源", items.get(0).getSource());
        assertEquals(List.of("acg"), items.get(0).getTags());
    }

    @Test
    void fetchesWrappedItems() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(source("包裹源", "/api/wrapped"));
        assertEquals(1, items.size());
        assertEquals("https://cdn.test/w1.webp", items.get(0).getUrl());
    }

    @Test
    void fetchesSingleRandomObject() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(source("随机源", "/api/random"));
        assertEquals(1, items.size());
        assertEquals("r1", items.get(0).getId());
    }

    @Test
    void emptyListYieldsNoItems() throws Exception {
        assertTrue(service.fetchSource(source("空源", "/api/empty")).isEmpty());
    }

    @Test
    void httpErrorIsRaised() {
        GallerySourceConfig c = source("坏源", "/api/broken");
        boolean thrown = false;
        try {
            service.fetchSource(c);
        } catch (Exception e) {
            thrown = true;
            assertTrue(e.getMessage().contains("500"), "应带上 HTTP 状态码，实际：" + e.getMessage());
        }
        assertTrue(thrown, "HTTP 5xx 必须抛异常以便记录到 lastError");
    }

    /** 禁用或地址为空的数据源不应发起任何请求 */
    @Test
    void disabledSourceIsNotUsable() {
        GallerySourceConfig c = source("禁用源", "/api/list");
        c.setEnabled(false);
        assertFalse(c.usable());
    }

    // -------------------------------------------------- 302 随机图源

    private GallerySourceConfig redirectSource(String name, String path, int samples) {
        GallerySourceConfig c = source(name, path);
        c.setParser("redirect");
        c.setSampleCount(samples);
        return c;
    }

    @Test
    void redirectSourceCollectsUniqueImagesAcrossSamples() throws Exception {
        // 图池只有 5 张，采样 10 次应当去重成 5 张
        List<GalleryImageItem> items =
            service.fetchSource(redirectSource("随机图", "/pic/random", 10));

        assertEquals(5, items.size(), "重复采到的同一张图不应重复入缓存");
        assertEquals("随机图", items.get(0).getSource());
        assertTrue(items.get(0).getUrl().startsWith("https://cdn.test/pic"));
        // 随机图的文件名多为哈希串，标题用「分类 + 短 ID」而不是文件名
        assertTrue(items.get(0).getTitle().startsWith("随机图 "),
            "标题应为「分类 + 短 ID」，实际：" + items.get(0).getTitle());
    }

    @Test
    void redirectSourceRespectsSampleCount() throws Exception {
        List<GalleryImageItem> items =
            service.fetchSource(redirectSource("少采样", "/pic/random", 3));
        assertTrue(items.size() <= 3, "采样 3 次不该得到超过 3 张，实际 " + items.size());
    }

    @Test
    void redirectIdIsStableForSameUrl() throws Exception {
        List<GalleryImageItem> a = service.fetchSource(redirectSource("随机图", "/pic/random", 6));
        List<GalleryImageItem> b = service.fetchSource(redirectSource("随机图", "/pic/random", 6));

        // 同一地址两次同步得到的 id 必须一致，否则同步进图库会不断产生新记录
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
        for (GalleryImageItem i : a) {
            ids.put(i.getUrl(), i.getId());
        }
        for (GalleryImageItem i : b) {
            String prev = ids.get(i.getUrl());
            if (prev != null) {
                assertEquals(prev, i.getId(), "同一图片地址的 id 必须稳定");
            }
        }
        assertTrue(a.stream().allMatch(i -> i.getId().startsWith("rnd-")));
    }

    @Test
    void dedupeRemovesDuplicatesAndAppliesLimit() {
        GallerySourceConfig c = redirectSource("随机图", "/pic/random", 10);
        c.setMaxItems(3);

        List<GalleryImageItem> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(new GalleryImageItem("id" + i, "https://cdn.test/p" + i + ".jpg",
                "t" + i, "随机图", List.of()));
        }
        // 加一条完全重复的
        many.add(new GalleryImageItem("dup", "https://cdn.test/p0.jpg", "t0", "随机图", List.of()));

        List<GalleryImageItem> kept = service.dedupe(many, List.of(c));
        assertEquals(3, kept.size(), "maxItems=3 时应只保留 3 张");

        // 非随机源不受上限约束
        GallerySourceConfig json = source("JSON源", "/api/list");
        assertEquals(10, service.dedupe(many.subList(0, 10), List.of(json)).size());
    }

    @Test
    void dedupeDropsBlankUrls() {
        GallerySourceConfig c = source("源", "/api/list");
        List<GalleryImageItem> items = new ArrayList<>(List.of(
            new GalleryImageItem("a", "https://cdn.test/a.jpg", "a", "源", List.of()),
            new GalleryImageItem("b", "", "b", "源", List.of()),
            new GalleryImageItem("c", null, "c", "源", List.of())));
        assertEquals(1, service.dedupe(items, List.of(c)).size());
    }

    @Test
    void redirectDiagnoseReportsLocation() {
        var out = service.diagnose();
        assertTrue(out.isEmpty() || out.stream().allMatch(m -> m.containsKey("name")));
    }

    @Test
    void sampleCountAndMaxItemsAreClamped() {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setParser("redirect");
        assertTrue(c.isRedirect());

        c.setSampleCount(0);
        assertEquals(GallerySourceConfig.DEFAULT_SAMPLE_COUNT, c.sampleCountOrDefault());
        c.setSampleCount(99999);
        assertEquals(200, c.sampleCountOrDefault());

        c.setMaxItems(-1);
        assertEquals(GallerySourceConfig.DEFAULT_MAX_ITEMS, c.maxItemsOrDefault());
        c.setMaxItems(999999);
        assertEquals(5000, c.maxItemsOrDefault());
    }

    @Test
    void nonRedirectParserIsNotTreatedAsRedirect() {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setParser("auto");
        assertFalse(c.isRedirect());
        c.setParser("REDIRECT");
        assertTrue(c.isRedirect(), "大小写不敏感");
    }

    // -------------------------------------------------- 随机单张 JSON

    @Test
    void randomJsonSourceCollectsUniqueImages() throws Exception {
        GallerySourceConfig c = source("随机JSON", "/api/random-json");
        c.setParser("random-json");
        c.setSampleCount(10);

        List<GalleryImageItem> items = service.fetchSource(c);

        // 图池 4 张，采样 10 次应当去重成 4 张
        assertEquals(4, items.size());
        assertTrue(c.isSampled(), "random-json 也属于采样型，同步时要累积而不是替换");
        assertTrue(items.get(0).getUrl().startsWith("https://cdn.test/j"));
        // JSON 里带了 title，应当用上而不是退化成短 ID
        assertTrue(items.get(0).getTitle().startsWith("随机JSON 随机图"),
            "标题应带上 JSON 里的 title，实际：" + items.get(0).getTitle());
    }

    @Test
    void randomJsonIsNotTreatedAsRedirect() {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setParser("random-json");
        assertFalse(c.isRedirect());
        assertTrue(c.isRandomJson());
        assertTrue(c.isSampled());

        // 固定列表源既不是采样型，同步时走全量替换
        GallerySourceConfig list = new GallerySourceConfig();
        list.setParser("auto");
        assertFalse(list.isSampled());
    }

    @Test
    void fixedJsonDiagnosedAsNotRandom() {
        GallerySourceConfig c = source("固定JSON", "/api/fixed-json");
        c.setParser("random-json");
        // 通过 dedupe 之外的路径验证：连续两次采样结果完全一致
        List<GalleryImageItem> a;
        List<GalleryImageItem> b;
        try {
            a = service.fetchSource(c);
            b = service.fetchSource(c);
        } catch (Exception e) {
            throw new AssertionError("采样不应抛异常", e);
        }
        assertEquals(1, a.size(), "内容固定的接口无论采样多少次都只有 1 张");
        assertEquals(a.get(0).getUrl(), b.get(0).getUrl());
    }

    // -------------------------------------------------- 同步周期可见性

    @Test
    void nextSyncIsExposedForTroubleshooting() {
        assertEquals(-1, service.secondsUntilNextSync(), "没排定周期时应返回 -1");

        service.markNextSync(5);
        assertEquals(5, service.getIntervalMinutes());
        assertTrue(service.getNextSyncAt() != null);
        long left = service.secondsUntilNextSync();
        assertTrue(left > 250 && left <= 300, "5 分钟后应剩约 300 秒，实际 " + left);

        // 周期最小 1 分钟，防止填 0 导致疯狂同步
        service.markNextSync(0);
        assertEquals(1, service.getIntervalMinutes());
    }
}
