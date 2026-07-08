# 更新日志 (Changelog)

> 地震预警系统 — 完整版本更新记录

---

## v5.7.0 (2026-07-08)

### ✨ 功能新增

- **数据源选择器**：控制台新增数据源下拉选择功能，支持两种数据源实时切换
  - **中国地震台网发布最新地震信息**：`wss://ws-api.wolfx.jp/cenc_eqlist` / `https://api.wolfx.jp/cenc_eqlist.json`（旧 NoX 信封格式）
  - **中国地震台网实时地震预警**：`wss://ws-api.wolfx.jp/cenc_eew` / `https://api.wolfx.jp/cenc_eew.json`（新扁平单对象格式）
- **数据源切换自动重连**：切换数据源后自动断开旧连接、清空去重表、重连新 WebSocket
- **新数据源格式兼容**：新增扁平单对象 JSON 解析路径 `parseSingleEarthquakeRecord()`，支持 `OriginTime/HypoCenter/Magnitude` 等新字段名

### 🔧 改进

- `RuntimeConfigService`：新增 `dataSource` 字段 + 预存数据源 URL 映射（`availableDataSources`），切换时自动联动 WS/REST URL
- `EarthquakeController`：PUT/GET `/config` 支持 `dataSource` 配置项 + 单个 key 读写
- `WebSocketClientService`：新增 `reconnect()` 方法（断开→清空去重表/记录缓存→重连），替代原 `switchPrimaryChannel()` 的局部行为
- 控制台 UI：连接配置卡片顶部新增数据源选择下拉框，蓝色提示框显示当前数据源名称，切换时自动填充 URL 并保存

---

## v5.6.1 (2026-07-08)

### 🐛 Bug 修复

- **推送通知无法进入详情页**：修复 `earthquake-detail.html` 中 `getDotClass` 函数声明意外缺失，导致 `'use strict'` 下 SyntaxError，整段 JS 解析失败，详情页始终显示静态"请从地震预警通知中点击查看详情"
- **历史地震重复推送**：修复 `processedEvents` 去重 Map TTL 仅 1 小时，Wolfx API 持续返回旧数据导致 1 小时后旧事件被重新推送。新增 `maxWarningAgeMinutes` 时效性检查（默认 10 分钟），超时地震直接跳过推送；`processedEvents` TTL 扩展至 48 小时

### 🔧 优化改进

- `RuntimeConfigService` 新增 `maxWarningAgeMinutes` 配置项，支持运行时动态修改
- 推送逻辑审查确认：WSS/HTTP 实时延迟 < 1s，Swagger 测试接口仅推送最新一条且完全隔离

---

## v5.6.0 (2026-07-07)

### 🐛 Bug 修复

- **语音倒计时无声音**：修复浏览器自动播放策略导致 AudioContext/SpeechSynthesis 被拦截的问题，增加全屏遮罩一键解锁机制
- **事件双重触发**：移除按钮 HTML 模板中的 `onclick` 内联属性，解决与 `addEventListener` 冲突导致音频刚解锁即被重新静音的 bug
- **倒计时慢一秒**：修复 `setInterval` 首次执行延迟 1 秒的问题，改为先立即调用 `countdownTick()` 再启动定时器
- **全屏遮罩不自动关闭**：修复倒计时到 0 时遮罩仍显示的问题，增加自动关闭逻辑
- **模拟地震 API cityResults 未返回**：修复 `WarningResult` 中遗漏逐城详细计算结果的问题，新增 `extra` 扩展字段
- **HTTP 轮询模式状态显示错误**：修复控制面板在 HTTP 轮询模式仍显示红色"未连接"的问题
- **重复事件监听器**：为 `startVoiceCountdown()` 中的按钮绑定增加 `_bound` 防重复标记

### ✨ 功能新增

