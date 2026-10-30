package io.kbmp.gallery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.Role;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.infra.AnonymousUserConst;

/**
 * 给访客（未登录用户）放行图库的只读接口。
 *
 * <p>Halo 的 {@code /apis/**} 默认要求登录，未认证访问会被重定向到
 * {@code /login?authentication_required}。图库要能在前台页面展示，就必须让游客也能读。
 * 这里在插件启动时幂等地创建一个只含只读规则的 Role，并把它绑定到匿名用户，
 * 后台无需任何手工操作；失败时只记录日志，不影响插件其它功能。
 */
@Slf4j
@Component
public class GalleryAccessSetup {

    /** 对外公开的只读组：组内只有 /items 与 /random 两个安全接口，可放心放行给访客 */
    public static final String API_GROUP = "api.gallery.halo.run";

    /**
     * 管理/诊断组：含数据源明细（图源地址）、实时拉图诊断、同步与写入图库。
     *
     * <p>这个组**不会**向匿名角色做任何授权，因此匿名访问会被 Halo 拦下，
     * 只有具备相应权限的管理端使用者可用。
     */
    public static final String ADMIN_API_GROUP = "console.gallery.halo.run";

    public static final String ROLE_NAME = "api-gallery-viewer";
    public static final String BINDING_NAME = "api-gallery-viewer-binding";

    private final ExtensionClient client;

    public GalleryAccessSetup(ExtensionClient client) {
        this.client = client;
    }

    /** 幂等放行；allowAnonymous 为 false 时改为尝试撤销放行 */
    public void apply(boolean allowAnonymous) {
        try {
            if (allowAnonymous) {
                ensureRole();
                ensureBinding();
                log.info("[api-gallery] 已放行图库只读接口给访客（角色 {}, 绑定 {}）",
                    ROLE_NAME, BINDING_NAME);
            } else {
                revoke();
            }
        } catch (Exception e) {
            log.warn("[api-gallery] 设置访客访问权限失败（可到「后台 → 角色」手动给匿名角色授权）：{}",
                e.getMessage());
        }
    }

    /**
     * 只为访客放行 {@link #API_GROUP} 这一个公开组。
     *
     * <p>该组内只有 {@code /items} 与 {@code /random} 两个只读看图接口，
     * 不会泄露图源地址、也不会触发服务端对外请求。
     * 触发实时拉取、暴露图源地址或会改动数据的接口全部在 {@link #ADMIN_API_GROUP} 组，
     * 这里不做任何放行，因此匿名访问会被 Halo 直接拒绝（401/403）。
     */
    private void ensureRole() {
        Role role = client.fetch(Role.class, ROLE_NAME).orElseGet(this::newRole);
        role.setRules(List.of(new Role.PolicyRule(
            new String[] {API_GROUP},
            new String[] {"*"},
            null,
            null,
            new String[] {"get", "list"})));
        // 旧版本创建的角色可能没有聚合标签，这里每次都覆盖，保证升级后同样生效
        if (role.getMetadata() == null) {
            role.setMetadata(new Metadata());
            role.getMetadata().setName(ROLE_NAME);
        }
        role.getMetadata().setLabels(managedLabels());
        if (client.fetch(Role.class, ROLE_NAME).isPresent()) {
            client.update(role);
        } else {
            client.create(role);
        }
    }

    /**
     * Halo 官方的授权方式：Role 上打聚合标签，规则会自动合并进目标角色。
     * 这里同时聚合给 anonymous（游客）和 authenticated（登录用户）。
     */
    private Map<String, String> managedLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Role.ROLE_AGGREGATE_LABEL_PREFIX + "anonymous", "true");
        labels.put(Role.ROLE_AGGREGATE_LABEL_PREFIX + "authenticated", "true");
        // 隐藏：只在后台角色列表里不干扰管理
        labels.put(Role.HIDDEN_LABEL_NAME, "true");
        return labels;
    }

    private Role newRole() {
        Role role = new Role();
        role.setApiVersion(apiVersion(Role.GROUP, Role.VERSION));
        role.setKind(Role.KIND);
        Metadata meta = new Metadata();
        meta.setName(ROLE_NAME);
        meta.setLabels(managedLabels());
        role.setMetadata(meta);
        return role;
    }

    private void ensureBinding() {
        RoleBinding binding = client.fetch(RoleBinding.class, BINDING_NAME)
            .orElseGet(this::newBinding);
        binding.setSubjects(List.of(anonymousSubject()));
        binding.setRoleRef(roleRef());
        if (client.fetch(RoleBinding.class, BINDING_NAME).isPresent()) {
            client.update(binding);
        } else {
            client.create(binding);
        }
    }

    private RoleBinding newBinding() {
        RoleBinding binding = new RoleBinding();
        binding.setApiVersion(apiVersion(RoleBinding.GROUP, RoleBinding.VERSION));
        binding.setKind(RoleBinding.KIND);
        Metadata meta = new Metadata();
        meta.setName(BINDING_NAME);
        binding.setMetadata(meta);
        return binding;
    }

    private RoleBinding.Subject anonymousSubject() {
        RoleBinding.Subject subject = new RoleBinding.Subject();
        subject.setKind("User");
        subject.setName(AnonymousUserConst.PRINCIPAL);
        subject.setApiGroup("");
        return subject;
    }

    private RoleBinding.RoleRef roleRef() {
        RoleBinding.RoleRef ref = new RoleBinding.RoleRef();
        ref.setKind(Role.KIND);
        ref.setName(ROLE_NAME);
        ref.setApiGroup("");
        return ref;
    }

    /** 关闭匿名访问：删掉绑定与角色 */
    private void revoke() {
        client.fetch(RoleBinding.class, BINDING_NAME).ifPresent(client::delete);
        client.fetch(Role.class, ROLE_NAME).ifPresent(client::delete);
    }

    private static String apiVersion(String group, String version) {
        return (group == null || group.isBlank()) ? version : group + "/" + version;
    }
}
