# X 12.22.0 逆向结论（Hook 点定位）

> 对象：X 12.22.0 的 base APK（X_12.22.0-prod.01.apks 的 base 包，117MB，classes4.dex 起）
> 方法：jadx 1.5.5 反编译 `--no-res` + DEX 二进制字符串验证

## 核心结论

12.22.0 的 R8 混淆比 12.19.1 更彻底：`com.x.models.timelines.items.UrtTimelinePost`
这个原始类名**已不存在**（`Lcom/x/models/timelines/items/UrtTimelinePost;` 未在 dex 中出现，
只有 Kotlin metadata 字符串 `UrtTimelinePost`），真实 DEX 类名是：

| 逻辑名 | 12.22.0 DEX 真名 (classes4.dex) | 备注 |
|---|---|---|
| `UrtTimelinePost` | `com.x.models.timelines.items.l1` | implements `p0`(URT item) + `s5`(PostResult 视图) |
| `UrtTimelineUser` | `com.x.models.timelines.items.d2` | 带 `promotedMetadata` |
| URT item 接口 | `com.x.models.timelines.items.p0` | `c()`=entryId, `n()`=sortIndex, `t()`=clientEventInfo |
| `TimelinePromotedMetadata` | `com.x.models.ze` | toString 验证；字段 a=impressionId, h=advertiserName |
| 主 feed UI 入口 | `com.x.urt.ui.o` | **新增 hook 点（12.19.1 是 `com.x.urt.ui.n0`，12.22.0 已重排）** |
| 内部 lambda/remember 类 | `com.x.urt.ui.h` / `com.x.urt.ui.j` | 构造参数[0] 同为 immutable List |
| immutable 列表接口 | `kotlinx.collections.immutable.b` | 12.19.1 是 `.c`，12.22.0 重排为 `.b` |

## Hook 点：`com.x.urt.ui.o`（抽象类，全静态 Compose 方法）

### 主线方法 `o.c(...)`（静态，23 参，main feed）

```java
public static final void c(
    kotlinx.collections.immutable.b urtTimelineItems,   // arg[0] ← 过滤目标（List<item>）
    com.x.models.timelines.v timelineType,              // arg[1]
    boolean z,
    com.x.urt.paging.g bottomPagingState,
    com.x.urt.paging.g topPagingState,
    Function3, Function3, Function2 timelineItemPresenterMapper,
    com.x.media.playback.mediaprefetcher.g,
    Function1, Function1, Function3, Function1, Function2, Function1, Function1,
    y2 contentPadding, Modifier, com.x.performance.f, androidx.compose.foundation.lazy.j0,
    Composer, int, int)
```

- `o.c` 内部：`new h(urtTimelineItems, ...)` → `compose.runtime.internal.g.e(...)` 渲染 → 列表以
  `new j(urtTimelineItems, ...)` 作为 remember 值保存。
- 单条目渲染辅助：`o.e(int, p0 timelineItem, Function2 mapper, m0)`——item 以 `p0` 接口流转，
  运行时 instanceof 判断。

### 过滤方案（兼容最稳）

hook `o.c` 的 `beforeHookedMethod`：
1. `param.args[0]`（`kotlinx.collections.immutable.b`，即接口 List）→ 深度拷贝为 ArrayList；
2. 逐项检查：`item instanceof com.x.models.timelines.items.l1`（UrtTimelinePost）；
3. 对 l1 实例反射调用 `getText()`（public，委托 `postResult.getText()`，`h6`）与 `getUrl()`，
   连同样文本（text + url）做词库匹配；
4. 命中 → 从副本移除；不命中保留（非 l1 条目一律保留：user/trend/card 等不受影响）；
5. 用 `java.lang.reflect.Proxy` 实现 `kotlinx.collections.immutable.b`，把只读 List 方法委托给
   过滤后的 ArrayList，其余方法回退到原始对象 → `param.args[0] = proxy`。

兜底 hook：`com.x.urt.ui.h`（20 参）/ `com.x.urt.ui.j`（22 参）构造函数，参数[0] 同为列表，
采用同一过滤逻辑（两者互斥覆盖，防止 X 更新后入口方法签名变化）。

