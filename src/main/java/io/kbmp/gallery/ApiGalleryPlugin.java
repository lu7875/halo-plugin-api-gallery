package io.kbmp.gallery;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Watcher;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * API 图库插件入口。
 *
 * <p>职责：
 * <ol>
 *   <li>启动后立即同步一次，让前端马上有数据；</li>
 *   <li>按固定周期重新拉取各数据源，保持素材更新；</li>
 *   <li>监听后台配置变化，新增/修改数据源时立刻同步，无需等待下一周期。</li>
 * </ol>
 */
@Slf4j
@Component
public class ApiGalleryPlugin extends BasePlugin {

    /** 默认同步周期（分钟） */
    private static final long DEFAULT_INTERVAL_MINUTES = 60;

    private final GalleryService galleryService;
    private final GalleryAccessSetup accessSetup;
    private final ExtensionClient client;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> periodicSync;
    private ScheduledFuture<?> deadLinkCheck;
    private Disposable configWatcher;

    public ApiGalleryPlugin(PluginContext pluginContext, GalleryService galleryService,
        GalleryAccessSetup accessSetup, ExtensionClient client) {
        super(pluginContext);
        this.galleryService = galleryService;
        this.accessSetup = accessSetup;
        this.client = client;
    }

    @Override
    public void start() {
        log.info("[api-gallery] 插件启动，开始初始化图库数据");
        galleryService.reloadSources();
        // 先从本地快照恢复：Halo 重启 / 升级核心后也能立刻有图，不必干等下一轮同步
        int restored = galleryService.restoreSnapshot();
        log.info("[api-gallery] 启动时图库已有 {} 条（含快照恢复）", restored);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "api-gallery-sync");
            t.setDaemon(true);
            return t;
        });

        // 启动 3 秒后做一次全量同步
        executor.schedule(this::safeSync, 3, TimeUnit.SECONDS);
        // 之后按后台配置的周期同步（后台改周期时会被重新调度）
        reschedulePeriodicSync();

        // 给访客放行图库只读接口（Halo 的 /apis/** 默认需要登录）
        executor.schedule(this::safeApplyAccess, 1, TimeUnit.SECONDS);

        // 后台配置一变（新增/修改 API 数据源），立刻同步
        configWatcher = new ConfigWatcher();
        client.watch((Watcher) configWatcher);
        log.info("[api-gallery] 初始化完成，同步周期 {} 分钟，下次 {}",
            galleryService.getIntervalMinutes(), galleryService.getNextSyncAt());
    }

    @Override
    public void stop() {
        // 停止前把最后一版缓存落盘，下次启动可直接恢复
        galleryService.saveSnapshotOnStop();
        if (configWatcher != null && !configWatcher.isDisposed()) {
            configWatcher.dispose();
        }
        if (periodicSync != null) {
            periodicSync.cancel(true);
        }
        if (deadLinkCheck != null) {
            deadLinkCheck.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("[api-gallery] 插件已停止");
    }

    /**
     * （重新）排定周期同步任务。
     *
     * <p>周期只在启动时读一次的话，后台把 60 分钟改成 5 分钟后仍然是 60 分钟跑一次，
     * 看起来就是「设置了自动同步却一直没反应」。所以后台一保存设置就重新排一次。
     */
    private synchronized void reschedulePeriodicSync() {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        if (periodicSync != null) {
            periodicSync.cancel(false);
        }
        long interval = Math.max(5, galleryService.loadSyncIntervalMinutes());
        periodicSync = executor.scheduleWithFixedDelay(this::safeSync, interval, interval,
            TimeUnit.MINUTES);
        galleryService.markNextSync(interval);
        log.info("[api-gallery] 周期同步已排定：每 {} 分钟一次，下次 {}",
            interval, galleryService.getNextSyncAt());
        rescheduleDeadLinkCheck();
    }

    /**
     * 死链巡检独立排期，与同步分开，避免抽样探测拖慢同步主流程。
     *
     * <p>首次延迟 10 分钟：启动后先让快照恢复与首轮同步完成，再开始探测第三方图片，
     * 否则刚启动就对空缓存或旧快照发起一批无效请求。
     */
    private synchronized void rescheduleDeadLinkCheck() {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        if (deadLinkCheck != null) {
            deadLinkCheck.cancel(false);
        }
        long interval = Math.max(15, galleryService.intSetting(GalleryService.STABILITY_KEY,
            "deadLinkCheckIntervalMinutes", 60, 15, 1440));
        deadLinkCheck = executor.scheduleWithFixedDelay(this::safeDeadLinkCheck, 10, interval,
            TimeUnit.MINUTES);
        log.info("[api-gallery] 死链巡检已排定：每 {} 分钟一次", interval);
    }

    private void safeDeadLinkCheck() {
        try {
            var result = galleryService.checkDeadLinks();
            log.info("[api-gallery] 死链巡检完成：{}", result);
        } catch (Throwable t) {
            log.error("[api-gallery] 死链巡检异常", t);
        }
    }

    private void safeApplyAccess() {
        try {
            accessSetup.apply(galleryService.loadAllowAnonymous());
        } catch (Throwable t) {
            log.error("[api-gallery] 设置访客访问权限异常", t);
        }
    }

    private void safeSync() {
        try {
            int n = galleryService.sync();
            log.info("[api-gallery] 定时同步完成，当前缓存 {} 条图片元数据", n);
        } catch (Throwable t) {
            log.error("[api-gallery] 定时同步异常", t);
        }
    }

    /** 监听插件自己的 ConfigMap，后台保存设置后立即生效 */
    private class ConfigWatcher implements Watcher {

        private final java.util.concurrent.atomic.AtomicBoolean disposed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public void onAdd(Extension extension) {
            handle(extension);
        }

        @Override
        public void onUpdate(Extension oldExtension, Extension newExtension) {
            handle(newExtension);
        }

        @Override
        public void onDelete(Extension extension) {
            if (isMine(extension)) {
                galleryService.reloadSources();
            }
        }

        private void handle(Extension extension) {
            if (!isMine(extension)) {
                return;
            }
            log.info("[api-gallery] 检测到数据源配置变更，立即同步");
            // 新线程执行，避免阻塞事件派发
            if (executor != null && !executor.isShutdown()) {
                // 同步周期可能也被改了，必须重新排定，否则还是按旧周期跑
                executor.submit(ApiGalleryPlugin.this::reschedulePeriodicSync);
                executor.submit(ApiGalleryPlugin.this::safeSync);
                // 开关可能同时被改动（如允许访客查看），一并重新应用
                executor.submit(ApiGalleryPlugin.this::safeApplyAccess);
            }
        }

        private boolean isMine(Extension extension) {
            return extension instanceof ConfigMap cm
                && GalleryService.CONFIG_MAP_NAME.equals(cm.getMetadata().getName());
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }

        @Override
        public void dispose() {
            disposed.set(true);
        }
    }
}
