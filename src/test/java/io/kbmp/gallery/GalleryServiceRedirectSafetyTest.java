package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跳转跟随的安全测试：图源返回 3xx 时，跳转目标必须重新执行 SSRF 校验，
 * 防止图源先返回公网地址、再通过 Location 把服务器引导访问内网/回环/云元数据地址。
 *
 * <p>测试服务器跑在本机回环（127.0.0.1），而真实的 {@link ApiUrlSafety} 会拦回环地址，
 * 因此这里用一个定制 checker：只放行指向本测试端口的地址，其余一律走真实 SSRF 校验。
 * 这样既能跑通本地端到端链路，又能验证「跳转到内网目标会被拒绝」。
 */
class GalleryServiceRedirectSafetyTest {

    private HttpServer server;
    private int port;
    private GalleryService service;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // 正常列表源，供「合法跳转」落地
        server.createContext("/api/list", ex -> respond(ex, 200, """
            [{"id":"img-1","url":"https://cdn.test/1.webp","title":"图一"}]
            """));

        // 302 -> 云元数据地址（链路本地 169.254.169.254），必须被拦
        server.createContext("/evil-to-metadata", ex -> {
            ex.getResponseHeaders().add("Location",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        // 302 -> 私有网段 10.0.0.5，必须被拦
        server.createContext("/evil-to-private", ex -> {
            ex.getResponseHeaders().add("Location", "http://10.0.0.5:8080/internal");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        // 302 -> 本测试服务器上的合法地址（相对路径），应正常跟随
        server.createContext("/good-relative", ex -> {
            ex.getResponseHeaders().add("Location", "/api/list");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        // 302 -> 本测试服务器上的合法地址（绝对路径），应正常跟随
        server.createContext("/good-absolute", ex -> {
            ex.getResponseHeaders().add("Location",
                "http://127.0.0.1:" + port + "/api/list");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        // 302 -> 非 http 协议（file），必须被拦
        server.createContext("/evil-to-file", ex -> {
            ex.getResponseHeaders().add("Location", "file:///etc/passwd");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        // 重定向循环 A <-> B，应触发最大跳数保护
        server.createContext("/loop-a", ex -> {
            ex.getResponseHeaders().add("Location", "/loop-b");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/loop-b", ex -> {
            ex.getResponseHeaders().add("Location", "/loop-a");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        // 302 随机图源：Location 指向云元数据地址，作为图片地址必须被丢弃
        server.createContext("/pic/evil-metadata", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // 302 随机图源：Location 为非 http 协议，必须被丢弃
        server.createContext("/pic/evil-file", ex -> {
            ex.getResponseHeaders().add("Location", "file:///etc/passwd");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // 302 随机图源：Location 指向本测试服务器（checker 放行），应正常入缓存
        server.createContext("/pic/good-image", ex -> {
            ex.getResponseHeaders().add("Location",
                "http://127.0.0.1:" + port + "/img/ok.webp");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // 随机单张 JSON：响应体里塞内网图片地址，必须被丢弃
        server.createContext("/api/evil-json", ex -> respond(ex, 200,
            "{\"id\":\"e1\",\"url\":\"http://10.0.0.5/evil.jpg\"}"));

        server.start();

        service = new GalleryService(null, new PhotoBridgeService(null));
        // 只放行本测试端口，其余走真实 ApiUrlSafety 校验
        service.setUrlChecker(url -> {
            String u = url.trim();
            String self = "http://127.0.0.1:" + port + "/";
            if (u.startsWith(self)) {
                return u;
            }
            return ApiUrlSafety.check(u);
        });
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GallerySourceConfig source(String name, String path) {
        GallerySourceConfig c = new GallerySourceConfig();
        c.setName(name);
        c.setApiUrl("http://127.0.0.1:" + port + path);
        c.setParser("auto");
        c.setEnabled(true);
        return c;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("跳转到云元数据地址（169.254.169.254）被拒绝")
    void rejectsRedirectToMetadata() {
        GallerySourceConfig c = source("恶意源", "/evil-to-metadata");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.fetchSource(c));
        assertTrue(e.getMessage() != null && e.getMessage().contains("169.254"),
            "拒绝原因应带目标地址，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("跳转到私有网段（10.0.0.5）被拒绝")
    void rejectsRedirectToPrivateRange() {
        GallerySourceConfig c = source("恶意源", "/evil-to-private");
        assertThrows(IllegalArgumentException.class, () -> service.fetchSource(c));
    }

    @Test
    @DisplayName("跳转到非 http 协议（file）被拒绝")
    void rejectsRedirectToNonHttp() {
        GallerySourceConfig c = source("恶意源", "/evil-to-file");
        assertThrows(IllegalArgumentException.class, () -> service.fetchSource(c));
    }

    @Test
    @DisplayName("相对路径 Location 正常跟随")
    void followsRelativeRedirect() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(source("正常源", "/good-relative"));
        assertEquals(1, items.size());
        assertEquals("img-1", items.get(0).getId());
    }

    @Test
    @DisplayName("绝对路径 Location 正常跟随")
    void followsAbsoluteRedirect() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(source("正常源", "/good-absolute"));
        assertEquals(1, items.size());
        assertEquals("https://cdn.test/1.webp", items.get(0).getUrl());
    }

    @Test
    @DisplayName("重定向循环触发最大跳数保护")
    void rejectsRedirectLoop() {
        GallerySourceConfig c = source("循环源", "/loop-a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> service.fetchSource(c));
        assertTrue(e.getMessage() != null && e.getMessage().contains("重定向次数"),
            "应提示超过最大跳数，实际：" + e.getMessage());
    }

    // ------------------------------------------------------------------
    // 1.4.2：302 随机图源的 Location 也要校验，不能把 3xx 目标写进业务数据
    // ------------------------------------------------------------------

    private GallerySourceConfig sampledSource(String name, String path, String parser,
        int samples) {
        GallerySourceConfig c = source(name, path);
        c.setParser(parser);
        c.setSampleCount(samples);
        return c;
    }

    @Test
    @DisplayName("302 随机图源 Location 指向云元数据地址时被丢弃，不写入缓存")
    void redirectLocationToMetadataIsDropped() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(
            sampledSource("恶意随机源", "/pic/evil-metadata", "redirect", 3));
        assertTrue(items.isEmpty(), "内网 Location 不得作为图片地址写入缓存与图库");
    }

    @Test
    @DisplayName("302 随机图源 Location 为非 http 协议时被丢弃")
    void redirectLocationToFileIsDropped() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(
            sampledSource("恶意随机源", "/pic/evil-file", "redirect", 3));
        assertTrue(items.isEmpty(), "file:// 等非 http 地址不得写入缓存");
    }

    @Test
    @DisplayName("302 随机图源合法 Location 正常采集")
    void redirectLocationToSafeAddressIsKept() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(
            sampledSource("正常随机源", "/pic/good-image", "redirect", 2));
        assertEquals(1, items.size(), "合法公网地址应正常入缓存并去重");
        assertTrue(items.get(0).getUrl().contains("/img/ok.webp"));
    }

    @Test
    @DisplayName("随机单张 JSON 响应体中的内网图片地址被丢弃")
    void randomJsonInternalUrlIsDropped() throws Exception {
        List<GalleryImageItem> items = service.fetchSource(
            sampledSource("恶意 JSON 源", "/api/evil-json", "random-json", 3));
        assertTrue(items.isEmpty(), "响应体里的内网地址不得写入缓存与图库");
    }
}
