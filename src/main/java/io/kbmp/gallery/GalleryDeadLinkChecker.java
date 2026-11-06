package io.kbmp.gallery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * 第三方图片地址的可用性探测。
 *
 * <p>设计重点是「宁可漏判，不可误删」：
 * <ul>
 *   <li>只有明确的 404 / 410 / 403 才判定为死链（{@link Status#DEAD}）；</li>
 *   <li>超时、连接失败、5xx、429 等一律视为 {@link Status#UNKNOWN}，不计入失败，
 *       避免图源限流或网络抖动时把正常图片清掉；</li>
 *   <li>部分 CDN 不支持 HEAD（405/501），自动降级为 {@code Range: bytes=0-0} 的 GET，
 *       不下载完整图片。</li>
 * </ul>
 *
 * <p>所有地址在发请求前都会再跑一次与采集相同的 SSRF 校验，且客户端不自动跟随重定向。
 */
@Slf4j
public class GalleryDeadLinkChecker {

    public enum Status {
        /** 2xx / 3xx，图片可访问 */
        ALIVE,
        /** 404 / 410 / 403，明确不可用 */
        DEAD,
        /** 超时、连接失败、5xx 等，无法下结论 */
        UNKNOWN
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private final HttpClient client;
    private final Function<String, String> urlChecker;

    public GalleryDeadLinkChecker(HttpClient client, Function<String, String> urlChecker) {
        this.client = client;
        this.urlChecker = urlChecker;
    }

    public Status probe(String url) {
        if (url == null || url.isBlank()) {
            return Status.DEAD;
        }
        final String safe;
        try {
            safe = urlChecker.apply(url);
        } catch (RuntimeException e) {
            // 连安全校验都过不了的地址，本来就不该继续展示
            return Status.DEAD;
        }
        try {
            HttpRequest req = base(safe).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            if (code == 405 || code == 501) {
                return probeWithRange(safe);
            }
            return classify(code);
        } catch (Exception e) {
            log.debug("[api-gallery] 图片可用性探测异常（不计入失败）：{} {}", url, e.toString());
            return Status.UNKNOWN;
        }
    }

    private Status probeWithRange(String safe) {
        try {
            HttpRequest req = base(safe)
                .header("Range", "bytes=0-0")
                .GET()
                .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return classify(resp.statusCode());
        } catch (Exception e) {
            log.debug("[api-gallery] 图片 Range 探测异常（不计入失败）：{} {}", safe, e.toString());
            return Status.UNKNOWN;
        }
    }

    private static HttpRequest.Builder base(String safe) {
        return HttpRequest.newBuilder(URI.create(safe))
            .timeout(TIMEOUT)
            .header("User-Agent", "halo-plugin-api-gallery/1.5")
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
    }

    private static Status classify(int code) {
        if (code >= 200 && code < 400) {
            return Status.ALIVE;
        }
        if (code == 404 || code == 410 || code == 403) {
            return Status.DEAD;
        }
        return Status.UNKNOWN;
    }
}
