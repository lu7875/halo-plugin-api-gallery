package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.Unstructured;

/**
 * 校验「写入图库」桥接逻辑：幂等更新、分组、以及只撤回自己写入的记录。
 */
class PhotoBridgeServiceTest {

    /** ExtensionClient 的极简替身，只实现桥接用到的 fetch/create/update/delete */
    static class FakeClient implements InvocationHandler {

        final Map<String, Unstructured> store = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>();
        final List<String> updated = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "fetch" -> {
                    if (args != null && args[0] instanceof GroupVersionKind) {
                        return Optional.ofNullable(store.get((String) args[1]));
                    }
                    return Optional.empty();
                }
                case "create" -> {
                    Unstructured u = (Unstructured) args[0];
                    store.put(nameOf(u), u);
                    created.add(nameOf(u));
                    return null;
                }
                case "update" -> {
                    Unstructured u = (Unstructured) args[0];
                    store.put(nameOf(u), u);
                    updated.add(nameOf(u));
                    return null;
                }
                case "delete" -> {
                    Unstructured u = (Unstructured) args[0];
                    store.remove(nameOf(u));
                    deleted.add(nameOf(u));
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }

        static String nameOf(Unstructured u) {
            Object meta = u.getData().get("metadata");
            if (meta instanceof Map<?, ?> m) {
                return String.valueOf(m.get("name"));
            }
            if (meta instanceof MetadataOperator mo) {
                return mo.getName();
            }
            return null;
        }
    }

    private FakeClient fake;
    private PhotoBridgeService bridge;

    @BeforeEach
    void setUp() {
        fake = new FakeClient();
        ExtensionClient client = (ExtensionClient) Proxy.newProxyInstance(
            ExtensionClient.class.getClassLoader(),
            new Class<?>[] {ExtensionClient.class},
            fake);
        bridge = new PhotoBridgeService(client);
    }

    private static GalleryImageItem item(String url, String title, String source) {
        return new GalleryImageItem("id-" + url.hashCode(), url, title, source, List.of("acg"));
    }

    @Test
    void pushesMetadataWithoutDownloading() {
        List<GalleryImageItem> items = List.of(
            item("https://cdn/a.webp", "图A", "派次元"),
            item("https://cdn/b.webp", "图B", "派次元"));
        PhotoBridgeService.PushResult r = bridge.push(items, null);
        assertEquals(2, r.created);
        assertEquals(0, r.failed);

        Unstructured photo = fake.store.get(PhotoBridgeService.nameOf("https://cdn/a.webp"));
        assertEquals(PhotoBridgeService.PHOTO_API_VERSION, photo.getApiVersion());
        assertEquals("Photo", photo.getKind());
        Map<String, Object> spec = specOf(photo);
        assertEquals("https://cdn/a.webp", spec.get("url"));
        assertEquals("图A", spec.get("displayName"));
        assertEquals(PhotoBridgeService.groupNameOf("派次元"), spec.get("groupName"),
            "未指定分组时按数据源名分组");
        assertTrue(labelsOf(photo).containsValue("true"), "必须带托管标签以便撤回");

        // 分组资源要一并创建，否则后台分组下拉里不会出现
        Unstructured group = fake.store.get(PhotoBridgeService.groupNameOf("派次元"));
        assertEquals("PhotoGroup", group.getKind());
        assertEquals("派次元", specOf(group).get("displayName"), "分组的显示名才是可读名称");
    }

    @Test
    void secondPushUpdatesInsteadOfCreating() {
        List<GalleryImageItem> items = List.of(item("https://cdn/a.webp", "图A", "派次元"));
        bridge.push(items, null);
        PhotoBridgeService.PushResult r = bridge.push(items, null);
        assertEquals(0, r.created);
        assertEquals(1, r.updated);
        long photos = fake.store.values().stream()
            .filter(u -> "Photo".equals(u.getKind())).count();
        assertEquals(1, photos, "同一地址不应产生重复记录");
    }

    @Test
    void groupOverrideWins() {
        bridge.push(List.of(item("https://cdn/a.webp", "图A", "派次元")), "统一分组");
        Map<String, Object> spec = specOf(
            fake.store.get(PhotoBridgeService.nameOf("https://cdn/a.webp")));
        assertEquals(PhotoBridgeService.groupNameOf("统一分组"), spec.get("groupName"));
        assertEquals("统一分组",
            specOf(fake.store.get(PhotoBridgeService.groupNameOf("统一分组"))).get("displayName"));
    }

    @Test
    void retractAlsoCleansUpGroups() {
        bridge.push(List.of(item("https://cdn/a.webp", "图A", "派次元")), null);
        assertTrue(fake.store.containsKey(PhotoBridgeService.groupNameOf("派次元")));
        assertEquals(1, bridge.retractGroups());
        assertFalse(fake.store.containsKey(PhotoBridgeService.groupNameOf("派次元")));
    }

    @Test
    void retractOnlyRemovesManagedRecords() {
        GalleryImageItem mine = item("https://cdn/a.webp", "图A", "派次元");
        bridge.push(List.of(mine), null);

        // 手工放一条同名但不带托管标签的记录，模拟用户自己上传的照片
        Unstructured manual = new Unstructured(new LinkedHashMap<>());
        manual.setApiVersion(PhotoBridgeService.PHOTO_API_VERSION);
        manual.setKind(PhotoBridgeService.PHOTO_KIND);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", PhotoBridgeService.nameOf("https://cdn/b.webp"));
        manual.getData().put("metadata", meta);
        fake.store.put(PhotoBridgeService.nameOf("https://cdn/b.webp"), manual);

        int removed = bridge.retract(List.of(mine,
            item("https://cdn/b.webp", "图B", "派次元")));
        assertEquals(1, removed, "只删本插件写入的");
        assertFalse(fake.store.containsKey(PhotoBridgeService.nameOf("https://cdn/a.webp")));
        assertTrue(fake.store.containsKey(PhotoBridgeService.nameOf("https://cdn/b.webp")),
            "用户自己的照片必须保留");
    }

    @Test
    void nameIsStable() {
        assertEquals(PhotoBridgeService.nameOf("https://cdn/a.webp"),
            PhotoBridgeService.nameOf("https://cdn/a.webp"));
        assertTrue(PhotoBridgeService.nameOf("https://cdn/a.webp")
            .startsWith(PhotoBridgeService.NAME_PREFIX));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(Unstructured photo) {
        return (Map<String, Object>) photo.getData().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labelsOf(Unstructured photo) {
        Object meta = photo.getData().get("metadata");
        Map<String, Object> m = (Map<String, Object>) meta;
        return (Map<String, String>) m.get("labels");
    }
}
