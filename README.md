# 🌍 地震预警系统 Earthquake Warning System

> 实时对接 Wolfx CENC 中国地震台网数据，多城市影响计算与 Bark 推送通知系统

[![Version](https://img.shields.io/badge/version-5.7.0-blue.svg)](https://github.com)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

---

## 📋 项目概述

地震预警系统是一个基于 Spring Boot 3.3.5 的实时地震监测与预警推送平台。系统通过 **WebSocket** 实时接收 Wolfx CENC 中国地震台网的地震数据，结合 **IASPEI-91 一维地球速度模型**计算 P 波/S 波走时，基于 **GB 18306-2015 中国东部烈度衰减模型**估算各地影响烈度，并通过 **Bark** 将预警信息推送到 iOS/Android 设备。

### 核心工作流程

```
Wolfx CENC WebSocket → 实时地震数据
        ↓
   [走时计算] IASPEI-91 射线追踪 → P波/S波到达时间
   [烈度计算] GB 18306-2015 衰减模型 → 基岩烈度
   [场地修正] 场地放大系数 → 本地影响烈度
        ↓
   [影响分级] 0 无感 → 5 毁灭性
        ↓
   [推送决策] 震级≥阈值 && 影响等级≥阈值
        ↓
   [Bark 推送] → 📱 iOS/Android 实时通知
   [Web 详情页] → 语音倒计时 + 波到达可视化
```

---

## ✨ 功能特性

| 模块 | 功能 |
|------|------|
| 📡 **数据获取** | WebSocket 实时接收 + HTTP 轮询兜底，支持 SOCKS5 代理 |
| 🧮 **地震计算** | IASPEI-91 P/S 波走时、GB 18306-2015 烈度衰减、场地放大修正 |
| 📱 **Bark 推送** | 多设备异步推送，按影响等级智能调整音量和通知级别 |
| 📍 **多城市监测** | 支持无限个城市同时监测，每个城市独立配置场地类别 |
| 🔊 **语音倒计时** | Web Audio API + SpeechSynthesis，三种强度模式自动适配 |
| 📊 **Web 控制台** | 完整的 Web 管理界面，全量配置实时可调 |
| 🌐 **REST API** | 完整的 REST API + Swagger UI 文档 |
| 🔄 **HTTP 轮询兜底** | WebSocket 断线自动切换 HTTP 轮询，保证数据连续性 |
| 🛡️ **代理支持** | wolfx.jp 走 SOCKS5 代理，Bark 推送直连不受影响 |

---

## 🏗️ 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时环境 |
| Spring Boot | 3.3.5 | 应用框架 |
| Java-WebSocket | 1.5.7 | WebSocket 客户端 |
| OkHttp | 4.12.0 | HTTP 代理客户端 (SOCKS5) |
| Jackson | 2.x | JSON 处理 |
| SpringDoc OpenAPI | 2.6.0 | Swagger UI |
| Lombok | 1.x | 代码简化 |
| HTML/CSS/JS | - | 原生前端 (无框架依赖) |

---

## ⚠️ 部署前必读：脱敏内容配置

本项目代码已脱敏，以下 **4 项配置** 在部署前**必须**按你的环境修改，否则功能无法正常工作。

### 1. Bark Device Key（必须修改）

**文件**：`src/main/java/com/earthquake/warning/service/RuntimeConfigService.java`

```java
// 第 161~164 行，默认设备已被设为占位值：
barkDevices.add(BarkDevice.builder()
        .id(shortId())
        .name("我的iPhone")
        .deviceKey("YOUR_BARK_DEVICE_KEY")  // ← 改为你的 Bark Device Key
        .enabled(false)                     // ← 改为 true
        .build());
```

> 💡 **如何获取**：在 iOS/Android 安装 [Bark](https://github.com/Finb/Bark) App，打开即可看到 Device Key。  
> 也可以在 Web 控制台 (`/`) 或 API 中动态添加设备，无需硬编码。

### 2. 详情页跳转地址（必须修改）

**文件**：`src/main/resources/application.yml`

```yaml
earthquake:
  # 手机收到的 Bark 通知点击后跳转到此地址，必须填手机能访问的地址！
  # 内网测试填电脑局域网 IP（如 http://192.168.1.100:5050），不要用 localhost
  # 留空则降级为 CENC 中国地震台网速报链接
  detail-page-base-url:    # ← 填你的服务器地址，如 http://your-server:5050
```

### 3. SOCKS5 代理（按需启用）

**文件**：`src/main/resources/application.yml`

```yaml
earthquake:
  proxy:
    enabled: false        # ← 若 wolfx.jp 被你的服务器 IP 段屏蔽，改为 true
    host: 127.0.0.1       # ← 代理地址（如 mihomo / Clash Meta）
    port: 1080            # ← 代理端口
```

### 4. 推送图标（可选）

**文件**：`src/main/java/com/earthquake/warning/service/BarkNotificationService.java`

```java
// 第 146 行，当前使用公共图标：
payload.put("icon", "https://img.icons8.com/emoji/96/earth-globe.png");
// ← 可替换为你自己的图标 URL
```

---

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- (可选) mihomo/Clash Meta — 如果服务器在阿里云等 IP 段被 wolfx.jp 屏蔽

### 本地运行

```bash
# 1. 克隆项目
git clone <repo-url>
cd earthquake-warning

# 2. 修改配置文件
vim src/main/resources/application.yml
# 按需配置代理、Bark设备、监测城市等

# 3. 编译运行
./mvnw spring-boot:run

# 4. 访问
# 控制台: http://localhost:5050
# Swagger: http://localhost:5050/swagger-ui.html
```

### 生产部署

```bash
# 编译 JAR
./mvnw package -DskipTests

# 上传到服务器
scp target/earthquake-warning-5.6.1.jar root@server:/opt/earthquake-warning/

# 启动
nohup java -jar /opt/earthquake-warning/earthquake-warning-5.6.1.jar > /dev/null 2>&1 &
```

服务器默认监听端口 `5050`，可在 `application.yml` 中修改 `server.port`。

---

## ⚙️ 配置说明

### 核心配置项 (`application.yml`)

```yaml
earthquake:
  websocket-url: wss://ws-api.wolfx.jp/cenc_eqlist   # WebSocket 数据源
  rest-api-url: https://api.wolfx.jp/cenc_eqlist.json # HTTP 兜底数据源
  polling-interval-ms: 1000     # HTTP 轮询间隔
  reconnect-interval: 3         # 重连间隔 (秒)
  primary-channel: wss          # 主通道: wss 或 http

  bark:
    api-url: https://api.day.app
    sound: alarm
    level: critical             # 静音模式也响铃
    volume: 10
    call: 1                     # 重复响铃次数

  warning:
    min-magnitude: 3.0          # 最低预警震级
    min-impact-level: 1         # 最低预警影响等级

  proxy:
    enabled: true               # 启用 SOCKS5 代理
    host: 127.0.0.1
    port: 1080
```

### 运行时配置管理

所有配置项均可在 Web 控制台 (`/`) 中实时修改，无需重启。修改后自动持久化到 `config/runtime-config.json`。

---

## 📡 API 文档

启动后访问 `http://localhost:5050/swagger-ui.html` 查看完整 API 文档。

### 主要接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/earthquake/status` | 系统状态 |
| GET | `/api/earthquake/latest` | 最新地震列表 |
| GET | `/api/earthquake/warnings` | 预警列表 |
| POST | `/api/earthquake/simulate` | 模拟地震测试 |
| POST | `/api/earthquake/test-push` | 真数据推送测试 |
| GET | `/api/earthquake/config` | 全量配置 |
| PUT | `/api/earthquake/config` | 批量修改配置 |
| GET/POST | `/api/earthquake/cities` | 监测城市 CRUD |
| GET/POST | `/api/earthquake/bark-devices` | Bark 设备 CRUD |

---

## 📂 项目结构

```
earthquake-warning/
├── pom.xml
├── src/main/java/com/earthquake/warning/
│   ├── EarthquakeWarningApplication.java    # 启动类
│   ├── config/
│   │   └── OpenApiConfig.java               # Swagger 配置
│   ├── controller/
│   │   └── EarthquakeController.java        # REST API 控制器
│   ├── model/
│   │   ├── BarkDevice.java                  # Bark 推送设备
│   │   ├── EarthquakeRecord.java            # 地震记录 (含计算字段)
│   │   ├── MonitoredCity.java               # 监测城市
│   │   ├── SimulateRequest.java             # 模拟地震请求
│   │   └── WarningResult.java               # 预警结果
│   └── service/
│       ├── BarkNotificationService.java     # Bark 推送 (异步多设备)
│       ├── EarthquakeCalculateService.java  # 影响计算 (衰减模型)
│       ├── RuntimeConfigService.java        # 运行时配置管理
│       ├── SiteAmplificationService.java    # 场地放大系数
│       ├── TravelTimeService.java           # IASPEI-91 走时计算
│       └── WebSocketClientService.java      # WebSocket 客户端
└── src/main/resources/
    ├── application.yml                      # 应用配置
    └── static/
        ├── index.html                       # 控制台 Web 页面
        └── earthquake-detail.html           # 地震详情页面
```

---

## 🔬 计算模型

### 地震波走时 — IASPEI-91

采用 IASP91 (Kennett & Engdahl, 1991) 标准地球参考模型，将地壳/上地幔上 210km 划分为 5 个恒速层，通过**射线参数二分搜索法**计算 P 波与 S 波走时。

| 层次 | 深度范围 | P波速度 | S波速度 |
|------|----------|---------|---------|
| 第1层 | 0-3 km | 5.40 km/s | 3.12 km/s |
| 第2层 | 3-20 km | 5.80 km/s | 3.36 km/s |
| 第3层 | 20-35 km | 6.50 km/s | 3.75 km/s |
| 第4层 | 35-80 km | 8.04 km/s | 4.47 km/s |
| 第5层 | 80-210 km | 8.05 km/s | 4.49 km/s |

### 烈度衰减 — GB 18306-2015

采用中国东部地区的烈度衰减模型：

```
I = 4.178 + 1.524 × M - 2.386 × ln(R + 15)
```

其中：M = 震级，R = 震中距 (km)

### 影响等级分级

| 等级 | 烈度范围 | 描述 |
|------|----------|------|
| 0 | < 3.0 | 无感 |
| 1 | 3.0 - 4.0 | 轻微有感 |
| 2 | 4.0 - 5.0 | 明显有感 |
| 3 | 5.0 - 6.0 | 强烈有感 |
| 4 | 6.0 - 7.0 | 破坏性 |
| 5 | ≥ 7.0 | 毁灭性 |

---

## 🎯 语音倒计时

地震详情页内置语音倒计时系统，根据影响等级自动选择播报模式：

| 影响等级 | 播报模式 | 示例 |
|----------|----------|------|
| 0-1 (无感) | 纯数字语音 | "10 9 8 7..." |
| 2-3 (有感) | 数字 + 单声"滴" | "10 滴 9 滴 8 滴..." |
| 4-5 (破坏性) | 数字 + 双声"滴滴" | "10 滴滴 9 滴滴 8滴滴..." |

触发方式：页面加载后轻触任意位置即可解锁声音（浏览器安全策略要求用户手势）。

---

## 📝 License

MIT License

---

## 📧 数据来源

地震数据来自 Wolfx CENC 中国地震台网 API：
- WebSocket: `wss://ws-api.wolfx.jp/cenc_eqlist`
- REST API: `https://api.wolfx.jp/cenc_eqlist.json`
