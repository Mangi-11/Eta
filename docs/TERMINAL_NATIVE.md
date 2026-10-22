# 终端原生组件

Eta 将终端辅助程序作为独立 ELF 可执行文件打包在 `jniLibs` 中。文件使用 `.so` 后缀以便由 Android 安装器提取到只读的 `nativeLibraryDir`，不会通过 JNI 加载，也不从可写目录执行 Android 原生辅助程序。

| 程序 | ABI | 用途 |
| --- | --- | --- |
| `libeta_pty.so` | arm64-v8a | 为普通 Android Shell 和 Linux 会话创建真实伪终端 |
| `libproot_exec.so` | arm64-v8a | 在 App UID 下运行 Linux 用户空间 |
| `libproot_loader.so` | arm64-v8a | 与 PRoot 同一次编译产生的同 ABI 加载器 |

APK 仅支持 ARM64。`app/build.gradle.kts` 的 `ndk.abiFilters` 同时限制本地与依赖 AAR 的原生库，Debug 和 Release 均只打包 `arm64-v8a`。

PRoot 只支持本机对应的 64 位 Linux rootfs，不执行异构指令集，也不提供设备 Root 权限。PRoot 静态链接 talloc 和 libandroid-shmem，运行时只依赖 Android 系统库。构建的 ELF 使用 16 KiB 页对齐。

## 运行接口

PTY 参数为 `rows cols -- executable args...`，Root 与普通身份的控制台会话都由它建立独立 session。标准输出传输原始终端字节；标准输入按帧传输，帧头为 1 字节类型与 2 字节大端长度：类型 0 为终端字节，原样写入伪终端；类型 1 为窗口尺寸，载荷是大端 rows、cols 各 2 字节，取值 1 到 1000，写入后由内核向前台进程组发送 SIGWINCH；未知类型按长度跳过。Ctrl-C 作为终端字节经 PTY 发送到当前前台进程组；输入管道关闭时发送终端 EOF；关闭窗口、父进程退出或输出管道断开时清理终端进程组。退出码保留子进程状态。

PRoot 调用方必须设置 `PROOT_LOADER` 为当前 `nativeLibraryDir/libproot_loader.so`，设置 `PROOT_TMP_DIR` 为 Eta 可写的私有临时目录。缺失加载器不会转而寻找其他应用的安装路径。共享内存补丁也使用同一临时目录，不依赖全局 `/tmp`。

## 重建

构建需要与 `gradle/libs.versions.toml` 的 `ndk` 条目一致的 Android NDK、Python 3、GNU make、patch 与 tar，在 macOS 或 Linux 上运行：

```sh
ANDROID_NDK_HOME=/path/to/android-ndk scripts/build-terminal-native.sh
```

脚本先核对 NDK 的 `source.properties` 与版本目录，再使用 APK 同步附带的固定源码压缩包，并校验 SHA-256；缺少源码时才从上游固定版本地址下载。第三方源码版本、URL、摘要和编译参数以脚本为准。`ETA_NATIVE_SOURCES` 与 `ETA_NATIVE_BUILD` 可以分别指定源码缓存和临时构建目录。编译日志保存在构建目录中，不包含模型配置或用户文件。

脚本只生成 ARM64 ELF，并把实际脚本、版本目录、PTY 源码及补丁自动打入 `app/src/main/assets/native-sources/eta-native-build.tgz`，该包不应手工修改。APK 的 `assets/native-sources` 还包含 PRoot、talloc 和 libandroid-shmem 的原始源码；源码资源使用 `.tgz` 后缀，避免 Android 资源打包自动解压 `.gz` 文件后改变名称与校验值。`assets/licenses` 包含各自许可证。解压构建包恢复仓库相对路径，再把原始源码所在目录传给 `ETA_NATIVE_SOURCES`，即可重建随包组件。

源码包与重新生成的二进制需要一起更新。相关第三方程序和补丁适用各自开源许可证，Eta 主项目的非商业许可证不限制这些独立第三方程序授予的权利；详见[第三方声明](THIRD_PARTY_NOTICES.md)。
