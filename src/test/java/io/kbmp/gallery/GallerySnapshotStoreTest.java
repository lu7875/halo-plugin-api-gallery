package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 快照读写：往返一致、目录自动创建、坏文件隔离、空数据处理 */
class GallerySnapshotStoreTest {

    @TempDir
    Path tempDir;

    private GalleryImageItem item(String id, String url) {
        return new GalleryImageItem(id, url, "标题" + id, "源A", List.of("acg"));
    }

    @Test
    void saveThenLoadRoundtrip() {
        Path file = tempDir.resolve("plugins/api-gallery/snapshot.json");
        GallerySnapshotStore store = new GallerySnapshotStore(file);
        store.save(List.of(item("a", "https://cdn/1.webp"), item("b", "https://cdn/2.webp")));

        assertTrue(Files.isRegularFile(file), "快照文件应已写入");
        assertNotNull(store.getSavedAt(), "保存后应记录时间");

        List<GalleryImageItem> loaded = new GallerySnapshotStore(file).load();
        assertEquals(2, loaded.size());
        assertEquals("https://cdn/1.webp", loaded.get(0).getUrl());
        assertEquals("源A", loaded.get(0).getSource());
        assertEquals(List.of("acg"), loaded.get(0).getTags());
    }

    @Test
    void createsParentDirectories() {
        Path file = tempDir.resolve("a/b/c/d/snapshot.json");
        new GallerySnapshotStore(file).save(List.of(item("x", "https://cdn/x.webp")));
        assertTrue(Files.isRegularFile(file), "多级目录不存在时应自动创建");
    }

    @Test
    void loadReturnsEmptyWhenFileMissing() {
        GallerySnapshotStore store = new GallerySnapshotStore(tempDir.resolve("none.json"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void corruptFileIsQuarantinedInsteadOfFailing() throws Exception {
        Path file = tempDir.resolve("snapshot.json");
        Files.writeString(file, "{ this is not valid json ");
        GallerySnapshotStore store = new GallerySnapshotStore(file);

        assertTrue(store.load().isEmpty(), "坏快照应按空图库处理，不能拖垮启动");
        assertTrue(Files.isRegularFile(tempDir.resolve("snapshot.json.corrupt")),
            "坏文件应被改名留存，便于排查");
    }

    @Test
    void invalidEntriesAreSkipped() {
        Path file = tempDir.resolve("snapshot.json");
        GallerySnapshotStore store = new GallerySnapshotStore(file);
        store.save(List.of(item("ok", "https://cdn/ok.webp"), item("bad", "  ")));
        List<GalleryImageItem> loaded = store.load();
        assertEquals(1, loaded.size(), "地址为空的条目不应恢复");
        assertEquals("https://cdn/ok.webp", loaded.get(0).getUrl());
    }

    @Test
    void emptySnapshotIsAllowed() {
        Path file = tempDir.resolve("snapshot.json");
        GallerySnapshotStore store = new GallerySnapshotStore(file);
        store.save(List.of());
        assertTrue(store.load().isEmpty());
    }
}
