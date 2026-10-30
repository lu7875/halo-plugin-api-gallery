package io.kbmp.gallery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Unstructured;

/**
 * 把 API 图源的图片**以元数据形式**写进「图库管理」插件（plugin-photos）的 Photo 资源，
 * 从而在 {@code /console/photos} 后台图库页和主题 {@code /photos} 页面里显示。
 *
 * <p>关键点：只写 {@code spec.url}（第三方图源的地址），不创建任何 Attachment、
 * 不下载文件，因此**不占用站点存储**。图片仍由浏览器直连图源 CDN 加载。
 *
 * <p>Photo 的 url 字段官方注释即支持外链：
 * “Absolute URL of the photo image file. Usually a permalink to a Halo Attachment
 * or an external CDN link.”
 *
 * <p>本插件写入的 Photo 都会带上 {@code api-gallery.kbmp.club/managed=true} 标签，
 * 撤回时只删带该标签的记录，不会误伤你手工上传的照片。
 */
@Slf4j
@Component
public class PhotoBridgeService {

    public static final String PHOTO_API_VERSION = "core.halo.run/v1alpha1";
    public static final String PHOTO_KIND = "Photo";
    public static final String PHOTO_GROUP_KIND = "PhotoGroup";
    public static final String MANAGED_LABEL_KEY = "api-gallery.kbmp.club/managed";
    public static final String MANAGED_LABEL_VALUE = "true";
    /** 名称前缀，便于在后台一眼认出是本插件同步进来的 */
    public static final String NAME_PREFIX = "photo-ag-";
    public static final String GROUP_NAME_PREFIX = "photo-group-ag-";

    private static final GroupVersionKind GVK =
        GroupVersionKind.fromAPIVersionAndKind(PHOTO_API_VERSION, PHOTO_KIND);
    private static final GroupVersionKind GROUP_GVK =
        GroupVersionKind.fromAPIVersionAndKind(PHOTO_API_VERSION, PHOTO_GROUP_KIND);

    /** 分组显示名 → 分组资源名，避免每条图片都去查一次 */
    private final Map<String, String> groupNames = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExtensionClient client;

    public PhotoBridgeService(ExtensionClient client) {
        this.client = client;
    }

    /**
     * 把图片元数据推送到图库。已存在的（同一图片地址）只更新 spec，不重复创建。
     *
     * @param items 图片元数据
     * @param groupOverride 统一分组名；为空时按数据源名自动分组
     * @return 本次新建的条数
     */
    public PushResult push(List<GalleryImageItem> items, String groupOverride) {
        PushResult result = new PushResult();
        if (items == null || items.isEmpty()) {
            return result;
        }
        for (GalleryImageItem item : items) {
            String name = nameOf(item.getUrl());
            try {
                var existing = client.fetch(GVK, name);
                if (existing.isPresent()) {
                    Unstructured photo = existing.get();
                    Map<String, Object> data = photo.getData();
                    data.put("spec", buildSpec(item, groupOverride));
                    ensureLabel(data);
                    client.update(photo);
                    result.updated++;
                } else {
                    client.create(buildPhoto(item, name, groupOverride));
                    result.created++;
                }
            } catch (Exception e) {
                result.failed++;
                if (result.failed <= 3) {
                    log.warn("[api-gallery] 写入图库失败（{}）：{}", name, e.getMessage());
                }
            }
        }
        if (result.failed > 0) {
            log.warn("[api-gallery] 写入图库共失败 {} 条，常见原因是未安装/未启用「图库管理」插件",
                result.failed);
        }
        log.info("[api-gallery] 已同步到图库：新建 {}，更新 {}，失败 {}",
            result.created, result.updated, result.failed);
        return result;
    }

    /**
     * 撤回本插件写入的图库记录（只删带托管标签的）。
     *
     * @param items 当前缓存的图片，用来推算需要删除的记录名
     * @return 删除条数
     */
    public int retract(List<GalleryImageItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (GalleryImageItem item : items) {
            String name = nameOf(item.getUrl());
            try {
                var existing = client.fetch(GVK, name);
                if (existing.isEmpty()) {
                    continue;
                }
                if (!isManaged(existing.get())) {
                    log.debug("[api-gallery] {} 不是本插件写入的，跳过删除", name);
                    continue;
                }
                client.delete(existing.get());
                removed++;
            } catch (Exception e) {
                log.warn("[api-gallery] 撤回图库记录失败（{}）：{}", name, e.getMessage());
            }
        }
        log.info("[api-gallery] 已从图库撤回 {} 条", removed);
        return removed;
    }

    /** 删除本插件创建的图库分组（撤回时用，只删带托管标签的） */
    public int retractGroups() {
        int removed = 0;
        for (String name : new ArrayList<>(groupNames.values())) {
            try {
                var existing = client.fetch(GROUP_GVK, name);
                if (existing.isEmpty() || !isManaged(existing.get())) {
                    continue;
                }
                client.delete(existing.get());
                removed++;
            } catch (Exception e) {
                log.warn("[api-gallery] 删除图库分组失败（{}）：{}", name, e.getMessage());
            }
        }
        if (removed > 0) {
            groupNames.clear();
        }
        log.info("[api-gallery] 已清理 {} 个图库分组", removed);
        return removed;
    }

