# lxdl（Flutter + Kotlin）

洛雪音源下载器的 Android 重写版：Flutter 画界面，Kotlin 干重活。

## 功能

- 分享链接直达：其他 App 分享文本到本应用即自动解析
- 后台下载：前台服务 + 通知栏进度，完成通知可点开
- 下载控制：暂停 / 继续 / 取消，断点续传（.part）
- 并发下载（1–3）与「仅 Wi-Fi」开关
- 下载前试听（MediaPlayer，可试听候选直链）
- 歌曲/歌单二选；歌单展开后勾选曲目再下载
- 复制直链、长按记录：打开 / 分享 / 编辑标签 / 删除
- 搜索历史、自定义命名模板、歌词 .lrc 旁车
- 音源测速（加载耗时 + 支持平台）、导出目录 SAF 自选
- 导入洛雪音源脚本（**系统文件选择器**选 .js，也支持粘贴脚本/URL），列表里 ↑↓ 调整优先级
- 搜索歌曲/歌单（网易云、QQ、酷狗；酷我/咪咕用链接解析或手填 id）
- 粘贴分享链接解析成歌曲/歌单 id
- 确认卡片：封面、歌名、歌手、音质（默认 320k）、命名方案（歌名-歌手 / 歌手-歌名 / 仅歌名）、下载目录
- 多渠道取链：按音源顺序试前 3 个，非官方渠道优先，按平台标注时长挑最长版本
- 写标题/歌手/专辑/封面/歌词（jaudiotagger）
- 下载列表：全部 / 下载中 / 已完成；完成的文件不在原位置自动标「已删除」
- 深色主题跟随系统、页面/卡片过渡动画

## 架构

```
lib/                       Flutter UI（3 页 + 底部导航 + 过渡动画）
  src/bridge.dart          MethodChannel 封装
  src/pages/search.dart    搜索 / 解析 / 确认卡片
  src/pages/downloads.dart 下载列表
  src/pages/sources.dart   音源排序、镜像/代理、文件导入
packages/lxdl_native/      本地 Flutter 插件（自动注册，不依赖 MainActivity）
  android/src/main/kotlin/com/lxdl/native/
    LxdlPlugin.kt          通道、生命周期、系统文件选择器、下载任务
    Engine.kt              隐藏 WebView 跑洛雪脚本 + 取链 + 下载 + 导出
    Providers.kt           平台搜索/详情/歌词/歌单/链接解析
    Http.kt                OkHttp（代理、流式下载）
    Tagger.kt              jaudiotagger 写标签
    Crypto.kt              md5 / aes / rsa / randomBytes
    Repo.kt                配置、音源、下载列表（JSON 持久化）
    Exporter.kt            导出到 Download（MediaStore）
assets/lx_host.html        JS 侧 lx 协议 shim（Buffer / URL / 定时器 polyfill）
```

用插件而不是自定义 Activity：`MainActivity` 被 Flutter 模板重新生成时会丢代码，
插件的 `onAttachedToEngine` 由 Flutter 自动调用，通道不会再丢。

洛雪脚本运行在系统 WebView 里（完整 ES2020），`lx.request` 通过
`@JavascriptInterface` 回到 Kotlin 发 HTTP，结果再 `evaluateJavascript` 回调。
`lx.utils.crypto` / `zlib` 由 Kotlin 同步实现。

## 镜像 / 代理

音源页顶部两个输入框：

- **镜像**：把平台 API 请求（搜索/详情/歌词）改走你的反代，例如
  `https://api.example.com`，路径和查询串原样保留
- **代理**：`127.0.0.1:7890` 这种 HTTP 代理，作用在所有请求（含音频下载）

没配置时点下载会先弹「未配置镜像/代理，下载可能很慢」，可以取消去配置。

## 构建 APK

### GitHub Actions（推荐，不用装环境）

推到 GitHub 后在 Actions 里跑 `build-apk`，产物在 Artifacts：

```bash
git add .
git commit -m "flutter rewrite"
git push
```

工作流见仓库根目录 `.github/workflows/build-apk.yml`。

### 本地（Windows / macOS / Linux）

```bash
# Flutter 3.22+ / JDK 17 / Android SDK（版本跟随 Flutter）
cd flutter_app

# 仓库里没带 Gradle wrapper，先从模板补一份（版本与你的 Flutter 匹配）
flutter create --platforms=android --org com.lxdl --project-name app /tmp/tpl
cp -r /tmp/tpl/android/gradle android/gradle
cp /tmp/tpl/android/gradlew android/gradlew

flutter pub get
flutter build apk --release
# 产物：build/app/outputs/flutter-apk/app-release.apk
```

第一次会自动生成 `android/local.properties`。如果本地的 AGP/Gradle 报版本不兼容，
把模板的 `settings.gradle*`、`build.gradle*`、`gradle.properties` 也复制过去即可
（CI 就是这么做的）。

## 说明

- 下载目录默认是系统 Download 下的 `lxdl` 子目录，可在搜索页改（只填子目录名）
- minSdk 26；Android 10+ 通过 MediaStore 写入 Download，不需要存储权限；9 及以下会申请写权限
- 歌单目前只支持网易云（整单展开，不分页截断）
- 酷我/咪咕不支持搜索，但可以用链接解析
- 源码全部未在本机编译（本机没有 JDK/Flutter/Android SDK），首次构建如有报错属正常调试
