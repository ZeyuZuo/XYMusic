# 独立服务

`KuGouMusicApi/` 从原工作区根目录原样移动至此，保留独立 `.git` 和全部文件。Android 通过 HTTP 调用它，不将 Node.js 依赖打包到 APK。

上游：https://github.com/MakcRe/KuGouMusicApi

基线提交：`590ff03b3b9aa766be6fd03bf4d09f9127482fc5`。

启动方式见根 README。运行与配置细节见上游 README 和 `docs/README.md`；优先复用服务，避免在 App 重写签名和加密逻辑。
