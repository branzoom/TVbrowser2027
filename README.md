# 电视浏览器助手2027 · TVbrowser2027

在安卓电视的大屏上直接打开网页、看网页版视频，用手机扫码就能输入网址、遥控电视，不用再投屏。

> An Android TV web browser built on Mozilla GeckoView (latest Firefox engine), with a remote-controlled mouse cursor and a phone-based remote via QR code.

## 能做什么

- **自带最新 Firefox 内核（GeckoView）**：电视自带的网页内核往往停在多年前的版本，很多视频网站会出现「有声音没画面」或「浏览器版本太低」。这个应用自带新内核，不受电视系统限制。
- **遥控器当鼠标用**：方向键移动光标，OK 键点击，光标移到屏幕边缘会滚动页面，鼠标悬停菜单也能用。
- **全屏看视频时按键自动切换**：OK 键暂停/播放，← → 快退/快进，返回键退出全屏。
- **手机扫码遥控**：手机和电视连同一个 Wi‑Fi，扫一下电视首页的二维码，就能在手机上粘贴网址（App 里「分享 → 复制链接」的整段文字也行）、搜片名、翻页、暂停，不用装任何 App。
- **首页网站卡片**：B站、腾讯视频、爱奇艺、优酷、芒果TV 等常用网站，带品牌色和图标；也可以收藏你自己的网站。
- **网页大小可调**：超大到特小六档，离电视远就调大一点。
- **默认电脑版网页**：大屏排版更舒服，也不会一直弹窗让你下载 App。
- **网页卡死能自救**：电视内存不够、网页进程被系统回收时会自动重新加载；按菜单键（或长按返回键）随时能回到首页点「重新加载」。

## 下载安装

到 [Releases](https://github.com/branzoom/TVbrowser2027/releases) 页面下载安装包：

| 安装包 | 适合 | 大小 |
|---|---|---|
| `TVbrowser2027-v1.0.1-32位.apk` | **大部分电视选这个**（小米/红米等电视多数是 32 位系统） | 约 88MB |
| `TVbrowser2027-v1.0.1-64位.apk` | 确认是 64 位系统的新电视 | 约 90MB |
| `TVbrowser2027-v1.0.1-通用版.apk` | 32 位版装不上或打不开时用这个，所有电视都能装，只是更大 | 约 159MB |

要求：**Android 8.0 及以上**的安卓电视或电视盒子（ARM 芯片，市面上的电视基本都是）。

怎么装到电视上、怎么用遥控器和手机遥控，请看 👉 **[详细安装和使用指南](docs/安装使用指南.md)**

## 用 AI 自己做一个

整个应用是用 AI 编程助手做出来的，作者没有手写代码。完整提示词整理在 👉 **[完整提示词](docs/提示词.md)**，复制给 AI 就能做出差不多的应用。国内用户推荐用 **WorkBuddy + DeepSeek 模型**。

## 自己编译

需要 JDK 17 和 Android SDK（compileSdk 37）。

```bash
git clone https://github.com/branzoom/TVbrowser2027.git
cd TVbrowser2027
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 改成你自己的 SDK 路径
./gradlew assembleRelease
```

安装包会输出到 `app/build/outputs/apk/release/`，同时生成 32 位、64 位和通用版三个文件。

- 没有 `keystore.properties` 时会自动用调试签名，自己用没问题。注意：**用调试签名编译的包，不能覆盖安装 Releases 里的正式版**，要先卸载旧的。
- 想用自己的正式签名，在项目根目录建一个 `keystore.properties`：

  ```properties
  storeFile=/你的路径/xxx.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

用 adb 直接装到电视上：

```bash
adb connect 电视IP:5555
adb install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk
```

### 代码结构

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/tvbrowser/assistant/MainActivity.java` | 主界面：GeckoView 网页、首页、遥控器按键处理、崩溃自动恢复 |
| `app/src/main/java/com/tvbrowser/assistant/CursorView.java` | 屏幕上的鼠标光标 |
| `app/src/main/java/com/tvbrowser/assistant/RemoteServer.java` | 手机遥控用的局域网 HTTP 服务（端口 8765） |
| `app/src/main/assets/remote.html` | 手机扫码后打开的遥控页面 |

## 说明

- 本应用只是一个浏览器，网页和视频内容归各网站所有。**看会员内容需要你自己的账号本身有对应会员**，本应用不提供任何破解功能。
- 手机遥控页只在局域网内可用，同一个 Wi‑Fi 下的人都能打开，请不要在公共网络上使用。
- 遇到问题欢迎提 [Issue](https://github.com/branzoom/TVbrowser2027/issues)，请写上电视品牌型号、Android 版本，以及在哪个网站做什么操作时出的问题。

## 开源许可

本项目代码使用 [MIT 许可证](LICENSE)。

用到的开源软件：

- [Mozilla GeckoView](https://github.com/mozilla-firefox/firefox)（Firefox 浏览器内核），Mozilla Public License 2.0
- [ZXing](https://github.com/zxing/zxing)（二维码生成），Apache License 2.0
