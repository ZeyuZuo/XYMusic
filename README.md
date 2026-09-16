# 相遇音乐 · XYMusic

面向 Android 的轻量酷狗第三方音乐播放器。使用 Kotlin、Jetpack Compose、Material 3 和 Media3，视觉参考 NeriPlayer。

## 版本管理

仓库与 Gradle 工程名称统一为 `XYMusic`，App 中文名为“相遇音乐”。远程仓库：`git@github.com:ZeyuZuo/XYMusic.git`，主分支为 `main`。本地目录和 Android 包名暂时沿用原名称，不影响仓库名称。

`.gitignore` 排除本机 SDK 配置、构建缓存、签名凭据、环境配置及两个上游独立仓库；Gradle Wrapper 随源码提交。从 GitHub 克隆后，如需启动 API 或参考 NeriPlayer，请按 `services/README.md`、`references/README.md` 的来源和基线提交另行获取上游仓库。

## 工作区

```text
android/                   新 App；Android Studio 打开此目录
docs/                      架构、复用来源与后续计划
services/KuGouMusicApi/     原 API 仓库，独立运行、保留 Git 历史
references/NeriPlayer/     原播放器仓库，仅作为参考、保留 Git 历史
AGENTS.md                  后续开发代理的工作约定
LICENSE                    GPL-3.0
```

两个上游仓库均已原样移动。它们不参与新 App 的构建，并由根 `.ignore` 和 `.gitignore` 隔离。显式查看上游代码用 `rg --no-ignore`。没有删除原始代码或仓库历史。

## 当前进度

已搭建：独立 Gradle 工程、浅色/深色主题、首页/搜索/音乐库/设置页面骨架、可持久保存的 API 地址、酷狗 HTTP 接口定义、内存 Cookie 会话容器、Media3 后台服务基础。

**尚未接通登录、在线搜索、实际播放、歌词和歌单。** 页面使用真实空状态；播放服务和网络层尚未连接到界面。当前交付是继续开发的起点，不是完整播放器。

2026-09-17：用户已在 Android Studio 编译运行工程。v0.1 已确定采用酷狗云端账号数据、Material 3 四栏导航及设置页登录；这些交互调整仍待实现。详细范围见 [v0.1 产品与实施计划](docs/V0.1.md)。

## 启动 Android 工程

1. 用 Android Studio 打开 `android/`。
2. 使用 JDK 17，安装 Android SDK Platform 35。支持 Android 9（API 28）及以上设备。
3. Android Studio 会生成 `local.properties`；命令行也可设置 `ANDROID_HOME`。
4. 同步依赖后运行 `app`。命令行验证：

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

首次构建需要联网下载 Gradle 和依赖。项目不沿用 NeriPlayer 的 native、子模块或多源构建配置。

### 初始化验证记录（2026-09-16）

- Gradle 8.11.1 下载及工程配置成功；XML、字符串资源引用和版本目录检查通过。
- API 地址校验与 Cookie 会话共 6 项测试，在临时独立 JVM 工程中使用本项目原始源文件运行，通过。该验证不依赖 Android SDK。
- 初始化时未找到 Android SDK，完整 `assembleDebug / testDebugUnitTest / lintDebug` 命令在 SDK 定位阶段停止。次日用户反馈已在 Android Studio 编译运行；完整测试、lint 和业务真机验收仍需后续记录。
- 已提供 `.github/workflows/android.yml`，后续上传 GitHub 可执行 Android 构建、单元测试与 lint；该工作流本次尚未运行。

## 启动 API

已有 Node.js 22 的环境可使用 Corepack 和上游锁文件安装：

```bash
cd services/KuGouMusicApi
corepack pnpm install --frozen-lockfile
HOST=127.0.0.1 corepack pnpm start
```

默认端口为 3000。需要环境配置时参考上游 `.env.example`；不要提交设备标识或登录凭据。

- Android 模拟器：App 设置使用 `http://10.0.2.2:3000/`。
- USB 真机：运行 `adb reverse tcp:3000 tcp:3000`，App 设置使用 `http://127.0.0.1:3000/`。
- 局域网真机：服务使用 `HOST=0.0.0.0`，App 填电脑局域网 IP；仅在可信网络调试。
- 发布构建：使用自建 HTTPS API 地址。API 是独立服务，APK 不包含 Node.js。

设置页目前只保存 API 地址，不代表服务器已经连通。后续先联调设备注册与登录，再连接搜索和播放。

## 继续开发

先按 [v0.1 产品与实施计划](docs/V0.1.md) 完成四栏导航、设置页账号入口与登录，再打通搜索播放。另见 [架构和里程碑](docs/ARCHITECTURE.md)、[第三方复用记录](docs/THIRD_PARTY.md) 和 [开发约定](AGENTS.md)。

新项目使用 GPL-3.0；复用的上游内容保留其许可，详见 `LICENSE` 和复用记录。
