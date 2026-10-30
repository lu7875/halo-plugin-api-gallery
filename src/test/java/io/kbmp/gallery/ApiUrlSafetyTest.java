package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图源地址的 SSRF 防护测试。
 *
 * <p>插件由服务端主动请求用户填写的地址，一旦允许任意协议或内网目标，
 * 就可能被用于探测内网服务与云厂商元数据，因此这里对拦截范围做穷举验证。
 * 用例全部使用 IP 字面量，不依赖外网 DNS。
 */
class ApiUrlSafetyTest {

    @Test
    @DisplayName("公共 IP 地址的 http/https 图源允许访问")
    void allowsPublicHttpUrl() {
        assertDoesNotThrow(() -> ApiUrlSafety.check("https://8.8.8.8/api/list"));
        assertDoesNotThrow(() -> ApiUrlSafety.check("http://1.1.1.1/list"));
        assertDoesNotThrow(() -> ApiUrlSafety.check("  https://8.8.8.8/api/list  "));
        assertTrue(ApiUrlSafety.isSafe("https://8.8.8.8/api/list"));
        assertTrue(ApiUrlSafety.isPlausible("https://example.com/list"));
    }

    @Test
    @DisplayName("非 http/https 协议一律拒绝")
    void rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("ftp://8.8.8.8/pub"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("jar:https://8.8.8.8/x!/"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("gopher://8.8.8.8:70/_x"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("//8.8.8.8/list"));
        assertFalse(ApiUrlSafety.isPlausible("file:///etc/passwd"));
    }

    @Test
    @DisplayName("空值与非法格式拒绝")
    void rejectsBlankOrMalformed() {
        assertThrows(IllegalArgumentException.class, () -> ApiUrlSafety.check(null));
        assertThrows(IllegalArgumentException.class, () -> ApiUrlSafety.check(""));
        assertThrows(IllegalArgumentException.class, () -> ApiUrlSafety.check("   "));
        assertThrows(IllegalArgumentException.class, () -> ApiUrlSafety.check("http://"));
        assertFalse(ApiUrlSafety.isPlausible(null));
        assertFalse(ApiUrlSafety.isPlausible(""));
    }

    @Test
    @DisplayName("超长输入拒绝")
    void rejectsTooLongUrl() {
        StringBuilder sb = new StringBuilder("https://8.8.8.8/?q=");
        while (sb.length() < 2100) {
            sb.append("a");
        }
        assertThrows(IllegalArgumentException.class, () -> ApiUrlSafety.check(sb.toString()));
    }

    @Test
    @DisplayName("回环与本机地址拒绝")
    void rejectsLoopback() {
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://127.0.0.1:8080/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://127.1.2.3/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://[::1]/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://0.0.0.0:8080/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://localhost/api"));
        assertFalse(ApiUrlSafety.isPlausible("http://127.0.0.1:8080/api"));
    }

    @Test
    @DisplayName("私有网段拒绝")
    void rejectsPrivateRanges() {
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://10.0.0.5/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://10.255.255.254/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://192.168.1.1/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://172.16.0.1/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://172.31.255.255/api"));
        // 172.32 已不属于 172.16/12，应当放行
        assertDoesNotThrow(() -> ApiUrlSafety.check("http://172.32.0.1/api"));
    }

    @Test
    @DisplayName("链路本地与云厂商元数据地址拒绝")
    void rejectsLinkLocalAndMetadata() {
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://169.254.169.254/latest/meta-data/"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://169.254.0.1/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://[fe80::1]/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://[fc00::1]/api"));
    }

    @Test
    @DisplayName("组播与保留网段拒绝")
    void rejectsMulticastAndReserved() {
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://224.0.0.1/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://239.255.255.250/api"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiUrlSafety.check("http://255.255.255.255/api"));
    }

    @Test
    @DisplayName("IP 判定覆盖地址族的禁止范围")
    void isBlockedCoversSpecialAddresses() throws Exception {
        assertTrue(ApiUrlSafety.isBlocked(InetAddress.getByName("127.0.0.1")));
        assertTrue(ApiUrlSafety.isBlocked(InetAddress.getByName("::1")));
        assertTrue(ApiUrlSafety.isBlocked(InetAddress.getByName("10.1.2.3")));
        assertTrue(ApiUrlSafety.isBlocked(InetAddress.getByName("192.168.0.1")));
        assertTrue(ApiUrlSafety.isBlocked(InetAddress.getByName("169.254.169.254")));
        assertFalse(ApiUrlSafety.isBlocked(InetAddress.getByName("8.8.8.8")));
        assertFalse(ApiUrlSafety.isBlocked(InetAddress.getByName("1.1.1.1")));
        assertTrue(ApiUrlSafety.isBlocked(null));
    }
}
