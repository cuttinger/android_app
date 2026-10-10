# 听读 (TtsReader)

把多行英文文本逐句用系统 TTS 朗读，同时把文字显示出来并高亮当前句；
支持 Android Auto，在车机上边听边看。

## 手机端

- 首页粘贴或输入文本（也可从任意 App「分享」文字进来），点「朗读」保存并开始。
  保存过的文本列在下方，点击继续读（记住上次读到的句子），长按可编辑或删除。
- 阅读页：句子列表，正在朗读的句子加粗高亮并自动滚到视野中，点任一句从那句开始。
  底部有上一句 / 播放暂停 / 下一句和语速调节（0.5x–2.0x）。
- 朗读由前台服务维持，通知栏是媒体样式，带上一句 / 播放暂停 / 下一句按钮；
  锁屏或切到别的 App 也会继续读。请求音频焦点，来电或其他媒体开始播放时自动暂停。

## 换更自然的声音

App 走系统 TextToSpeech 接口，任何注册为系统 TTS 引擎的第三方引擎都能用。阅读页底部
「声音」按钮可在 App 内选择引擎和音色（★ 越多质量越高，标「需联网」的是在线音色），
不影响系统默认设置。推荐的引擎见下（都以系统 TTS 引擎身份安装，装好后在「声音」里选）：

| 引擎 | 特点 | 联网 |
|---|---|---|
| HayaiTTS（sherpa-onnx，Kokoro / Piper 等 186 个音色） | 开源免费，离线，Kokoro 音色接近商用水准 | 否 |
| VoxSherpa TTS（sherpa-onnx，Kokoro + Piper） | 开源免费，离线，需 Android 11+、约 500 MB 模型 | 否 |
| sherpa-onnx 官方单音色 APK（如 en_US-joe-medium） | 每个 APK 一个 Piper 音色，体积小 | 否 |
| BookFusion Voice | 免费，离线，160+ 音色 | 否 |
| TTS Server（jing332/tts-server-android） | 微软 Edge 神经网络音色，非常自然，免费但依赖微软接口 | 是 |
| Google 文字转语音 自带高质量音色 | 在 Google TTS 设置里为英语下载更高质量的语音数据 | 部分 |



`app/src/main/assets/phrases.txt` 每行 `短语 | 例句 | 中文解释`（来源：
`~/.qclaw/workspace/memory/daily-phrases-used.txt` 里的 180 个短语，例句和解释为手写）。
首次启动时导入为一篇内容「日常英语短语 180 条」。每条**显示例句**，下方注释显示
「短语：中文解释」；**朗读顺序是短语 → 中文解释 → 例句**，中英文自动切换语音。
手机阅读页、车机跟读屏、媒体列表和正在播放页都是这个规则。

第二批 `assets/phrases2.txt`：128 条美国日常口语与职场地道用语（my bad、bandwidth、loop someone in、
low-hanging fruit、no cap、quiet quitting 等），排除课本常见句，且不与第一批重复，
导入为「美国日常口语与职场用语 128 条」。新增批次只需在 `Library.BUILTINS` 加一行。

第三批 `assets/vocab.txt`：769 词生词本，来自用户 PDF 词表
（`pdftotext -layout` 解析，两栏表格；7 个因换行被截断的词手工补齐）。
每词一个自编例句加中文释义，导入为「生词本 769 条」：显示例句，注释显示「单词：释义」，
朗读顺序是**单词 → 中文释义 → 例句**：单词和例句用英语音色，中文释义自动切到中文语音
（引擎没有中文时跳过这一段）。

文本格式约定：一行里用 ` || ` 分隔成最多三段：`显示文本 || 注释 || 单词`。
注释形如「单词：中文释义」，只显示不整体朗读；第三段是朗读和封面图上用的单词，可省略。
朗读顺序为 单词(英) → 注释中「：」之后的中文 → 显示文本(英)。普通文本不受影响。

## 媒体 App 形态（推荐在真车 Android Auto 上使用）

除模板界面外，本应用还注册了 `ReaderMediaService`（MediaBrowserService + MediaSession），
以**媒体 App** 的身份出现在 Android Auto 的媒体应用列表和 Automotive OS 媒体中心里，
和音乐、播客 App 同一入口。侧载的媒体 App 只要打开「未知来源」就一定会显示，比模板 App 可靠。

- 浏览：根目录列出所有文本，进入后列出每一句，行标题是英文句子，副标题是中文解释。
- 正在播放页：标题 = 当前英文句，副标题（歌手位）= 中文解释，专辑位 = 文本标题，
  队列 = 全部句子（可点选跳转）。
- 方向盘 / 车机的播放、暂停、上一曲、下一曲直接控制朗读；通知栏也绑定了同一个会话。
- 车机媒体界面的封面位去不掉，于是把它利用起来：`SentenceArt` 把当前句的英文和中文渲染成
  一张图当"封面"（正在播放页的方形区域只显示单词或词组，例句在右侧标题里），浏览列表和队列里的图标换成序号。
  图片地址带渲染版本号（`ArtProvider.RENDER_VERSION`），改渲染时递增以避开车机的图片缓存。
  车机 UI 只认 `content://` 图片，所以由 `ArtProvider` 提供，内联 bitmap 会被忽略。

