package io.kbmp.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * 同步失败告警推送（Webhook）。
 *
 * <p>数据源连续失败达到阈值时，把源名称、失败次数与原因 POST 到后台配置的 Webhook，
 * 可用于对接企业微信 / 钉钉 / Server 酱 / 自建告警服务。
 *
 * <p>安全约束：Webhook 地址本身也要过与图源相同的 SSRF 校验（协议 + 主机 + DNS 解析 IP），
 * 不允许指向内网或云元数据地址；推送失败只记日志，绝不影响同步主流程。
 */
@Slf4j
public class GalleryNotifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private final HttpClient client;
    private final Function<String, String> urlChecker;
    private final ObjectMapper mapper = new ObjectMapper();

    public GalleryNotifier(HttpClient client, Function<String, String> urlChecker) {
        this.client = client;
        this.urlChecker = urlChecker;
    }

    /** 发送一条告警，返回是否推送成功 */
    public boolean send(String webhookUrl, String title, String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }
        try {
            String safe = urlChecker.apply(webhookUrl.trim());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "halo-plugin-api-gallery");
            payload.put("title", title);
            payload.put("text", text);
            payload.put("time", Instant.now().toString());
            String body = mapper.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder(URI.create(safe))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "halo-plugin-api-gallery/1.5")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            if (!ok) {
                log.warn("[api-gallery] 告警 Webhook 返回非 2xx：{}", resp.statusCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("[api-gallery] 告警 Webhook 推送失败：{}", e.toString());
            return false;
        }
    }
}
