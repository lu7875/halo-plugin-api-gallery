# API 图库插件（api-gallery）

Halo 2.x 插件。在后台配置任意**图片 API 地址**，插件定时拉取素材并在前端展示。
**图片只缓存地址等元数据，文件始终由第三方图源提供，不下载、不写入附件库，不占用本地存储。**

> 当前版本 **1.4.1**。从旧版升级请看 [十、常见问题](#十常见问题) 里的接口路径迁移说明。

## 一、安全设计

本插件会**根据后台配置的地址向外发起 HTTP 请求**，因此对外网出口和接口权限做了明确边界。

### 1. SSRF 防护（1.4.0 新增）

`ApiUrlSafety` 对所有待请求的 HTTP 目标做前置校验，不通过直接抛错、不发起连接：

| 检查项 | 拦截内容 |
|---|---|
| 协议 | 只允许 `http` / `https`，拦截 `file`、`ftp`、`jar`、`gopher` 等 |
| 主机 | `http://` 必须带主机名，空主机直接拒绝 |
| 长度 | URL 超过 2048 字符拒绝 |
| 回环 | `127.0.0.0/8`、`::1`、`localhost` |
| 私有网段 | `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16` |
| 链路本地 | `169.254.0.0/16`（含云厂商元数据端点 `169.254.169.254`） |
| IPv6 唯一本地 | `fc00::/7` |
| 组播 / 广播 | `224.0.0.0/4`、`255.255.255.255` |
| DNS 解析 | 域名解析后的**每一个** A / AAAA 记录都要过网段检查，防止 DNS rebinding 与域名指向内网 |

校验在**每次请求前**执行（不是只在保存配置时校验），所以后台改完配置、DNS 后续被劫持也会被拦。

### 1.1 跳转目标逐跳校验（1.4.1 新增）

只校验初始地址不够：如果图源返回 302/307，把 `Location` 指向内网、回环或云元数据地址，
HTTP 客户端的自动跟随就会绕过上面的校验。因此 1.4.1 起：

- 所有对外请求一律**关闭自动跟随跳转**（`Redirect.NEVER`）；
- 需要跨跳转的请求改为**手动逐跳跟随**，每一跳跳转前都对目标地址重新执行完整的
  「协议 + 主机 + DNS 解析 IP」校验，跳转目标必须同样是公网 http/https 地址；
- 限制最大 5 跳，防止重定向循环；
- `Location` 支持相对路径与协议相对路径（基于当前地址解析后再校验）。

### 2. 接口权限边界（1.4.0 收紧）

插件对外接口分成**两个 group**，权限互不相干：

| 接口组 | API Group | 放行给匿名用户 | 内容 |
|---|---|---|---|
| 公开只读组 | `api.gallery.halo.run` | ✅ 可放行（默认开启，可关） | `/items`、`/random` |
| 管理诊断组 | `console.gallery.halo.run` | ❌ **永不对匿名放行** | `/sources`、`/diagnose`、`/sync`、`/push-to-photos`、`/photos` |

1.3.1 及以前，所有接口共用 `api.gallery.halo.run` 一个组，而匿名角色拿到的是「该组的 `get`/`list`」，
导致**访客可以匿名读取 `/sources`（数据源地址明细）和 `/diagnose`（含图源响应体）**。
1.4.0 把这两类接口拆开，匿名角色只保留公开只读组的读权限。

### 3. 诊断接口脱敏（1.4.0）

`/diagnose` 不再返回图源响应体片段和异常堆栈栈顶，改为只回：

- `httpStatus`、`contentType`、`bodyLength`
- `looksLikeJson`（首字符是否为 `{` 或 `[`）
- `exception`（异常类名）

用于判断「图源返回的是不是 JSON」的信息量足够，但不会泄露第三方响应内容。

### 4. 写操作隔离

写入「图库管理」的 `Photo` 记录统一带标签 `api-gallery.kbmp.club/managed=true`，
撤回时只删这一批，**不会触碰手工上传的照片**。

## 二、特性

| 特性 | 说明 |
|---|---|
| 多数据源 | 后台可添加任意数量的 API 图源，各自独立开关 |
| 零存储 | 只保存 `{id, url, title, tags, source}` 元数据，图片由浏览器直连图源 CDN |
| 自动生效 | 后台新增/修改数据源并保存后，**立即触发同步**，不用等下一周期、不用重启 |
| 定时更新 | 默认每 60 分钟重新拉取一次素材（后台可调，5–1440 分钟） |
| 结构自适应 | 自动识别数组 / `{items:[...]}` / 单个对象（随机图接口）三种响应 |
| 字段映射 | url、id、title、tags 字段名可单独指定，留空自动推断常见字段名 |
| 开箱可用 | 未配置时内置派次元示例源，装完就有图 |
| 访客可读 | 自动给匿名用户只读权限，前台页面不必登录即可展示（可关闭） |
| 对接图库 | 把图片地址写成 Photo 记录，直接在后台 `/console/photos` 和主题 `/photos` 显示，仍不占存储 |

## 三、安装

1. Halo 后台 → **插件** → **安装** → 上传 `build/libs/api-gallery-1.4.1.jar`
2. 启用插件
3. 插件详情 → **设置**，在「API 数据源」里添加你的图源

### 添加派次元作为示例

| 字段 | 填 |
|---|---|
| 名称 | 派次元 |
| API 地址 | `https://t.paiii.cn/api/list` |
| 响应结构 | 自动识别 |

保存后约 1–3 秒完成首次同步，在「同步设置」里可看到张数。

### 添加随机图类型的源

每次请求返回不同图的源（随机单张 JSON、或 302 跳到随机图），插件会**重复采样并去重**攒成图集。

| 字段 | 填 |
|---|---|
| 名称 | 动漫（显示用，也是图库分组名） |
| API 地址 | `https://api.6045833.xyz/dongman` |
| 响应结构 | **302 跳转到随机图片（无列表接口）** |
| 采样次数 | 30（每次同步请求多少次，默认 30，上限 200） |
| 最多累积 | 60（该源图池上限，默认 60，上限 5000） |

**滚动更新（1.3.0 起）**：达到「最多累积」后，新采集的图插到最前、**最老的被淘汰**（并同步从图库删除对应记录），
图池大小稳定在上限值但内容每轮都在换。拉图失败时保住已有数据，不会清空。

采样是串行 + 120ms 间隔，30 次约 8 秒，不会把免费图源打挂。
实测 `api.6045833.xyz` 采样 12 次约 3.4 秒能拿到 11–12 张不重复的图。

### 与「图库管理」插件对接（/console/photos）

站点装了官方的**图库管理**（plugin-photos）插件后，本插件会把图片**地址**写成图库的
`Photo` 记录（`core.halo.run/v1alpha1`），于是：

- 后台 **`/console/photos`** 能看到并管理这些图
- 主题前台 **`/photos`** 页面直接展示（Serenity 主题已适配该路由）
- **依然不占存储**：只写 `spec.url` 指向第三方 CDN，不创建 Attachment、不下载文件

| 后台设置项 | 默认 | 说明 |
|---|---|---|
| 同步到「图库管理」插件 | 开启 | 关闭后不再写入，可配合下面的接口撤回 |
| 图库分组名 | 留空 | 留空按数据源名自动分组；填写则全部归入一个分组 |

## 四、对外接口

### 公开只读组（可对访客放行）

基址：`/apis/api.gallery.halo.run/v1alpha1`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/items?source=&page=1&size=20` | 分页返回图片元数据 |
| GET | `/random?source=` | 随机一张（适合做站点头图/背景） |

`/items` 返回示例：

```json
{
  "items": [
    {"id":"img-1777380183712-g832cd","url":"https://img.static.paiii.cn/....webp",
     "title":"1","source":"派次元","tags":["acg","二次元"]}
  ],
  "total": 261, "page": 1, "size": 20, "totalPages": 14,
  "lastSync": "2026-10-25T05:30:00Z"
}
```

### 管理诊断组（需要后台权限，永不对匿名放行）

基址：`/apis/console.gallery.halo.run/v1alpha1`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sources` | 数据源列表及各源的张数、上次同步时间、错误 |
| GET | `/diagnose` | 逐个源实探一次，返回脱敏后的状态信息 |
| POST | `/sync` | 手动触发同步（会顺带写入图库） |
| POST | `/push-to-photos` | 手动把当前缓存推送到图库，返回 `{created, updated, failed}` |
| DELETE | `/photos` | 撤回本插件写入图库的记录，返回 `{removed}` |

> **⚠️ 路径变更**：`/sources`、`/diagnose`、`/sync`、`/push-to-photos`、`/photos`
> 在 1.3.1 及以前位于 `api.gallery.halo.run` 组，1.4.0 起迁到 `console.gallery.halo.run`。
> 如果你在自己的主题或脚本里调用过这些接口，升级后请改基址；
> `/items` 与 `/random` **路径未变**，前台展示不受影响。

### 访客访问

Halo 的 `/apis/**` 默认要求登录，未登录访问会 302 到 `/login?authentication_required`，
前台页面就取不到数据。插件启动时会自动做两件事（幂等，重复启用不会重复创建）：

1. 创建角色 `api-gallery-viewer`，只含一条规则：**公开只读组** `api.gallery.halo.run` 的 `get / list`
2. 在该角色上打**聚合标签** `rbac.authorization.halo.run/aggregate-to-anonymous`，
   规则会自动合并进 Halo 内置的匿名角色（这是 Halo 官方给访客授权的方式）
3. 兜底再建一条 `api-gallery-viewer-binding`，把角色绑给匿名用户

后台「同步设置」里的 **允许访客查看图库**（默认开启）可关掉，关闭后插件会删除上述绑定与角色，
接口只对登录用户开放。**管理诊断组无论如何都不会对匿名放行。**

自动放行失败时插件只记日志，不影响其它功能，可手工兜底：
后台 → **用户 → 角色 → 匿名角色** → 勾选 API 图库相关权限并保存。

## 五、前台展示页

插件**自带**一个自包含的展示页（瀑布流 + 懒加载 + 灯箱 + 按源筛选 + 随机图），
打包在 jar 里，启用插件即可直接访问：

```
https://<你的站点>/plugins/api-gallery/assets/static/gallery.html
```

> Halo 不会自动暴露插件的 `static/` 目录，本插件通过 `extensions/reverseProxy.yaml`
> 声明了静态资源代理，才有上面这个路径。**不要**直接访问 `/plugins/api-gallery/static/...`，会 404。

三种用法，任选：

**1. 直接用（推荐）**
浏览器打开上面的地址即可；也可在菜单里加一条自定义链接指向它。

**2. 放进主题目录**
```
unzip -p api-gallery-1.4.1.jar static/gallery.html > gallery.html
cp gallery.html <halo>/themes/theme-Serenity/templates/...
```
具体位置取决于主题，放好后通过主题路由访问。

**3. 嵌进任意页面**
把 `gallery.html` 里 `<script>` 之后的部分（或整个文件用 iframe）嵌入你的页面/文章：
```html
<iframe src="/plugins/api-gallery/assets/static/gallery.html"
        style="width:100%;height:900px;border:0"></iframe>
```

页面顶部的 `BASE` 常量指向公开只读接口，同域部署无需修改：
```js
var BASE = '/apis/api.gallery.halo.run/v1alpha1';
```

## 六、自己构建

```bash
cd halo-plugin-api-gallery
gradle build            # 产物 build/libs/api-gallery-1.4.1.jar
```

环境：编译用 JDK 17 字节码（`sourceCompatibility = 17`），但**跑单元测试需要 JDK 21**——
Halo 2.27 的 `api` 包是 Java 21 编译的（class 版本 65），用 17/20 的 JVM 跑测试会报
`UnsupportedClassVersionError`。`build.gradle` 里已用 toolchain 给 `test` 任务单独指定 21，
Gradle 会自动挑选本机已安装的 JDK 21。

依赖 `run.halo.app:api:2.27.1`（与站点 Halo 版本一致）；若你的 Halo 是其他版本，
改 `build.gradle` 里的 `halo { version }` 和依赖版本号即可。

`LICENSE`（MIT 全文）会随 jar 一起打包，位于 jar 根目录。

## 七、代码结构

```
src/main/java/io/kbmp/gallery/
├── ApiGalleryPlugin.java     入口：启动同步、定时任务、监听配置变更
├── GalleryService.java       读配置、HTTP 拉取、解析、内存缓存
├── GallerySourceConfig.java  数据源配置模型
├── GalleryImageItem.java     图片元数据模型（只有地址，不存文件）
├── GalleryEndpoint.java      公开只读接口（items / random）
├── GalleryAdminEndpoint.java 管理诊断接口（sources / diagnose / sync / push / photos）
├── GalleryAccessSetup.java   角色与匿名放行（只对公开组授权）
├── GalleryConfigListener.java 监听后台保存设置事件（PluginConfigUpdatedEvent）
├── ApiUrlSafety.java         SSRF 校验（1.4.0 新增）
└── PhotoBridgeService.java   桥接「图库管理」：写入/撤回 Photo 与分组（只写地址，不存文件）
src/main/resources/
├── plugin.yaml               插件描述（settingName / configMapName / license / repo）
├── static/gallery.html       前台展示页（1.4.0 起打进 jar）
└── extensions/
    ├── settings.yaml         后台设置表单（**必须在 extensions 目录下**）
    └── reverseProxy.yaml     静态资源代理（暴露 /plugins/api-gallery/assets/static/**）
```

数据流：

```
后台设置表单 → ConfigMap(api-gallery-config)
                    │  watch（保存即触发）
                    ▼
              GalleryService.sync()
                    │  ApiUrlSafety 校验
                    │  HTTP GET 各数据源
                    ▼
        内存缓存（图片元数据，进程内）
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
公开只读组 /items /random   PhotoBridgeService → Photo 记录（只写地址）
        │                       │
        ▼                       ▼
   前台展示页            /console/photos、/photos
```

## 八、测试

```bash
gradle test
```

49 个用例，全部通过：

| 类 | 用例数 | 覆盖内容 |
|---|---|---|
| `GalleryServiceTest` | 10 | 响应解析（顶层数组 / items 包裹 / 单对象随机图 / 未知 key 数组 / 非法载荷）、后台 repeater 配置解析、数据源可用性判断 |
| `GalleryServiceFetchTest` | 18 | 起本地 HTTP 服务模拟图源，端到端跑通「请求 → 解析 → 生成元数据」，含 500 错误与空数组 |
| `GalleryServiceRedirectSafetyTest` | 6 | 跳转安全：302 指向云元数据/私有网段/非 http 协议时拒绝跟随；相对与绝对 Location 正常跟随；重定向循环触发最大跳数保护 |
| `ApiUrlSafetyTest` | 9 | 公共 IP 放行；非 http 协议、非法格式、超长 URL、回环/localhost、私有网段（含 172.32 放行反例）、链路本地与云元数据端点、IPv6 唯一本地、组播/广播全部拒绝 |
| `PhotoBridgeServiceTest` | 6 | 写入图库的元数据是否正确、重复同步只更新不堆积、分组自动创建与覆盖、撤回时只删本插件写入的记录 |

其中 `GalleryServiceFetchTest` 与 `GalleryServiceRedirectSafetyTest` 全程只产生内存对象，不落任何本地文件。

## 九、注意事项

- **依赖第三方图源**：图源挂了前端就加载不出图，已同步过的元数据仍在（恢复后自动可用）
- **缓存是进程内的**：重启 Halo 后缓存清空，插件启动 3 秒后会重新同步，无需人工干预
- **不要滥用同步周期**：图源通常更新不快，60 分钟足够；设太短可能被图源限流
- **不要指向内网地址**：127.0.0.1、192.168.x.x 这类地址会被 SSRF 防护拒绝，这是刻意为之，
  图源必须是可从公网访问的地址
- **版权**：图片版权归各图源及其投稿者，商用前请自行确认授权
- **图源要支持跨域**：图片由访客浏览器直连图源 CDN，图源未开放 CORS 时前端会加载失败
  （派次元 `https://t.paiii.cn` 已开放）

## 十、常见问题

**Q：启动时报 `Setting name xxx was provided but setting extension was not found`**

设置定义没被 Halo 扫描到。Setting 的 YAML 必须放在 `src/main/resources/extensions/` 下
（打成包后是 `extensions/settings.yaml`），放在 resources 根目录无效。
另外确认三处名字一致：

| 位置 | 值 |
|---|---|
| `plugin.yaml` → `spec.settingName` | `api-gallery-settings` |
| `extensions/settings.yaml` → `metadata.name` | `api-gallery-settings` |
| `settings.yaml` → `apiVersion` / `kind` | `v1alpha1` / `Setting` |

**Q：后台保存了数据源，前端还是没图**

看管理组的 `/sources` 接口里该源的 `error` 字段，
常见原因是图源地址不通、返回非 JSON、或响应结构与所选解析方式不匹配（换「自动识别」试试）。
如果 error 里出现 `IllegalArgumentException: URL protocol must be http or https`
或 `points to a blocked/private address`，说明地址被 SSRF 防护拦了，换公网图源。

**Q：设置了自动同步，图片却一直没变（最常见的误解）**

先分清「没同步」和「同步了但内容一样」，看 `/sources` 接口即可：

| 字段 | 含义 |
| --- | --- |
| `intervalMinutes` | 当前**实际生效**的周期（改完保存是否生效，看这个） |
| `lastSync` / `nextSyncAt` | 上次 / 下次同步时间 |
| `secondsUntilNextSync` | 距下次同步还有多少秒，为 `-1` 说明没排上周期任务 |
| `errors` | 哪个源失败了、失败原因 |

两种典型情况：

1. **周期没生效**（1.2.0 已修）— 旧版本只在插件启动时读一次周期，后台把 60 改成 5 分钟后
   仍按 60 分钟跑。1.2.0 起保存设置会立刻按新周期重新排期，不用重启插件。
2. **同步了，但图本来就不会变** — 固定列表类图源（如 `https://t.paiii.cn/api/list`）
   每次返回的内容**完全一致**：实测连续两次拉取都是 261 条、内容指纹相同。
   这类源同步一万次也是同一批图，属于正常现象。

想要素材真的有变化，改用随机类型的源：

| 图源 | 类型 | 实测 |
| --- | --- | --- |
| `https://t.paiii.cn/api/random?format=json` | 随机单张 JSON | 采样 12 次 → 12 张不重复，2.9 秒 |
| `https://api.6045833.xyz/dongman` | 302 跳转 | 采样 12 次 → 12 张不重复，3.4 秒 |

另外派次元的列表其实**支持分页**（`?page=2` 返回 `{"items":[...]}`），
想扩充固定素材可以直接加几行：`/api/list`、`/api/list?page=2`、`/api/list?page=3`。

**Q：前台页面请求 `/items` 返回 302**

公开只读组未放行。确认「同步设置 → 允许访客查看图库」已开启并保存；
自动授权失败时到后台 → 用户 → 角色 → 匿名角色手动勾选权限。

**Q：升级到 1.4.0 后自己的脚本调 `/sources` 返回 403 或 404**

接口迁移了。`/sources`、`/diagnose`、`/sync`、`/push-to-photos`、`/photos`
的新基址是 `/apis/console.gallery.halo.run/v1alpha1`，且**需要后台登录态**（永不对匿名开放）。
`/items`、`/random` 仍在 `/apis/api.gallery.halo.run/v1alpha1`，未变。

## 十一、许可证

[MIT](LICENSE) © kbmp

本插件的源码仓库：<https://github.com/lu7875/halo-plugin-api-gallery>
问题反馈：<https://github.com/lu7875/halo-plugin-api-gallery/issues>

> **AI 使用声明**：本项目在开发过程中使用了 AI 辅助编码（AI-substantial）。
> 提交上架前已由开发者对权限边界、对外 HTTP 请求、诊断接口脱敏与上架材料做人工复查，
> 并在 Halo 2.27.1 实机安装验证。1.4.1 针对审核意见，人工复测了以下路径：
> 图源 302/307 跳转目标逐跳校验（内网/回环/云元数据/非 http 协议均拒绝、合法跳转与相对路径正常跟随）、
> 初始地址 SSRF 校验，以及随机源多轮定时同步的滚动更新与去重行为（见 `GalleryServiceRedirectSafetyTest`
> 与 `GalleryServiceFetchTest`）。
