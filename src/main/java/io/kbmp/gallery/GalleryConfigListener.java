package io.kbmp.gallery;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.PluginConfigUpdatedEvent;

/**
 * 监听后台「保存设置」事件，保存后立刻重新同步。
 *
 * <p>与 {@link ApiGalleryPlugin} 里监听 ConfigMap 的 Watcher 互为双保险：
 * 只要任意一条链路生效，新增/修改数据源就能立即反映到前端。
 * 两条同时触发也没关系，{@link GalleryService#sync()} 内部有 2 秒节流。
 */
@Slf4j
@Component
public class GalleryConfigListener implements DisposableBean {

    private final GalleryService service;
    private final GalleryAccessSetup accessSetup;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "api-gallery-config");
        t.setDaemon(true);
        return t;
    });

    public GalleryConfigListener(GalleryService service, GalleryAccessSetup accessSetup) {
        this.service = service;
        this.accessSetup = accessSetup;
    }

    @EventListener
    public void onPluginConfigUpdated(PluginConfigUpdatedEvent event) {
        log.info("[api-gallery] 收到设置保存事件，立即同步");
        executor.submit(() -> {
            try {
                int n = service.sync();
                // 开关可能同时被改动（如允许访客查看）
                accessSetup.apply(service.loadAllowAnonymous());
                log.info("[api-gallery] 设置保存后同步完成，缓存 {} 条", n);
            } catch (Throwable t) {
                log.error("[api-gallery] 设置保存后同步失败", t);
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
