package io.kbmp.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 图库元数据的本地快照存储。
 *
 * <p>1.4.x 的图库缓存只存在于内存（{@code CopyOnWriteArrayList}），Halo 重启、
 * 升级核心或重载插件后图库会变空，必须等下一轮同步；如果此时图源恰好不可用，
 * 前台就会长时间没有图片。这里把「图片地址等元数据」落到工作目录的 JSON 快照，
 * 启动时先恢复快照再后台同步，保证重启后立刻有图可看。
 *
 * <p>注意：快照里只有 URL / 标题 / 来源 / 标签等元数据，**不包含图片文件**，
 * 也不会写入 Halo 附件库，因此不改变插件「零存储图片」的定位。
 *
 * <p>写入采用「临时文件 + 原子重命名」，避免同步中途被 kill 留下半个 JSON；
 * 所有异常都只记日志、不向上抛，持久化失败绝不影响同步主流程。
 */
@Slf4j
@Component
public class GallerySnapshotStore {

    /** 快照格式版本，后续字段变更时用于兼容判断 */
    static final int FORMAT_VERSION = 1;
    /** 快照最大条目数，防止图库膨胀时写出超大文件拖慢启动 */
    static final int MAX_ITEMS = 20000;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Instant savedAt;

    /**
     * 生产环境：按 Halo 工作目录定位快照文件。
     *
     * <p>必须显式标注 {@link Autowired}：本类还有一个接收 {@link Path} 的测试构造器，
     * 多构造器时 Spring 不会自动选择，而会去找不存在的无参构造器，
     * 导致整个插件启动失败（No default constructor found）。
     */
    @Autowired
    public GallerySnapshotStore(Environment env) {
        this(defaultFile(env));
    }

    /** 测试或手工指定路径时使用 */
    GallerySnapshotStore(Path file) {
        this.file = file;
    }

    static Path defaultFile(Environment env) {
        String workDir = env == null ? null : env.getProperty("halo.work-dir");
        Path base = (workDir == null || workDir.isBlank())
            ? Path.of(System.getProperty("user.home"), ".halo2")
            : Path.of(workDir.trim());
        return base.resolve("plugins").resolve("api-gallery").resolve("snapshot.json");
    }

    public Path getFile() {
        return file;
    }

    /** 最近一次成功写入快照的时间（内存值，重启后由 load 恢复） */
    public Instant getSavedAt() {
        return savedAt;
    }

    /** 原子写入快照；items 超过上限时只保留最新的部分 */
    public synchronized void save(List<GalleryImageItem> items) {
        if (items == null) {
            return;
        }
        try {
            List<GalleryImageItem> data = items.size() > MAX_ITEMS
                ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : new ArrayList<>(items);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", FORMAT_VERSION);
            payload.put("savedAt", Instant.now().toString());
            payload.put("count", data.size());
            payload.put("items", data);

            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), payload);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            savedAt = Instant.now();
            log.debug("[api-gallery] 图库快照已保存：{} 条 -> {}", data.size(), file);
        } catch (Exception e) {
            log.warn("[api-gallery] 保存图库快照失败（不影响同步）：{}", e.getMessage());
        }
    }

    /**
     * 读取快照。文件不存在返回空列表；文件损坏时把坏文件改名留存后返回空列表，
     * 不会因为一份坏快照导致插件启动失败。
     */
    public synchronized List<GalleryImageItem> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.readValue(file.toFile(), Map.class);
            Object rawItems = payload.get("items");
            if (!(rawItems instanceof List<?> list)) {
                return List.of();
            }
            List<GalleryImageItem> out = new ArrayList<>();
            for (Object o : list) {
                GalleryImageItem item = mapper.convertValue(o, GalleryImageItem.class);
                if (item.getUrl() == null || item.getUrl().isBlank()) {
                    continue;
                }
                out.add(item);
            }
            Object at = payload.get("savedAt");
            if (at != null) {
                try {
                    savedAt = Instant.parse(String.valueOf(at));
                } catch (Exception ignore) {
                    // 时间字段损坏不影响数据恢复
                }
            }
            log.info("[api-gallery] 已从快照恢复 {} 条图库元数据（{}）", out.size(), file);
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("[api-gallery] 图库快照读取失败，按空图库启动：{}", e.getMessage());
            quarantineCorrupt();
            return List.of();
        }
    }

    private void quarantineCorrupt() {
        try {
            Path bak = file.resolveSibling(file.getFileName() + ".corrupt");
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignore) {
            // 坏文件移不走也无所谓，下次 save 会覆盖
        }
    }
}
