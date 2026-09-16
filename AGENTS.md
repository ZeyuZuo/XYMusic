# 相遇音乐开发约定

## 项目定位与工作范围

- Android 原生酷狗第三方播放器，Kotlin + Jetpack Compose + Media3。追求 NeriPlayer 风格的简洁、留白、封面主导和顺畅播放体验。
- v0.1 功能和交互以 `docs/V0.1.md` 为开发基线。底部固定首页、搜索、音乐库、设置四个入口；登录位于设置页顶部，首页内容仅每日推荐和猜你喜欢两张卡片。
- 我喜欢、自建和收藏歌单使用酷狗账号云端数据；本地仅缓存，不另做一套本地歌单代替。真实账号的写操作由用户明确触发，测试不自动修改云端内容。
- 默认工作目录是 `android/`；说明文档位于 `docs/`。根目录仅放工作区配置、许可证和开发说明。
- `references/NeriPlayer/` 是上游参考仓库，`services/KuGouMusicApi/` 是独立的上游 API 服务仓库。两者保留自己的 Git 历史，不属于 Android Gradle 构建。
- 默认不要遍历、格式化、重构、提交或更新整个上游仓库。需要参考时精确读取对应文件。确需改 API 时在任务范围内修改，并单独报告其 Git 差异。
- `.ignore` 排除上游目录以减少搜索噪声。查看上游请显式使用 `rg --no-ignore ... references/NeriPlayer/...` 或对应服务路径。
- 根仓库已按用户要求初始化，主分支为 `main`，远程 `origin` 为 `git@github.com:ZeyuZuo/XYMusic.git`。通过正常 Git 命令维护版本，不手动删除或改写 `.git` 内部文件；不修改环境管理的 `.agents`、`.codex`。

## 开发方式

- 默认中文交流。先查看已有变更；保留用户修改。没有 Git 元数据时不要声称已检查根工作区 Git 差异。
- 当前是项目骨架，不是已完成的播放器。不要把占位页面、声明的 API 或未接通的播放服务称为可用功能。
- 优先完成设备会话/登录 → 搜索 → 获取播放地址 → 后台播放 → 歌词，再做推荐、歌单和动画。
- 小项目先使用单个 `app` 模块，按 `ui/`、`data/`、`domain/`、`playback/` 分包；确有需要才拆 Gradle 模块。
- UI 不直接发 HTTP 请求、不直接持有 ExoPlayer。API 响应经数据层转换为领域模型；界面通过 ViewModel/StateFlow 消费状态。
- 使用结构化协程；不能吞掉 CancellationException。网络、数据库、歌词解码不在主线程执行。
- 字符串放资源文件，支持深色模式、系统字体缩放、触控面积与读屏描述。空数据、加载、错误必须有真实可理解的状态。
- 页面遵循 Google Material 3：优先标准组件，统一主题、字阶、间距和形状，触控区域至少 48dp；不要堆叠装饰效果。参考 NeriPlayer 的体验不等于逐像素照搬。
- 保持代码简洁且职责清楚；避免万能管理类、无意义的转发层和预先搭建复杂框架。云端业务状态与播放状态分别管理。
- 当前没有已联调的在线数据，不在主应用塞入假歌曲、假账号、假播放状态。演示数据只用于 Preview 或测试。

## 酷狗 API 与播放边界

- 最终产品目标是安装 APK 即可使用：Android 客户端直接访问酷狗官方服务，普通用户无需部署服务或填写 API 地址。
- 开发阶段暂用 `services/KuGouMusicApi/` 的 HTTP 服务联调；后续逐步将所需签名、加密、设备和业务请求移植为自有 Kotlin 实现。上游服务是过渡依赖，不是最终用户的使用前提。不把 Node.js 嵌入 APK，也不照搬网易云的账号/歌曲 ID 逻辑。
- 过渡阶段 API 地址是开发联调配置，界面和文档须明确标注；最终普通用户流程不包含服务地址配置。开发构建允许 HTTP 调试；发布构建只允许 HTTPS。
- 保留歌曲 hash、album_id、album_audio_id、时长和来源信息，统一客户端时长单位为毫秒。
- 设备注册、登录、搜索实际字段与权限以接口联调为准；普通版和概念版 token 不混用。
- 当前 Kotlin API 接口返回原始 JSON，属于传输边界。没有真实响应样本前不要编造 DTO 或把空结果当作成功。
- 认证信息不能写入代码、URL、日志、截图、测试样本或版本库。后续登录使用独立的会话存储；账号切换/退出应清理旧会话及相关缓存。
- 不向音频 CDN 转发 API Cookie/Authorization。播放 URL 可能过期，应在播放时解析并按需要刷新。
- 正确区分完整播放、试听、权限不足、接口错误。不把失败伪装成成功，不实现权限绕过。
- ExoPlayer 归 MediaSessionService 所有；UI 通过 MediaController 操作。处理音频焦点、耳机断开、通知栏和服务生命周期。

## 复用与许可

- 项目按 GPL-3.0 发布；完整许可证见根目录 `LICENSE`。复用 NeriPlayer 代码须保留原版权声明。
- 复制或改写上游代码时，在 `docs/THIRD_PARTY.md` 记录来源仓库、提交、原路径、本地路径、许可证和改动。
- 优先复用边界明确的工具或组件；不要为一个 UI 组件引入整套多平台、USB、同步或脚本运行时。
- 第三方源码和品牌素材的许可分别核查，不默认复用 NeriPlayer 名称、图标或酷狗商标。

## 验证与交付

- Android：JDK 17、Android SDK Platform 35；在 `android/` 执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。
- 首次运行需要下载 Gradle 和 Maven 依赖。SDK 路径通过 ANDROID_HOME 或未提交的 `android/local.properties` 提供。
- API 开发：`cd services/KuGouMusicApi && corepack pnpm install --frozen-lockfile && HOST=127.0.0.1 corepack pnpm start`。不要无故重写上游锁文件。
- 有行为变化才增加有价值的测试；网络适配用脱敏的真实响应或 MockWebServer 测试，禁止依赖真实账号的自动化测试。
- 未安装 SDK、网络下载失败、未接设备等限制必须如实报告；静态检查通过不等于 APK 构建通过。
- 交付说明改动、验证结果和剩余工作。更新 README 的完成状态，避免文档超前于代码。