## Android Auto

Android Auto 不允许第三方 App 自由绘制界面，只能使用 Google 的模板库
（Car App Library，`androidx.car.app`）。本应用的车机界面：

- 第一屏：手机上保存的文本列表（`PassageListScreen`），点一条开始朗读。
- 阅读屏（`ReaderScreen`）：`ListTemplate` 列出当前句附近的一组句子（上一句、当前句、
  后面几句；行数受车机限制，一般 6 行），当前句标 `▶ 序号 / 总数`，随 TTS 进度自动刷新；
  右上操作条有上一句 / 播放暂停 / 下一句；点任一行跳到那句。
- 音频：TTS 以 `USAGE_MEDIA` 输出并申请音频焦点，连接 Auto 后走车机音响。
- 如果手机端已经在读，进入车机 App 直接打开阅读屏。

### 两种车机环境

| 环境 | 需要什么 | 入口 |
|---|---|---|
| Android Auto（手机投屏到车机） | 手机装本 App，Auto 开发者模式勾选「未知来源」 | `ReaderCarAppService` |
| Android Automotive OS（车机自带系统 / 电脑模拟器） | 直接在车机装本 App | 同上，外加 `CarAppActivity` 启动器入口 |

**Android Auto**：侧载的 Auto App 默认不显示，要在手机 Android Auto 设置里连点底部「版本」
10 次进入开发者模式，右上角菜单 → 开发者设置 → 勾选「未知来源」，重新连接车机即可。
桌面模拟器 DHU：`sdkmanager "extras;google;auto"`，手机开启「启动头单元服务器」，
`adb forward tcp:5277 tcp:5277` 后运行 `$ANDROID_HOME/extras/google/auto/desktop-head-unit`。

**Android Automotive OS 模拟器**（已在本机验证）：

```bash
sdkmanager "system-images;android-34-ext9;android-automotive;arm64-v8a"
avdmanager create avd -n car34 -k "system-images;android-34-ext9;android-automotive;arm64-v8a" -d automotive_1024p_landscape
emulator -avd car34 -no-snapshot            # 带窗口启动，可直接用鼠标操作
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```
车机桌面的应用列表里会出现「听读」。车机系统运行在用户 10 下，命令行启动要加 `--user 10`：
`adb shell am start --user 10 -n com.xingang.ttsreader/androidx.car.app.activity.CarAppActivity`。
截屏要指定主屏：`adb exec-out screencap -d <主屏物理ID> -p`（`dumpsys SurfaceFlinger --display-id` 查）。

### 说明与限制

- Manifest 里声明的类别是 `androidx.car.app.category.IOT`（模板 App 必须选一个类别，
  侧载用途下类别不影响功能）。上架 Play 商店需要通过 Google 的 Auto 审核，并把
  `createHostValidator()` 改回官方 host 白名单。
- 模板 host 的硬性限制：列表操作条最多 2 个按钮（放了播放/暂停和下一句，上一句点列表里
  上一行即可）；带文字标题的按钮最多 1 个（因此全部用纯图标）；列表默认最多 6 行。
- 模板每行最多显示约两行文字，太长的句子会被车机截断。
- 车机模拟器的音频通道在进程刚启动时偶发失败（TTS 错误码 -5，随后系统还会发一个虚假的
  音频焦点丢失）。引擎对此做了三层保护：出错 1.5 秒后重试（最多 2 次）、错误后 3 秒内忽略
  焦点丢失、一句话超过预期时长没有回调则重新朗读。连续 4 次冷启动测试全部读完。
- `CarAppActivity` 只在有模板 host 的车机系统上启用（`App.onCreate` 按 automotive 特性开关），
  手机上只有一个启动图标。`app-automotive` 库要求 minSdk 29。
- 目前没有接 MediaSession，方向盘的播放 / 暂停键暂不联动；用车机屏幕上的操作条控制。

## 构建与测试

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest      # 分句规则测试
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构

```
app/src/main/java/com/xingang/ttsreader/
├── MainActivity.kt        # 输入 / 保存 / 文本列表 / 接收分享
├── ReadActivity.kt        # 手机阅读页：句子列表、高亮、控制条
├── ReaderEngine.kt        # 单例：TTS 引擎、播放状态 (StateFlow)、音频焦点
├── ReaderService.kt       # 前台服务 + 媒体样式通知
├── Sentences.kt           # 分句
├── Library.kt             # 文本与进度存储
└── car/
    ├── ReaderCarAppService.kt   # Android Auto 入口
    ├── PassageListScreen.kt     # 车机：文本列表
    └── ReaderScreen.kt          # 车机：跟读界面
```
