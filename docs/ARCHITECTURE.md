# 架构

`HostApp` 是不可变描述与注册表，按精确包名/主进程名选择 `HostAdapter`。渲染引擎不判断应用身份，不把开放作用域当作通用兼容能力。新增宿主需要策略、注册项和静态作用域声明。

`HostAdapter` 使用策略模式隔离控件定位、标签身份、选中状态、布局挂载、窗口边距和原生图标识别。`WeChatAdapter` 适配原生全屏 Pager，`WeWorkAdapter` 适配 SwitchTab 自定义测量与 Fragment 页面。`BarMount` 提供明确的挂载目标与采样子树，禁止把玻璃自身录回背景。

`TabBarBridge` 将宿主逻辑索引映射为可见槽位。隐藏只改变可见性，不删除宿主数组成员、不改原生回调。

`LiquidGlassInstaller` 负责可回滚的移入操作、统一几何和生命周期观察。`LiquidGlassPanel` / `DropletPanel` 只负责材质与内容绘制。`DropletDragController` 负责连续弹簧和手势：取消回到原选中项，不触发点击；拖动完成才交回原生点击。

`GlassStyle` 是统一数值入口，`WeWorkTabPolicy` 是企业微信的明确标签过滤策略。只有业务相关差异留在宿主适配器，禁止在渲染与手势中追加包名 if/else。

设备材料在工作区 var，源码仓库不保存宿主安装包、截图、日志或账号内容。模块更新不自动重启手机或宿主；安装成功与实际注入、视觉验收分开记录。
