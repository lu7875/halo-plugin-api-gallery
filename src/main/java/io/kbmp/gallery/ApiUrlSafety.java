package io.kbmp.gallery;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;

/**
 * 图源地址安全校验，用于防止 SSRF（服务端请求伪造）。
 *
 * <p>插件会由站点服务器向用户填写的 {@code apiUrl} 发起 HTTP 请求。若不加限制，
 * 该地址可被指向内网服务、本机端口或云厂商元数据地址，造成内网探测风险。
 * 因此所有对外请求发出前都必须经过这里校验：
 *
 * <ol>
 *   <li>只允许 {@code http} / {@code https} 协议（挡掉 file、ftp、jar、gopher 等）；
 *   <li>主机名必须有值；
 *   <li>把主机名解析成 IP 后逐个校验，命中回环、私有网段、链路本地、组播、
 *       保留地址等一律拒绝。
 * </ol>
 */
@Slf4j
public final class ApiUrlSafety {

    /** 地址长度上限，避免异常超长输入 */
    private static final int MAX_LENGTH = 2048;

    private ApiUrlSafety() {
    }

    /**
     * 校验图源地址，不合法时抛出 {@link IllegalArgumentException}。
     *
     * @param url 后台填写的图源地址
     * @return 规范化后的地址（去掉首尾空白）
     */
    public static String check(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("图源地址为空");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("图源地址过长（上限 " + MAX_LENGTH + " 字符）");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图源地址格式不合法：" + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("图源地址缺少协议，只允许 http 或 https");
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("不支持的协议「" + scheme + "」，只允许 http 或 https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("图源地址缺少主机名");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IllegalArgumentException("图源主机名无法解析：" + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("图源主机名解析不到 IP：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new IllegalArgumentException(
                    "图源地址指向内网或保留地址，已拒绝访问：" + host + " -> " + address.getHostAddress());
            }
        }
        return trimmed;
    }

    /** 只做地址判定、不抛异常，供配置可用性判断使用（含 DNS 解析，较重） */
    public static boolean isSafe(String url) {
        try {
            check(url);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 轻量校验：只检查协议与格式，不做 DNS 解析。
     *
     * <p>用于「该数据源是否可用」这类高频判断，避免每次调用都走域名解析。
     * 真正发请求前仍会经过 {@link #check(String)} 的完整校验。
     */
    public static boolean isPlausible(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            return false;
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.isBlank() && !isIpLiteralBlocked(host);
    }

    /** 主机名本身是 IP 字面量时，直接判定是否落在禁止范围 */
    private static boolean isIpLiteralBlocked(String host) {
        String bare = host.trim();
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        try {
            InetAddress address = InetAddress.getByName(bare);
            if (address instanceof Inet4Address || address instanceof Inet6Address) {
                byte[] bytes = address.getAddress();
                boolean looksNumeric = (address instanceof Inet4Address && bytes.length == 4)
                    || (address instanceof Inet6Address && bytes.length == 16);
                if (looksNumeric) {
                    return isBlocked(address);
                }
            }
        } catch (Exception ignore) {
            // 不是 IP 字面量（普通域名），交给后续 DNS 校验
        }
        return false;
    }

    /**
     * 判断 IP 是否属于不允许访问的地址范围。
     *
     * <p>覆盖：回环地址、通配地址、链路本地、站点本地（含 IPv4 私有网段）、
     * 组播、IPv6 唯一本地地址，以及 0.0.0.0/8、224.0.0.0/4 等保留段。
     */
    public static boolean isBlocked(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // IPv6 唯一本地地址 fc00::/7（注意按无符号比较，避免 byte 符号位导致漏判）
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc;
        }
        if (address instanceof Inet4Address) {
            int o1 = bytes[0] & 0xff;
            int o2 = bytes[1] & 0xff;
            if (o1 == 0) {
                return true;              // 0.0.0.0/8
            }
            if (o1 == 10) {
                return true;              // 10.0.0.0/8
            }
            if (o1 == 127) {
                return true;              // 127.0.0.0/8
            }
            if (o1 == 169 && o2 == 254) {
                return true;              // 169.254.0.0/16 链路本地
            }
            if (o1 == 172 && o2 >= 16 && o2 <= 31) {
                return true;              // 172.16.0.0/12
            }
            if (o1 == 192 && o2 == 168) {
                return true;              // 192.168.0.0/16
            }
            if (o1 >= 224) {
                return true;              // 224.0.0.0/4 及以上（组播与保留）
            }
        }
        return false;
    }
}