### 为什么不用 12.19.1 参考方案

- `com.x.urt.ui.n0`（12.19.1 构造 hook 目标）在 12.22.0 不存在；
- `UrtTimelinePost` 类名 hook 会直接 ClassNotFound；
- `PostResult#getText()` 是抽象接口方法，不可 hook（参考项目已踩坑确认）。

## 12.22.0 里 l1（UrtTimelinePost）关键结构

```java
public final class l1 implements p0, s5 {
    public final h6 a;        // postResult：text/getText(), url, id, user, timestamps...
    public final long b;      // sortIndex
    public final String c;    // entryId
    public final hc d;        // socialContext
    public final ze e;        // promotedMetadata（官方广告标记）
    public final a7 f;        // prerollMetadata
    public final com.x.models.u0 g; // clientEventInfo
    public final v5 h;        // displayType (Post)
    public final c4 i;        // hostingModuleMetadata
    public final java.util.List j; // feedbackKeys
    public final String k;    // feedbackMetadata
    public final a0 l;        // facepile

    public String c() { return this.c; }        // entryId
    public String getText() { return this.a.getText(); }  // 帖子全文
    public String getUrl() { return this.a.getUrl(); }    // 帖子外链（图片帖常用）
    public String s() { return this.a.s(); }              // 附加文本字段
}
```

## 产品约束挂钩

- **官方商业广告一律不碰**：`promotedMetadata (ze)` 判定虽然存在（`l1.e != null` 即官方推广），
  但按需求禁用，不做任何 promoted 过滤；
- 屏蔽目标为**中文区黄色广告/垃圾机器人**：按词库关键词命中 `text+url` 移除整条；
- 词库来源：x-comment-blocker 公共词库（默认订阅）+ 自定义 HTTPS/GitHub 订阅 + 本地词库。

## 参考项（12.19.1）已确认失效清单（供对照）

- `com.x.urt.ui.n0` 构造 hook — 不存在
- `com.x.models.timelines.items.UrtTimelinePost` — 不存在（→ `l1`）
- `kotlinx.collections.immutable.c` — 不存在（→ `.b`）

## 12.22.0 渲染跳过链真结构（2026-09-03 修正，dex classes4 直接核对）

旧结论"渲染 lambda = `i5#invoke(Object)`、`i5` 构造参数含 w4"**有误**：

| 类 | 真实形态 | 关键字段 |
|---|---|---|
| `com.x.urt.items.post.w4` | PostItemPresenter，implements `com.x.presenter.a` | `b` = l1（UrtTimelinePost），其余 ~90 个依赖字段 |
| `com.x.urt.items.post.b5` | 每帖渲染状态（约 70 参 data 类） | `a`=entryId(String)、`b`=postId(z5)、`c`=displayType、`g`=text；由 w4 内部 `new b5(...)` 构建 |
| `com.x.urt.items.post.i5` | `Function2` = Compose 行内容 lambda `invoke(Composer,Integer)`，**不含 w4** | `a` = b5（帖子渲染状态） |

- w4 构造参数**含 l1**（arg[1]，经 `y4.a(...)` 工厂），但旧版"i5 构造参数含 w4"不成立，
  i5 由列表组装层以 `b5` 为载荷创建 → W4_BLOCKED→I5_BLOCKED 关联永不建立（I5-BLOCK 永远不出现）。
- 正确的渲染级跳过：hook `i5.invoke(Composer,int)` 前读取 `i5.a`(b5) → `b5.a`(entryId) →
  命中 `BLOCKED_IDS` 即 `setResult(null)`（整行空组合，不影响数据层列表/官方广告）。
- 代价：彻底移除 w4/i5 构造期字段扫描（findFieldOfType），避免每个可见帖构造期的全字段反射开销。
- 参考：i5.java 完整反编译见项目外本地反编译产物（jadx --show-bad-code 对 classes4.dex 单 dex 反编译；原 jadx-out 漏掉了 i5 的类文件）。
  （jadx --show-bad-code 对 classes4.dex 单 dex 反编译；原 jadx-out 漏掉了 i5 的类文件）。
