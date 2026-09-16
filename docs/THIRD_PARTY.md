# 第三方来源与复用

本项目采用 GPL-3.0。第三方内容按其原许可证保留；源代码授权不等于品牌或图像素材授权。

## NeriPlayer

- 上游：https://github.com/cwuom/NeriPlayer
- 本地参考：`references/NeriPlayer/`
- 基线：`ac7bdea460b84a25e9b6feb1c66b2592360202f1`
- 项目许可证：GPL-3.0，原文保留在上游及根 `LICENSE`。
- 作者：NeriPlayer contributors。

| 原文件 | 本地文件 | 改动 |
| --- | --- | --- |
| `app/src/main/java/moe/ouom/neriplayer/ui/theme/SystemThemeState.kt` | `android/app/src/main/java/io/github/xiangyuplayer/ui/theme/SystemThemeState.kt` | 仅修改包名并补充来源说明，复用系统深色模式监听逻辑 |
| `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` | `android/` 下对应路径 | 原样复用 Gradle 启动工具；新建 wrapper 配置固定到 Gradle 8.11.1 |

Gradle Wrapper 属于 Gradle 项目工具；启动脚本内保留 Apache-2.0 声明，Gradle 发行版的 LICENSE/NOTICE 另存于 `docs/licenses/`。应用界面为新实现，不复制 NeriPlayer 图标、品牌素材或复杂的播放器实现。

## KuGouMusicApi

- 上游：https://github.com/MakcRe/KuGouMusicApi
- 本地服务：`services/KuGouMusicApi/`
- 基线：`590ff03b3b9aa766be6fd03bf4d09f9127482fc5`
- 许可证：MIT，完整许可证和版权声明保留在服务目录。
- 复用方式：独立运行原服务；Android 通过 HTTP 调用，没有把 Node.js 代码复制进 App。

## Android 依赖

AndroidX / Compose / Media3、Kotlin、OkHttp、Retrofit、Gson 等依赖由 Gradle 获取；版本集中于 `android/gradle/libs.versions.toml`。发布时应随构建生成、核对第三方依赖许可清单。
