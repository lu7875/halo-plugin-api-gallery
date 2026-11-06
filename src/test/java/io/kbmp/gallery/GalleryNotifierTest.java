package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 告警推送：JSON 内容、非 2xx 处理、非法地址拦截 */
class GalleryNotifierTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> received = new AtomicReference<>();
    private GalleryNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/bad", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        notifier = new GalleryNotifier(HttpClient.newHttpClient(), url -> url);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsJsonPayload() {
        assertTrue(notifier.send(base + "/hook", "同步失败", "数据源「派次元」连续 3 次失败"));
        String body = received.get();
        assertTrue(body.contains("\"title\":\"同步失败\""), body);
        assertTrue(body.contains("派次元"), body);
        assertTrue(body.contains("halo-plugin-api-gallery"), body);
    }

    @Test
    void returnsFalseOnNon2xx() {
        assertFalse(notifier.send(base + "/bad", "t", "x"));
    }

    @Test
    void returnsFalseWhenUrlRejected() {
        GalleryNotifier strict = new GalleryNotifier(HttpClient.newHttpClient(), url -> {
            throw new IllegalArgumentException("禁止内网 Webhook");
        });
        assertFalse(strict.send("http://169.254.169.254/hook", "t", "x"));
    }

    @Test
    void blankWebhookIsIgnored() {
        assertEquals(false, notifier.send("  ", "t", "x"));
        assertEquals(false, notifier.send(null, "t", "x"));
    }
}
