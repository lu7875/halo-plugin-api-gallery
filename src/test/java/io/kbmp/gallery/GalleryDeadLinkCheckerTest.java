package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 死链探测的关键边界：只有明确失效才判 DEAD，服务异常与网络抖动一律 UNKNOWN，
 * 避免巡检把正常图片误删。
 */
class GalleryDeadLinkCheckerTest {

    private HttpServer server;
    private String base;
    private GalleryDeadLinkChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, 1);
                ex.getResponseBody().write(new byte[] {0});
            }
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/forbidden", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.createContext("/error", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        // 不支持 HEAD 的 CDN：必须降级为 Range GET 才能正确判断存活
        server.createContext("/head-not-allowed", ex -> {
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
            } else {
                ex.sendResponseHeaders(206, 1);
                ex.getResponseBody().write(new byte[] {0});
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        checker = new GalleryDeadLinkChecker(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build(), url -> url);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void aliveOn200() {
        assertEquals(GalleryDeadLinkChecker.Status.ALIVE, checker.probe(base + "/ok"));
    }

    @Test
    void deadOn404() {
        assertEquals(GalleryDeadLinkChecker.Status.DEAD, checker.probe(base + "/missing"));
    }

    @Test
    void deadOn403() {
        assertEquals(GalleryDeadLinkChecker.Status.DEAD, checker.probe(base + "/forbidden"));
    }

    @Test
    void unknownOn500() {
        assertEquals(GalleryDeadLinkChecker.Status.UNKNOWN, checker.probe(base + "/error"));
    }

    @Test
    void fallsBackToRangeGetWhenHeadNotAllowed() {
        assertEquals(GalleryDeadLinkChecker.Status.ALIVE, checker.probe(base + "/head-not-allowed"));
    }

    @Test
    void unknownOnConnectError() {
        // 未监听的端口：连接失败属于「无法下结论」，绝不能当成死链
        assertEquals(GalleryDeadLinkChecker.Status.UNKNOWN,
            checker.probe("http://127.0.0.1:1/none.webp"));
    }

    @Test
    void deadWhenUrlRejectedBySafetyCheck() {
        GalleryDeadLinkChecker strict = new GalleryDeadLinkChecker(HttpClient.newHttpClient(),
            url -> {
                throw new IllegalArgumentException("内网地址不合法");
            });
        assertEquals(GalleryDeadLinkChecker.Status.DEAD, strict.probe("http://169.254.169.254/x"));
    }
}
