# 架构与开发顺序

产品范围、交互和验收以 [v0.1 开发基线](V0.1.md) 为准。界面遵循 Material 3，底部四栏，设置页承载登录。

## 边界

```text
Compose 页面 → ViewModel → Repository → KuGouApi → 独立 Node.js API
                    ↓
              MediaController → PlaybackService → ExoPlayer → 音频 CDN
```

当前只有页面骨架、设置存储、HTTP 传输接口、歌曲领域模型和播放服务。ViewModel、Repository、MediaController 与实际响应映射留待首个功能链路实现，不用无效实现冒充可用功能。

`android/app/src/main/java/io/github/xiangyuplayer/`：

- `ui/`：Compose 页面与主题。
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

业务开发前先将设置移至底部第四栏，并在设置页顶部建立账号入口。详细阶段完成条件见 v0.1 文档。

## 明确限制

- 当前的 CookieJar 只保存在内存，不能代替持久登录；切换 API 地址必须创建新的客户端，退出登录必须清空会话。不要把 API 客户端用于音频 CDN。
- HTTP 接口返回 JsonObject 只是传输边界，HTTP 200 不代表业务成功。未来 Repository 需要解析 status/error_code、检查必填字段、区分认证失败和空结果。
- `/song/url/new` 文档提示加密音频问题，初期只接 `/song/url`，实际验证格式后再扩展。
- 发布版禁止 HTTP；调试版允许模拟器和局域网开发。API 地址存储不包含账号信息。
- Media3 服务已有媒体会话、音频焦点与耳机断开处理；还没有界面控制器、播放 URL 解析、队列持久化或自动恢复。

## 验收

构建与静态检查：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。

接通播放后真机验证：锁屏控制、切后台连续播放、耳机拔出、电话音频焦点、网络中断、链接过期、退出登录、重启进程。网络层自动化测试不使用真实账号或真实令牌。
