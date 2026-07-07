# 地震预警项目 - 开发与部署踩坑记录

> 本文档记录了从项目开发到服务器部署过程中遇到的所有问题及其解决方案，供后续参考。
> ⚠️ 本文档中的 IP 地址、密码、密钥等已替换为占位符。

---

## 目录

1. [Maven 构建问题](#1-maven-构建问题)
2. [SSH 部署问题](#2-ssh-部署问题)
3. [连接状态显示问题](#3-连接状态显示问题)
4. [浏览器自动播放策略问题](#4-浏览器自动播放策略问题)
5. [事件双重触发问题](#5-事件双重触发问题)
6. [语音倒计时时序问题](#6-语音倒计时时序问题)
7. [代码质量问题](#7-代码质量问题)
8. [经验总结](#8-经验总结)

---

## 1. Maven 构建问题

### 1.1 Git Bash 下 `mvnw.cmd` 静默失败

**现象**：在 Git Bash 中使用 `cmd //c mvnw.cmd package` 命令，没有任何输出，也没有错误信息，但 JAR 文件实际上并未重新构建。

**根因**：Git Bash 的路径分隔符和 CMD 的兼容性问题。`cmd //c` 在 Git Bash 中的行为不稳定，Maven Wrapper 的 CMD 脚本在非原生 Windows 环境中可能静默失败。

**解决方案**：直接用 Java 启动 Maven Wrapper JAR 包，绕过 CMD 脚本：

```bash
java -Dmaven.multiModuleProjectDirectory=. -cp .mvn/wrapper/maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain package -DskipTests
```

**关键点**：
- `-Dmaven.multiModuleProjectDirectory=.` 必须设置，否则 Maven 在某些环境下会找不到项目目录
- 直接调用 `MavenWrapperMain` 类，不再依赖 `mvnw.cmd` 脚本

### 1.2 旧 JAR 部署后问题依旧

**现象**：修改代码后重新构建部署，但服务器上的行为没有任何变化，就好像新代码没生效。

**根因**：Maven 构建静默失败（见 1.1），实际部署的还是旧 JAR。

**解决方案**：
1. 每次构建后检查 JAR 文件的 MD5 值：
   ```bash
   md5sum target/earthquake-warning-*.jar
   ```
2. 部署后验证关键代码是否在服务器上生效：
   ```bash
   curl -s http://localhost:PORT/page.html | grep -c "关键词"
   ```
3. 确认 JAR 文件大小合理（不应与旧版本完全相同）

---

## 2. SSH 部署问题

### 2.1 Paramiko SFTP 传输

**现象**：需要通过 SSH 远程部署 JAR 到服务器。

**解决方案**：使用 Python paramiko 库，通过 `Transport` + `SFTPClient` 进行文件传输：

```python
import paramiko

transport = paramiko.Transport(('YOUR_SERVER_IP', 22))
transport.connect(username='root', password='YOUR_PASSWORD')

sftp = paramiko.SFTPClient.from_transport(transport)
sftp.put(local_jar_path, remote_jar_path)
sftp.close()
```

### 2.2 Spring Boot 启动后需要等待

**现象**：部署脚本启动 Java 进程后立即 curl 测试，返回连接拒绝。

**根因**：Spring Boot 从进程启动到 HTTP 端口监听需要 5~8 秒，期间任何 HTTP 请求都会失败。

**解决方案**：在启动命令后增加 `time.sleep(8)` 等待应用完全启动，再进行健康检查。

### 2.3 端口与进程管理

**现象**：重新部署时需要先停掉旧进程，否则端口被占用。

**解决方案**：
```bash
# 停止旧进程
pkill -f earthquake-warning 2>/dev/null
sleep 1
# 启动新进程
cd /opt/earthquake-warning && nohup java -jar earthquake-warning.jar > /dev/null 2>&1 &
```

**注意**：`2>/dev/null` 确保在第一次部署（没有旧进程）时不会报错。

---

## 3. 连接状态显示问题

### 3.1 控制面板显示"未连接"但数据正常接收

**现象**：HTTP 轮询模式工作正常，数据能正常接收和处理，但右上角连接状态始终显示红色"未连接"。

**根因**：`RuntimeConfigService.init()` 的初始化顺序问题。

代码流程：
1. `application.yml` → `defaultPrimaryChannel = "http"`
2. `loadFromDisk()` → 磁盘中保存的 `primaryChannel = "wss"` 覆盖了默认值
3. 结果：HTTP 模式正常工作，但状态检查接口返回 `primaryChannel: "wss"`，前端判断为 WSS 模式未连接

**修复**：在 `loadFromDisk()` 之后强制恢复 `application.yml` 的配置：

```java
// RuntimeConfigService.java
@PostConstruct
public void init() {
    loadFromDisk();
    // application.yml 的 primary-channel 优先级最高
    primaryChannel = defaultPrimaryChannel;
}
```

**设计原则**：`application.yml` 作为基础设施配置，优先级应高于运行时保存的用户偏好。

### 3.2 API 字段缺失

**现象**：前端调用 `/api/status` 接口，响应中没有 `primaryChannel` 和 `pollingActive` 字段。

**根因**：`WebSocketClientService.getStats()` 方法的返回值只包含基础统计信息，没有这两个字段。

**修复**：在 `getStats()` 的返回 Map 中添加：
```java
result.put("primaryChannel", runtimeConfigService.getPrimaryChannel());
result.put("pollingActive", pollingActive.get());
```

---

## 4. 浏览器自动播放策略问题

### 4.1 手机上完全没有声音

**现象**：页面实现了语音倒计时功能，但手机上打开后没有任何声音。

**根因**：移动端浏览器（iOS Safari / Android Chrome）有严格的自动播放策略：
- `AudioContext` 初始状态为 `suspended`
- `SpeechSynthesis` 在没有用户手势的情况下不能播放
- 任何代码都无法绕过这一安全限制

**尝试的错误方向**：
- 调整 AudioContext 创建时机
- 修改 OscillatorNode 参数
- 在 `window.onload` 中尝试播放
- 以上全部无效，浏览器层面直接拦截

**最终解决方案**：全屏遮罩 + 一键解锁

1. 页面加载时显示全屏半透明遮罩，提示"点击屏幕开启声音预警"
2. 遮罩上显示实时倒计时数字（与下方同步）
3. 用户轻触任意位置 → `unlockAudio()` 执行：
   - `AudioContext.resume()` 恢复音频上下文
   - `SpeechSynthesis.speak('')` 空语句解锁语音引擎
4. 解锁后遮罩消失，语音从当前秒数继续播报

```javascript
function unlockAudio() {
    if (cdState.audioUnlocked) return true;
    var ctx = getAudioCtx();
    if (ctx.state === 'suspended') {
        ctx.resume().then(function() {
            console.log('[CD] AudioContext resumed:', ctx.state);
        });
    }
    var u = new SpeechSynthesisUtterance('');
    u.volume = 0;
    speechSynthesis.speak(u);
    cdState.audioUnlocked = true;
    cdState.muted = false;
    return true;
}
```

**这是移动端能做到的最接近"自动"的方案**——用户打开页面后随手点一下屏幕即可。

### 4.2 SpeechSynthesis `getVoices()` 首次为空

**现象**：首次调用 `speechSynthesis.getVoices()` 返回空数组，导致语音播报使用的不是期望的语音。

**根因**：`getVoices()` 是异步加载的，浏览器需要时间初始化语音引擎。

**解决方案**：监听 `voiceschanged` 事件，在事件触发后再获取语音列表：
```javascript
speechSynthesis.onvoiceschanged = function() {
    var voices = speechSynthesis.getVoices();
    console.log('[CD] Available voices:', voices.length);
};
```

---

## 5. 事件双重触发问题

### 5.1 按钮点击后状态被翻转两次

**现象**：点击解除静音按钮 → 声音有了一瞬间 → 然后又被静音了。

**根因**：按钮同时绑定了两种事件处理器，点击一次触发两个回调：

```html
<!-- HTML 内联属性 -->
<button onclick="toggleMuteCD()">🔊 有声</button>
```

```javascript
// JavaScript addEventListener
muteBtn.addEventListener('click', function() {
    if (!cdState.audioUnlocked) { unlockAudio(); return; }
    toggleMuteCD();
});
```

**执行流程**：
1. `onclick` 触发 → `!audioUnlocked` → `unlockAudio()` → `muted = false` ✅
2. `addEventListener` 触发 → `audioUnlocked` 已为 true → `toggleMuteCD()` → `muted` 从 false 切回 true ❌

**结果**：音频刚解锁就被第二个回调重新静音。

**修复**：**彻底移除 HTML 中的 `onclick` 内联属性**，只保留 `addEventListener`：

```html
<!-- 修复后：无 onclick -->
<button class="cd-btn" id="cd-btn-mute">🔊 点击启用声音</button>
```

**铁律**：永远不要在 HTML 按钮中同时使用 `onclick` 和 `addEventListener`。

### 5.2 `cloneNode(true)` 造成的重复绑定

**现象**：替换节点后重新绑定事件，但旧事件仍残留在已替换的节点上。

**根因**：`btn.replaceWith(btn.cloneNode(true))` 这一模式本身就是反模式：
- 创建了全新的 DOM 节点
- 但代码逻辑上并没有必要替换节点

**修复**：移除所有 `cloneNode` + `replaceWith` 模式，直接使用 `addEventListener`。

---

## 6. 语音倒计时时序问题

### 6.1 倒计时比 S 波到达时间慢 1 秒

**现象**：视觉倒计时显示的数字比 S 波到达时间多出 1 秒，且开始时会停顿一秒才跳变。

**根因**：`setInterval(fn, 1000)` 的第一次执行在 1 秒之后：

```
t=0:  setInterval(fn, 1000) 被调用
t=0~1: 没有执行 fn，画面停顿
t=1:  第一次 fn 执行，显示 currentSec（此时已多过了 1 秒）
t=2:  第二次 fn 执行
```

**修复**：在 `setInterval` 之前先立即调用一次：

```javascript
countdownTick();  // t=0 立即执行第一次
cdState.intervalId = setInterval(countdownTick, 1000);  // t=1 起每秒执行
```

### 6.2 全屏遮罩倒计时结束不自动关闭

**现象**：如果用户始终没有点击屏幕解锁音频，倒计时到 0 后遮罩仍然显示。

**根因**：倒计时结束分支中只停止了定时器和播放了语音，没有处理遮罩层。

**修复**：在 `sec <= 0` 分支中增加遮罩关闭逻辑：

```javascript
if (sec <= 0) {
    // 倒计时结束，关闭遮罩
    var overlay = document.getElementById('audio-unlock-overlay');
    if (overlay) {
        overlay.style.opacity = '0';
        overlay.style.pointerEvents = 'none';
    }
    stopVoiceCountdown();
    // ...
}
```

---

## 7. 代码质量问题

### 7.1 模拟地震 API 返回数据不完整

**现象**：`/api/earthquake/simulate` 接口计算了每个城市的 S 波到达时间，但在 JSON 响应中不包含这些数据。

**根因**：`EarthquakeController` 中使用 `WarningResult.builder()` 构建响应时，没有把 `cityResults` 列表加入。

**修复**：在 `WarningResult` 中新增 `extra` 字段，将逐城计算结果放入响应。

### 7.2 事件监听器重复绑定

**现象**：`startVoiceCountdown()` 每次被调用时都会重新执行 `addEventListener`，导致同一个按钮绑定多个 handler。

**修复**：增加 `_bound` 标记位，防止重复绑定：

```javascript
var playBtn = document.getElementById('cd-btn-play');
if (playBtn && !playBtn._bound) {
    playBtn._bound = true;
    playBtn.addEventListener('click', togglePauseCD);
}
```

### 7.3 全局变量残留

**现象**：代码中有 `window.togglePauseCD` 和 `window.toggleMuteCD` 的全局赋值，但 HTML 中的 `onclick` 已经移除，这些全局暴露已无意义。

**修复**：删除这两行全局赋值。

### 7.4 版本号不一致

**现象**：`pom.xml` 显示 1.0.0，Swagger 配置显示 1.0.0，前端显示 v5.0，API 返回 5.0.0。

**修复**：统一为 5.6.0。

| 位置 | 旧值 | 新值 |
|------|------|------|
| pom.xml | 1.0.0 | 5.6.0 |
| application.yml (springdoc) | 1.0.0 | 5.6.0 |
| index.html | v5.0 | v5.6 |
| Controller API | 5.0.0 | 5.6.0 |
| OpenApiConfig.java | 1.0.0 | 5.6.0 |

---

## 8. 经验总结

### 8.1 构建与部署流程

采用 Python subprocess + Maven wrapper 的方式替代直接使用 `mvnw.cmd`：

```python
import subprocess
result = subprocess.run(
    ['java', '-Dmaven.multiModuleProjectDirectory=.',
     '-cp', '.mvn/wrapper/maven-wrapper.jar',
     'org.apache.maven.wrapper.MavenWrapperMain',
     'package', '-DskipTests'],
    capture_output=True, text=True, timeout=300
)
```

### 8.2 部署验证清单

每次部署后必须验证：

- [ ] JAR 文件 MD5 与本地不同
- [ ] HTTP 200: `curl http://localhost:PORT/index.html`
- [ ] API 正常: `curl http://localhost:PORT/api/status`
- [ ] 关键代码已更新: `grep` 检查新代码特征

### 8.3 前端开发注意事项

| 规则 | 说明 |
|------|------|
| 禁止 onclick + addEventListener | 永远只选一种绑定方式 |
| `setInterval` 前先调用一次 | 避免第一秒停顿 |
| AudioContext 需要用户手势 | 移动端无法绕过，使用遮罩引导点击 |
| `speechSynthesis.getVoices()` 异步 | 监听 `voiceschanged` 事件 |
| `cloneNode` + `replaceWith` | 通常是反模式，直接操作 DOM |

### 8.4 后端开发注意事项

| 规则 | 说明 |
|------|------|
| `application.yml` > 磁盘缓存 | 基础设施配置优先级最高 |
| API 响应完整性 | 确认所有计算数据都包含在响应中 |
| `@PreDestroy` 清理 | WebSocket/线程池等资源必须正确释放 |
| `ConcurrentHashMap` / `CopyOnWriteArrayList` | 多线程环境使用线程安全集合 |
