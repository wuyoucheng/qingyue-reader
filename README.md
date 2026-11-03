# 轻阅 · 安卓本地小说阅读器

一个纯本地的小说阅读 App：导入 txt / epub，自动分章、记忆阅读位置，支持在线多音色听书，
锁屏与切后台后可以连续朗读、自动跨章。

- 安装包见 [Releases](https://github.com/wuyoucheng/qingyue-reader/releases)
- 体积约 2.7MB，**零 native 库**（无 `.so`），不需要安装任何第三方语音引擎
- 全部数据留在本机（IndexedDB），不联网也能读；听书时才访问语音接口

## 功能

| 模块 | 说明 |
|---|---|
| 导入 | txt（自动识别 UTF-8 / GBK / Big5）与 epub，SAF 选择文件，缓存副本避免授权失效 |
| 分章 | 按「第 N 章」等常见标题正则切章，支持手动改编码后重新解析 |
| 阅读 | 上下滚动 / 左右翻页两种模式，字号、行距、边距、主题（纸黄/夜间/护眼）可调 |
| 记忆 | 记住每本书的章节与位置，重进直接续读；显示总进度与剩余章节 |
| 听书 | 14 个中文音色（晓晓、云希、云扬、辽宁小北、陕西小妮、粤语等）+ 百度兜底 + 系统 TTS 兜底 |
| 后台 | 前台服务 + 常驻通知（书名·章节·进度·停止），CPU/WiFi 唤醒锁，锁屏继续念 |
| 跨章 | 原生队列一次装载 8 章，念完一章自动进下一章，不足时回叫网页补章节 |
| 其他 | 定时关闭、语速/音调调节、当前句高亮、屏幕常亮 |

## 语音方案

App 内置了微软 Edge 在线语音（WebSocket 直连，无需 API Key、无需装引擎），
实现在 `EdgeTtsEngine.java` + `EdgeGec.java`：

- 鉴权令牌为 `Sec-MS-GEC` **v2** 格式（`CS64` + 微软变体 SHA-256，`K[14] = 0xC9DC06A7`），
  纯 Java 实现，与官方算法 20/20 对拍一致
- 遇到 403 会用响应 `Date` 头校正时钟偏差后重试一次
- 第二引擎为百度翻译语音接口（`BaiduTtsEngine.java`），音质平一些但响应快
- 仍失败则降级到系统 TTS；设备没有中文引擎时，界面上会提示切换在线音色

> 早期版本用 sherpa-onnx 做过纯离线合成，但其 `libonnxruntime.so` 为 4KB 页对齐，
> 在部分机型上反复触发 native SIGSEGV 且无法从 Java 侧拦截，已在 v1.16 整体移除。
> 如需完全离线听书，请安装系统级中文 TTS 引擎（如讯飞语记、VoxEngine）。

## 构建

环境：JDK 17、Android SDK（platform 34 / build-tools 34.0.0）、Gradle 8.5。

```bash
# 1. 把网页前端同步进 assets
bash sync-assets.sh

# 2. 编译 Debug APK
gradle assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 需要指向本机 SDK，例如：

```properties
sdk.dir=/path/to/Android/sdk
```

CI：`.github/workflows/build-apk.yml` 会在 push 到 main 时自动打包并上传 artifact。
若该文件未能入库，可把 `ci/build-apk.yml` 移动回 `.github/workflows/` 目录。

## 目录结构

```
app/src/main/java/com/qingyue/reader/
  MainActivity.java      WebView 壳、文件导入、生命周期
  NativeTTS.java         朗读调度：原生多章队列、音色路由、降级、崩溃熔断
  PlaybackService.java   听书前台服务与常驻通知
  EdgeTtsEngine.java     Edge 在线合成（WebSocket）
  EdgeGec.java           Sec-MS-GEC v2 令牌
  BaiduTtsEngine.java    百度翻译语音兜底
  CrashLogger.java       未捕获异常落盘
novel-reader/            网页前端（源码，sync-assets.sh 同步到 assets/www）
  js/reader.js           章节渲染、位置记忆、朗读联动
  js/tts.js              朗读控制器（原生队列 / 浏览器两条路径）
  js/parser.js           编码识别、分章、分句
```