    /** 图库插件是否可用（Photo 资源类型已注册） */
    public boolean available() {
        try {
            client.fetch(GVK, NAME_PREFIX + "availability-probe");
            return true;
        } catch (Exception e) {
            log.warn("[api-gallery] 未检测到图库资源类型，请确认已安装并启用「图库管理」插件：{}",
                e.getMessage());
            return false;
        }
    }

    private Unstructured buildPhoto(GalleryImageItem item, String name, String groupOverride) {
        Unstructured photo = new Unstructured(new LinkedHashMap<>());
        photo.setApiVersion(PHOTO_API_VERSION);
        photo.setKind(PHOTO_KIND);
        photo.setMetadata(managedMetadata(name));
        photo.getData().put("spec", buildSpec(item, groupOverride));
        return photo;
    }

    private Metadata managedMetadata(String name) {
        Metadata meta = new Metadata();
        meta.setName(name);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE);
        meta.setLabels(labels);
        return meta;
    }

    private Map<String, Object> buildSpec(GalleryImageItem item, String groupOverride) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("displayName", displayName(item));
        spec.put("url", item.getUrl());
        spec.put("cover", item.getUrl());
        spec.put("description", "来自 API 图源：" + item.getSource());
        String group = (groupOverride == null || groupOverride.isBlank())
            ? item.getSource() : groupOverride;
        if (group != null && !group.isBlank()) {
            // spec.groupName 存的是 PhotoGroup 资源的 name，不是显示名
            spec.put("groupName", ensureGroup(group));
        }
        List<String> tags = new ArrayList<>();
        if (item.getTags() != null) {
            tags.addAll(item.getTags());
        }
        if (item.getSource() != null && !item.getSource().isBlank()
            && !tags.contains(item.getSource())) {
            tags.add(item.getSource());
        }
        if (!tags.isEmpty()) {
            spec.put("tags", tags);
        }
        spec.put("priority", 0);
        return spec;
    }

    /**
     * 确保分组存在：图库的 spec.groupName 引用的是 PhotoGroup 资源的 name，
     * 只写显示名的话后台分组下拉里不会出现，所以这里一并创建分组资源。
     */
    private String ensureGroup(String displayName) {
        return groupNames.computeIfAbsent(displayName, dn -> {
            String name = groupNameOf(dn);
            try {
                if (client.fetch(GROUP_GVK, name).isEmpty()) {
                    Unstructured group = new Unstructured(new LinkedHashMap<>());
                    group.setApiVersion(PHOTO_API_VERSION);
                    group.setKind(PHOTO_GROUP_KIND);
                    group.setMetadata(managedMetadata(name));
                    Map<String, Object> spec = new LinkedHashMap<>();
                    spec.put("displayName", dn);
                    spec.put("priority", 0);
                    group.getData().put("spec", spec);
                    client.create(group);
                    log.info("[api-gallery] 已创建图库分组「{}」（{}）", dn, name);
                }
            } catch (Exception e) {
                log.warn("[api-gallery] 创建图库分组「{}」失败：{}", dn, e.getMessage());
            }
            return name;
        });
    }

    /** 分组显示名 → 合法资源名 */
    public static String groupNameOf(String displayName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(displayName.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return GROUP_NAME_PREFIX + sb;
        } catch (Exception e) {
            return GROUP_NAME_PREFIX + Math.abs(displayName.hashCode());
        }
    }

    private String displayName(GalleryImageItem item) {
        if (item.getTitle() != null && !item.getTitle().isBlank()) {
            return item.getTitle();
        }
        String url = item.getUrl();
        int slash = url.lastIndexOf('/');
        String tail = slash >= 0 ? url.substring(slash + 1) : url;
        int q = tail.indexOf('?');
        if (q > 0) {
            tail = tail.substring(0, q);
        }
        return tail.isBlank() ? item.getSource() : tail;
    }

    /** 同一图片地址生成同一条记录，保证重复同步不会堆积 */
    public static String nameOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return NAME_PREFIX + sb;
        } catch (Exception e) {
            return NAME_PREFIX + Math.abs(url.hashCode());
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureLabel(Map<String, Object> data) {
        Object meta = data.get("metadata");
        if (!(meta instanceof Map<?, ?> metaMap)) {
            return;
        }
        Map<String, Object> mm = (Map<String, Object>) metaMap;
        Object labels = mm.get("labels");
        if (!(labels instanceof Map<?, ?>)) {
            mm.put("labels", new LinkedHashMap<String, String>());
            labels = mm.get("labels");
        }
        ((Map<String, Object>) labels).put(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE);
    }

    private boolean isManaged(Unstructured photo) {
        Object meta = photo.getData().get("metadata");
        if (meta instanceof Map<?, ?> metaMap) {
            Object labels = metaMap.get("labels");
            if (labels instanceof Map<?, ?> labelMap) {
                return MANAGED_LABEL_VALUE.equals(String.valueOf(labelMap.get(MANAGED_LABEL_KEY)));
            }
        }
        return false;
    }

    /** 推送结果统计 */
    public static class PushResult {
        public int created;
        public int updated;
        public int failed;

        public int total() {
            return created + updated;
        }
    }
}
