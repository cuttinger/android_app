# 最近使用 (RecentApps)

一个极简的 Android 应用：打开后把手机上所有可启动的应用按**最近打开时间**倒序排列，
点击任意一项即可启动该应用。

## 原理

- 通过 `UsageStatsManager` 读取系统的应用使用记录：
  - `queryEvents` 取最近 14 天的前台事件（精确到每次打开）；
  - `queryAndAggregateUsageStats` 取最近 1 年的聚合数据，用 `lastTimeUsed` 补全更早的应用。
- 与 `PackageManager` 查到的所有带 LAUNCHER 入口的应用做关联，没有使用记录的排在最后按名称排序。
- 需要用户在系统设置里手动授予「使用情况访问权限」（`PACKAGE_USAGE_STATS`），
  首次打开时会显示引导页并提供跳转按钮。每次回到本应用都会自动刷新，也可以下拉刷新。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 34）。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 中的 `sdk.dir` 指向本机 SDK 路径，默认为 `~/Library/Android/sdk`。

## 代码结构

```
app/src/main/java/com/xingang/recentapps/
├── MainActivity.kt      # 界面：权限引导 / 列表 / 下拉刷新 / 点击启动
├── UsageRepository.kt   # 读取使用记录并与可启动应用关联、排序
├── AppListAdapter.kt    # RecyclerView 适配器，显示图标、名称、相对时间、绝对时间
└── AppUsage.kt          # 数据模型
```
