package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 稳定性端到端：快照恢复 → 前台筛选 → 死链巡检剔除。
 *
 * <p>ExtensionClient 传 null 时，配置读取会因异常被捕获而返回 null，
 * 因此所有设置都走代码默认值（快照开启、巡检开启、连续 3 次失效剔除），
 * 正好覆盖「用户没做任何配置」的默认行为。
 */
class GalleryServiceStabilityTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private GallerySnapshotStore store;
    private GalleryService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/error", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        store = new GallerySnapshotStore(tempDir.resolve("snapshot.json"));
        service = new GalleryService(null, new PhotoBridgeService(null));
        service.setUrlChecker(url -> url);
        service.setSnapshotStore(store);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private GalleryImageItem item(String id, String url, String source, String... tags) {
        return new GalleryImageItem(id, url, "标题" + id, source, List.of(tags));
    }

    @Test
    void restoresSnapshotAndExposesPublicFilters() {
        store.save(List.of(
            item("a1", base + "/ok/1.webp", "源A", "acg"),
            item("a2", base + "/ok/2.webp", "源A", "acg"),
            item("b1", base + "/ok/3.webp", "源B", "beauty")));

        assertEquals(3, service.restoreSnapshot(), "启动应从快照恢复全部元数据");
        assertEquals(2, service.filter("源A", null).size());
        assertEquals(2, service.filter(null, "acg").size());
        assertEquals(0, service.filter("源B", "acg").size());
        assertEquals(List.of("acg", "beauty"), service.allTags());

        List<Map<String, Object>> sources = service.publicSources();
        assertEquals(2, sources.size());
        for (Map<String, Object> s : sources) {
            assertFalse(s.containsKey("apiUrl"), "公开源列表绝不能泄露图源地址");
            assertTrue(s.containsKey("name") && s.containsKey("count"));
        }
    }

    @Test
    void restoreHappensOnlyOnce() {
        store.save(List.of(item("a", base + "/ok/1.webp", "源A", "acg")));
        assertEquals(1, service.restoreSnapshot());

        store.save(List.of(item("b", base + "/ok/2.webp", "源A", "acg"),
            item("c", base + "/ok/3.webp", "源A", "acg")));
        assertEquals(1, service.restoreSnapshot(), "已恢复过就不能再覆盖运行中的缓存");
    }

    @Test
    void removesDeadLinksOnlyAfterRepeatedConfirmation() {
        store.save(List.of(
            item("good", base + "/ok", "源A", "acg"),
            item("dead", base + "/missing", "源A", "acg")));
        service.restoreSnapshot();

        Map<String, Object> r1 = service.checkDeadLinks();
        assertEquals(0, r1.get("removed"), "第一次失效不应立即剔除");
        assertEquals(2, service.count());

        service.checkDeadLinks();
        assertEquals(2, service.count(), "第二次仍未达阈值");

        Map<String, Object> r3 = service.checkDeadLinks();
        assertEquals(1, r3.get("removed"), "连续 3 次确认 404 后才剔除");
        assertEquals(1, service.count());
        assertEquals(base + "/ok", service.all().get(0).getUrl(), "正常图片必须保留");
    }

    @Test
    void serverErrorNeverCountsAsDeadLink() {
        store.save(List.of(item("flaky", base + "/error", "源A", "acg")));
        service.restoreSnapshot();

        for (int i = 0; i < 4; i++) {
            Map<String, Object> r = service.checkDeadLinks();
            assertEquals(0, r.get("removed"), "5xx 不能当作死链剔除");
        }
        assertEquals(1, service.count());
    }

    @Test
    void snapshotIsRewrittenAfterRemoval() {
        store.save(List.of(item("dead", base + "/missing", "源A", "acg")));
        service.restoreSnapshot();
        for (int i = 0; i < 3; i++) {
            service.checkDeadLinks();
        }
        assertTrue(store.load().isEmpty(), "剔除后快照要同步更新，否则重启又把死链恢复回来");
    }

    @Test
    void stabilityStatusExposesRuntimeInfo() {
        store.save(List.of(item("a", base + "/ok", "源A", "acg")));
        service.restoreSnapshot();
        Map<String, Object> status = service.stabilityStatus();
        assertEquals(true, status.get("snapshotEnabled"));
        assertEquals(true, status.get("deadLinkCheckEnabled"));
        assertEquals(false, status.get("alertWebhookConfigured"));
        assertTrue(status.containsKey("consecutiveFailures"));
    }
}