- **语音倒计时系统**：基于 Web Audio API + SpeechSynthesis API 实现三模式语音倒计时（纯数字/单滴/双滴）
- **全屏遮罩解锁**：移动端友好的声音解锁方式，轻触屏幕任意位置即可启用
- **视觉倒计时横幅**：大字倒计时数字 + 颜色分级显示（正常→橙色→红色脉冲→已到达）
- **暂停/静音控件**：用户可以暂停倒计时或切换静音

### 🔧 优化改进

- 移除 `window.togglePauseCD` / `window.toggleMuteCD` 全局变量（不再需要 onclick 兼容）
- 增加 `[CD]` 前缀的全链路调试日志
- 版本号统一更新到 5.6.0（pom.xml / Swagger / 前端）

---

## v5.0.0 (2026-07-06)

### ✨ 功能新增

- **多城市监测与多 Bark 设备**：支持无限个城市同时监测，每个城市独立配置场地类别和放大系数
- **全量运行时配置管理**：所有配置项均可通过 Web 页面实时修改，无需重启
- **运行时配置持久化**：修改后自动保存到 `config/runtime-config.json`，启动时自动加载
- **HTTP 轮询兜底**：WebSocket 断线自动切换到 HTTP 轮询模式，保证数据连续性
- **SOCKS5 代理支持**：wolfx.jp 走代理、Bark 推送直连的分流设计
- **主通道切换**：支持 wss 和 http 两种主通道模式，一键切换
- **Web 控制台 v5.0**：完整的配置管理、城市/设备 CRUD、模拟测试、实时日志界面

### 🧮 计算模型升级

- **IASPEI-91 走时计算**：用射线参数二分搜索法替代原先的定速直线传播，预计算查表加速
- **GB 18306-2015 烈度衰减模型**：采用中国东部地区的烈度衰减公式
- **场地放大系数**：支持 5 类场地类别 (I₀~IV) 和自定义放大系数

### 🔧 架构升级

- **多城市计算**：`EarthquakeCalculateService.calculate(record, city)` 支持逐城市计算
- **异步推送**：Bark 推送改为线程池异步执行，不阻塞 WebSocket 消息处理
- **线程安全**：`ConcurrentHashMap`、`CopyOnWriteArrayList`、`AtomicBoolean` 全面保证并发安全
- **代理分流**：`WebSocketClientService` 条件代理模式，wolfx.jp 走 SOCKS5，其他直连

---

## v4.0.0 (2026-06)

### ✨ 功能新增

- **Bark 推送通知**：对接 Bark API 实现地震预警推送
- **多设备支持**：支持多台 iOS/Android 设备同时接收预警
- **通知分级策略**：按地震影响等级自动调整 push level/sound/volume/call
- **详情页跳转**：Bark 通知点击后跳转到地震详情页（Base64 编码传递数据）
- **地震详情页**：展示震级、深度、各城市 P/S 波倒计时、防护建议
- **模拟地震测试**：支持通过 API 和 Web 页面模拟地震测试，可选是否发送 Bark 推送

### 🧮 计算模型

- **震中距离**：Haversine 大圆公式
- **烈度衰减**：基础烈度衰减公式
- **P/S 波走时**：定速直线传播模型
- **影响等级**：0-5 六级影响等级判定

---

## v3.0.0 (2026)

### ✨ 功能新增

- **WebSocket 实时数据接收**：对接 Wolfx CENC 地震数据 WebSocket
- **自动重连**：断线自动重连机制
- **保活 Ping**：20s 间隔发送 ping 保持连接
- **原始消息缓冲区**：环形缓冲区存储最近 300 条消息
- **REST API 基础框架**：连接管理、数据查询、状态获取

---

## v2.0.0 (2026)

### ✨ 功能新增

- Spring Boot 项目初始化
- 基础项目结构搭建
- Swagger API 文档集成

---

## v1.0.0 (2026)

### ✨ 功能新增

- 项目原型：地震数据获取与基础计算
- 单城市监测模式
