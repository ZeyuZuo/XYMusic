# 架构与开发顺序

产品范围、交互和验收以 [v0.1 开发基线](V0.1.md) 为准。界面遵循 Material 3，底部四栏，设置页承载登录。

## 过渡方案与最终目标

最终产品安装 APK 即可使用，不要求用户部署服务或配置 API 地址。开发阶段先通过 KuGouMusicApi 验证登录和音乐业务，后续按设备注册、登录与续期、搜索、播放的顺序，将必需的请求签名、加密和接口实现迁移到 Kotlin，直连酷狗官方服务。

现有 Compose 页面消费 AuthViewModel 的状态，HTTP 与 JSON 处理集中在数据层。当前 AuthRepository 仍依赖中转服务的 Cookie 协议；迁移时需替换这部分实现，并调整会话恢复和校验，不能仅替换 baseUrl。保持界面交互和领域行为稳定，不提前搭建多后端框架；迁移上游代码时按 THIRD_PARTY.md 记录许可与来源。

## 边界

```text
开发阶段：Compose 页面 → ViewModel → Repository → KuGouApi → KuGouMusicApi → 酷狗
最终目标：Compose 页面 → ViewModel → Repository → Kotlin 接口实现 → 酷狗
                    ↓
              MediaController → PlaybackService → ExoPlayer → 音频 CDN
```

当前登录链路已包含 AuthViewModel、AuthRepository、JSON 必填字段校验与 Keystore 加密会话存储；用户已反馈登录正常，恢复与续期仍需验收。SearchViewModel / SearchRepository 已接通三类搜索，复用当前账号客户端；基础单曲点播已接通 MediaController 与播放地址解析，服务独立观察会话变化。完整播放页已接入实际进度与拖动定位；本地队列、上下首及三种播放模式已实现，本地恢复和有限地址刷新已实现，歌词尚未实现。

`android/app/src/main/java/io/github/xiangyuplayer/`：

- `ui/`：Compose 页面与主题。
- `data/auth/`：登录仓库、响应校验与加密会话存储。
- `ui/auth/`：手机号登录、账号卡片及会话状态 ViewModel。
- `data/settings/`：DataStore 设置存储。
- `data/remote/`：API 地址校验、Retrofit 接口和按客户端隔离的内存 CookieJar。
- `domain/model/`：不依赖 HTTP JSON 的歌曲模型。
- `playback/`：MediaSessionService，是 ExoPlayer 的唯一持有者。

保持单模块，暂不加入 Hilt、Room、KSP、NDK 或 NeriPlayer 的构建插件；持久缓存、依赖注入等出现实际需求时再选择相应工具。

## 里程碑

1. **会话与接口验证**：调用 `/register/dev`，选择并实现登录方式，验证 token/userid/dfid；建立安全的持久会话。脱敏记录普通版实际响应，避免混用概念版 token。
2. **搜索与播放**：把 `/search` 响应映射为 Song；保留 hash 和专辑标识。验证 `/song/url` 的可播格式、试听标志、过期与失败；连接 MediaController，完成锁屏和后台播放。
3. **歌词与队列**：`/search/lyric` → `/lyric`；首版支持 LRC，KRC 后续增强；支持队列、跳转和进程重建恢复。
4. **首页推荐**：每日推荐列表、FM 自动续播与反馈，首页保持两张卡片。
5. **云端音乐库**：我喜欢、自建及收藏歌单以酷狗账号数据为准；实现云端读写和本地缓存，不创建独立本地歌单体系。持久缓存确有需要时引入 Room。
6. **详情与交付**：歌曲信息、评论/回复浏览、音质设置及 Material 3 体验验收。复杂动效后置。

设置已移至底部第四栏，并在设置页顶部建立账号入口。详细阶段完成条件见 v0.1 文档。

## 明确限制

- CookieJar 在内存中维护请求 Cookie，登录仓库加密持久化会话；切换 API 地址创建新客户端，退出清除持久及内存会话。不与音频 CDN 共享客户端。
- HTTP 接口返回 JsonObject 只是传输边界，HTTP 200 不代表业务成功。登录 Repository 已检查 status 和必填字段；其他业务仍需各自映射与错误处理。
- `/song/url/new` 文档提示加密音频问题，初期只接 `/song/url`，实际验证格式后再扩展。
- 发布版禁止 HTTP；调试版允许模拟器和局域网开发。API 地址存储不包含账号信息。
- Media3 服务已有媒体会话、控制器、音频焦点、耳机断开处理与播放 URL 解析；界面提供迷你播放器与完整播放页，已支持队列持久化及重启恢复为暂停。
- 播放数据层实现 `AudioSourceResolver`，服务层负责请求切换和播放器生命周期；通过非敏感的会话版本通知同步账号失效，不依赖 Compose 重组来停止播放。阶段范围及验收见 [播放计划](PLAYBACK_PLAN.md)。

## 验收

构建与静态检查：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。

接通播放后真机验证：锁屏控制、切后台连续播放、耳机拔出、电话音频焦点、网络中断、链接过期、退出登录、重启进程。网络层自动化测试不使用真实账号或真实令牌。

## 当前队列实现

PlaybackQueue 只保存歌曲元数据、当前项和遍历顺序，由 PlaybackService 在主线程串行操作；播放地址仍按当前歌曲独立解析，不加入队列或持久化。QueueSessionPlayer 使用 Media3 的 ForwardingSimpleBasePlayer 将标准上下首命令转交服务，其他播放与进度控制交给原 ExoPlayer。通知栏、耳机和页面因此共用同一套切歌规则。界面通过会话命令维护队列，通过会话状态展示队列，不自行修改播放器。队列恢复已在阶段 4 实现。

## 播放恢复与稳定性

PlaybackStateStore 在 IO 协程中串行、原子写入队列快照，PlaybackSnapshotCodec 校验归属、版本和字段结构。会话缓存标识保存在加密会话中，播放文件只保存该不含认证内容的随机标识；写入与退出清理共用 SessionStore 的锁。服务在会话与快照加载后恢复暂停状态，通过会话附加状态向界面提供待恢复进度。实际音频只在用户播放时请求，QueueSessionPlayer 同时处理标准播放/停止命令。

PlaybackRecovery 限制每次明确播放或手动重试最多刷新一次可能失效的地址；HTTP 401/403/404/410 才触发新解析，解析仍遵守账号权限。保存、解析与播放器生命周期分开管理；网络恢复不自动触发播放。
