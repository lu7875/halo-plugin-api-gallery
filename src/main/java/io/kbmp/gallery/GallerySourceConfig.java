package io.kbmp.gallery;

import java.util.List;

/**
 * 一个 API 数据源的配置。对应后台设置表单里的每一行。
 */
public class GallerySourceConfig {

    /** 显示名称，如「派次元」 */
    private String name;

    /** API 地址，如 https://t.paiii.cn/api/list */
    private String apiUrl;

    /**
     * 响应结构：auto / array / items / random / redirect
     * auto     = 自动判断（默认）
     * array    = 顶层就是数组
     * items    = 顶层对象的 items 字段是数组
     * random   = 顶层是单个图片对象
     * redirect = 302/301 跳转到图片地址（随机图 API），每次请求返回不同的图，
     *            插件会重复采样若干次攒成一个图集
     */
    private String parser = "auto";

    /** {@code redirect} 类型每次同步采样多少次，越多攒到的图越多，但请求也越多 */
    private int sampleCount = 30;

    /** {@code redirect} 类型最多累积多少张，防止图库无限膨胀 */
    private int maxItems = 200;

    private boolean enabled = true;

    /** 字段映射，留空则按常见字段名自动推断 */
    private String urlField;
    private String idField;
    private String titleField;
    private String tagsField;

    /** 该数据源的同步间隔（分钟），<=0 表示跟随全局 */
    private int syncIntervalMinutes = 0;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getParser() {
        return parser;
    }

    public void setParser(String parser) {
        this.parser = parser;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = maxItems;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrlField() {
        return urlField;
    }

    public void setUrlField(String urlField) {
        this.urlField = urlField;
    }

    public String getIdField() {
        return idField;
    }

    public void setIdField(String idField) {
        this.idField = idField;
    }

    public String getTitleField() {
        return titleField;
    }

    public void setTitleField(String titleField) {
        this.titleField = titleField;
    }

    public String getTagsField() {
        return tagsField;
    }

    public void setTagsField(String tagsField) {
        this.tagsField = tagsField;
    }

    public int getSyncIntervalMinutes() {
        return syncIntervalMinutes;
    }

    public void setSyncIntervalMinutes(int syncIntervalMinutes) {
        this.syncIntervalMinutes = syncIntervalMinutes;
    }

    /**
     * 是否可用（启用、地址非空且通过安全校验）。
     *
     * <p>这里用轻量的 {@link ApiUrlSafety#isPlausible(String)}，只挡掉非 http/https
     * 与明显的内网 IP；真正发请求前还会做一次含 DNS 解析的完整校验。
     */
    public boolean usable() {
        return enabled && apiUrl != null && !apiUrl.isBlank() && ApiUrlSafety.isPlausible(apiUrl);
    }

    /** 是否是「302 跳转到随机图片」类型的源 */
    public boolean isRedirect() {
        return PARSER_REDIRECT.equalsIgnoreCase(parser == null ? "" : parser.trim());
    }

    /** 是否是「每次请求返回随机一张图的 JSON 接口」类型的源 */
    public boolean isRandomJson() {
        return PARSER_RANDOM_JSON.equalsIgnoreCase(parser == null ? "" : parser.trim());
    }

    /**
     * 是否是「采样型」源：每次请求只返回一张随机图，需要重复采样攒成图集。
     *
     * <p>这类源的共同点是「内容会变」，所以同步时要累积而不是替换；
     * 固定列表源（如 /api/list）每次返回同样的数据，走的是全量替换。
     */
    public boolean isSampled() {
        return isRedirect() || isRandomJson();
    }

    /** 采样次数，限制在 1–200，防止手滑填成几万把图源打挂 */
    public int sampleCountOrDefault() {
        if (sampleCount <= 0) {
            return DEFAULT_SAMPLE_COUNT;
        }
        return Math.min(200, sampleCount);
    }

    /** 累积上限，限制在 1–5000 */
    public int maxItemsOrDefault() {
        if (maxItems <= 0) {
            return DEFAULT_MAX_ITEMS;
        }
        return Math.min(5000, maxItems);
    }

    public static final String PARSER_REDIRECT = "redirect";
    public static final String PARSER_RANDOM_JSON = "random-json";
    public static final int DEFAULT_SAMPLE_COUNT = 30;
    public static final int DEFAULT_MAX_ITEMS = 200;

    public String displayNameOrDefault(String fallback) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return fallback;
    }

    public static List<String> defaultUrlFields() {
        return List.of("url", "imageUrl", "image_url", "src", "link", "origin", "path");
    }

    public static List<String> defaultIdFields() {
        return List.of("id", "uid", "uuid", "key");
    }

    public static List<String> defaultTitleFields() {
        return List.of("title", "name", "desc", "description", "alt");
    }

    public static List<String> defaultTagsFields() {
        return List.of("tags", "tag", "categories", "labels");
    }
}
