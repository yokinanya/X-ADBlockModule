# X-ADBlockModule

一个基于 [LSPosed](https://github.com/LSPosed/LSPosed) 的 X (Twitter) Android 模块，用于屏蔽 X 官方 App 中文区黄色广告与垃圾机器人帖子。**官方商业广告一概不碰**（约定原则）。

> ⚠️ 仅供学习与个人使用。本模块通过逆向分析 X 12.22.0 的渲染结构实现，随版本更新可能失效。

## 功能

- **垃圾帖屏蔽**：命中词库的帖子可整行移除（REMOVE 模式）或保留头像/用户名、正文替换为"已屏蔽"占位（MARK 模式）。
- **只在对话/详情页处理**：主页时间线完全不扫描（entryId 以 `conversationthread-` 开头才进入匹配），保证主页滚动零开销。
- **词库云端同步**：默认订阅 [x-comment-blocker](https://github.com/amahteru/x-comment-blocker) 公共词库（GitHub raw + jsdelivr CDN 兜底，ETag/304 增量同步），支持多订阅增删、本地 TXT 导入。
- **词库格式**：`#` 注释/分类头、`/regex/flags`、纯关键词（匹配时做零宽字符剥离、小写去空白）。
- **过滤选项**：用户名、emoji、特殊字符、Grok 匹配开关；显示模式（占位/移除）可切换。
- **屏蔽历史**：记录命中词条与原文，可在模块界面查看/清空。
- **诊断日志**：`/sdcard/Download/xadblock_module.log`（模块 App 侧），hook 侧日志经心跳广播捎带。

## 技术方案

- **Hook 目标**：X 官方 App `com.twitter.android`（12.22.0）。
- **渲染链路**（详见 [orchestra/reverse-findings-x-12.22.0.md](orchestra/reverse-findings-x-12.22.0.md)）：
  - 列表过滤入口：`com.x.urt.ui.o#c/d`、`com.x.urt.ui.h/j` 构造。
  - 行渲染：`com.x.jetfuel.v2.element.attribute.h#a(...)`（整行包含头像/用户名/正文）。
  - 内容渲染：`com.x.urt.items.post.i5#invoke`；帖子渲染状态 `com.x.urt.items.post.b5`（字段 `a`=entryId、`g`=正文、`i`=displayTextRange）。
- **跨进程通道**：Android 11+ 包可见性限制 + HMA 拦截导致 ContentProvider 不可用；模块用 **LSPosed「New XSharedPreferences」**（`MODE_WORLD_READABLE` prefs）写规则快照，X 进程用 `XSharedPreferences` 读；事件回传走 setPackage 广播（`ACTION_BLOCK_EVENTS` / `ACTION_HEARTBEAT`）。
- **性能**：关键词匹配用 Aho-Corasick 自动机（编译期构建，一次遍历出所有命中）；entryId 评估缓存避免重复匹配；规则快照变更由心跳线程每 120s 检查一次。
- **构建链**：AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.11.1 / compileSdk 36 / minSdk 26；Room 走 KSP。

## 构建

```bash
# 1. 准备签名（可选，缺失时 release 自动回退 debug 签名）
cp keystore.properties.example keystore.properties
#    填写你自己的 storeFile/storePassword/keyAlias/keyPassword

# 2. 构建 debug
./gradlew assembleDebug
# Windows: gradlew.bat assembleDebug --no-daemon

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

依赖说明：

- `app/libs/api-82.jar` 为 LSPosed API（`de.robv.android.xposed.*`），仅在编译期使用（`compileOnly`），运行时由 LSPosed 框架提供。来源：[LSPosed](https://github.com/LSPosed/LSPosed)（Apache-2.0）。
- `app/src/main/assets/builtin_keywords.txt` 内置词库派生自 [x-comment-blocker](https://github.com/amahteru/x-comment-blocker)（MIT）。

## 安装与使用

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed)（API 93+，需 Root）。
2. 编译并安装本模块 APK，在 LSPosed 中启用模块，作用域勾选 `com.twitter.android`，重启。
3. 打开模块 App：
   - 「词库订阅」：管理云端订阅源，立即同步。
   - 「过滤设置」：显示模式（占位/移除）、匹配选项开关、Grok。
   - 「屏蔽历史」：查看命中记录。
4. 点进任意推文查看回复效果。

## 免责声明

- 本模块仅作用于"垃圾机器人/黄色引流"类帖子，**不处理任何官方商业广告**。
- 屏蔽行为基于文本匹配，可能存在误伤；请谨慎使用。
- 本项目与 X Corp. 无关，不提供任何官方保证。

## 许可证

MIT，见 [LICENSE](LICENSE)。

内置词库与默认订阅源来自 [x-comment-blocker](https://github.com/amahteru/x-comment-blocker)（MIT License, Copyright (c) 2026 Ethan Zhou），版权归原作者所有。
