package io.kbmp.gallery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Spring 装配冒烟测试。
 *
 * <p>1.5.0 首次部署时曾因「多构造器且未标注 @Autowired」导致
 * {@code No default constructor found}，整个插件启动失败（phase=FAILED），
 * 而纯单元测试全部通过，完全覆盖不到这一类问题。
 * 这里直接起一个最小 Spring 上下文验证 Bean 能真正被创建，
 * 把装配错误挡在构建期，而不是等上传到站点后才发现。
 */
class GallerySnapshotStoreWiringTest {

    @Test
    void snapshotStoreCanBeCreatedBySpring() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(GallerySnapshotStore.class);
            ctx.refresh();
            GallerySnapshotStore bean = ctx.getBean(GallerySnapshotStore.class);
            assertNotNull(bean, "Spring 必须能用 Environment 构造器创建快照存储");
            assertNotNull(bean.getFile(), "快照路径应已根据工作目录解析");
            assertTrue(bean.getFile().toString().endsWith("snapshot.json"));
        }
    }
}
