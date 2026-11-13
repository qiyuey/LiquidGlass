# LiquidGlass

为 Android 应用提供一致的液态玻璃浮动导航。包名 `top.qiyuey.liquidglass`，运行于支持 libxposed API 102 的 LSPosed。每个宿主通过独立策略接入，共用采样、玻璃材质、液滴和手势引擎。

## 当前适配

| 应用 | 作用域 | 状态 |
| --- | --- | --- |
| 微信 | `com.tencent.mm` | 延续上游 LauncherUI 接入；本分支改动后仍需回归 |
| 企业微信 | `com.tencent.wework` | 5.0.13 (84802)；用户已确认 0.3.2 的底栏效果可用 |

企业微信固定隐藏 Channel 和 Doc 对应标签，按名称匹配，不依赖按钮位置；其他入口保留原数组索引、原生点击、角标和未读数更新。没有禁用文档或频道业务，深层入口仍由企业微信管理。

## 安装与测试

企业微信 5.0.13 启用模块后自动隐藏原生页面绘制的可见文字水印。

安装本项目 APK，在 LSPosed 中启用 LiquidGlass，勾选所需应用。由用户手动结束并重新打开宿主，让新模块进入进程；不需要重启手机。禁用模块并重新打开宿主可恢复原生底栏。

从旧 `io.github.qiyuey.wechatliquidglass` 测试包迁移时启用新包名，避免两个模块同时作用于同一宿主。模块在本机运行。

## 构建

版本统一维护于 [toolchain.env](toolchain.env)：JDK 27、Java 27 字节码、Android API 37.2 / target 37、Build Tools 37.0.0、R8 9.5.32、libxposed 102.0.0。R8 单独固定版本，以支持 Java 27。Google 官方源优先，必要时使用阿里云 Maven 镜像，并校验 R8 SHA-256。

2026-11-13已重新查询Google官方SDK仓库及Maven元数据，以上SDK、Build Tools和R8为当时最新正式版；libxposed由Maven Central元数据核对。预览/alpha版本不混入正式工具链。

Windows（已安装 Android SDK 与 JDK 27）：

```powershell
./test.ps1 -JavaHome $env:JAVA_HOME
./build.ps1 -JavaHome $env:JAVA_HOME -SdkRoot "$env:LOCALAPPDATA/Android/Sdk"
```

Linux（JDK 27 在 PATH，设置 ANDROID_SDK_ROOT）：

```sh
./setup-tools.sh
./build.sh
```

输出 `LiquidGlass-v版本.apk`。默认生成并复用本机 debug 签名；正式签名通过 KEYSTORE / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS 环境变量提供。密钥、工具、构建和安装包均不提交。构建只创建新输出目录，不自动删除旧目录。

## 设计与扩展

统一规范见 [DESIGN.md](docs/DESIGN.md)，扩展点见 [ARCHITECTURE.md](docs/ARCHITECTURE.md)。效果参考 Apple 官方材质原则及本工作区现有 Compose/Miuix 实现；64dp 等具体数值是项目参数，不是苹果规定。Android 13 以上使用 AGSL，Android 12 保留 GPU 模糊，旧版本保留兼容导航。

日志 tag：`LiquidGlass`。JVM 测试验证几何、过滤和弹簧状态；编译与签名校验不等于全部宿主版本、主题或设备均已通过视觉验收。

## 来源与许可

基于 [liuran001/WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass)（MIT）演进。保留原始 [LICENSE](LICENSE)。光学与交互参考 [KernelSU](https://github.com/tiann/KernelSU)、[Miuix](https://github.com/compose-miuix-ui/miuix) 和 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)。不随仓库分发宿主 APK、账户数据或设备截图。
