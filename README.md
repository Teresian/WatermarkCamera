# 水印相机 Watermark Camera

一个开源、纯净的安卓水印相机。拍照时自动把**时间、日期、地点、备注**烧录进照片，生成的照片直接进系统相册，不带任何广告、不需要登录、不上传云端。

## 功能

- 📷 CameraX 实时预览，前后摄像头一键切换，支持闪光灯
- 🕒 实时时间/日期水印，所见即所得
- 📍 自动定位并把坐标反解成可读地名（省 · 市 · 区 · 街道），无地名时回退显示经纬度
- ✍️ 自定义备注文字水印
- 💾 水印**烧录进像素**后保存到 `相册/WatermarkCamera`，任何 App 打开都能看到
- 🌏 全离线运行，无网络权限

## 截图

> 拍照界面：左侧黄色竖条 + 大号时间 + 日期 + 地点

## 下载 APK

到 [Releases](https://github.com/Teresian/WatermarkCamera/releases/latest) 页面下载 `app-release.apk`，传到手机上安装即可（Android 7.0 / API 24 及以上）。

> 该 APK 使用 debug 签名，适合自用安装。若要上架应用商店，请用下面的环境变量配置自己的 keystore 重新打包。

## 自己构建

```bash
# 需要 JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 下可以用 `gradlew.bat`。首次构建需要联网拉依赖。

### 发布签名（可选）

设置以下环境变量后，`assembleRelease` 会用你自己的 keystore 签名：

| 变量 | 说明 |
| --- | --- |
| `WM_SIGN_STORE` | keystore 文件路径 |
| `WM_SIGN_STORE_PASSWORD` | keystore 密码 |
| `WM_SIGN_KEY_ALIAS` | 密钥别名 |
| `WM_SIGN_KEY_PASSWORD` | 密钥密码 |

不设置时 release 会回退到 debug 签名，方便本地出包测试。

## 项目结构

```
app/src/main/java/com/xiaojing/watermarkcamera/
├── MainActivity.kt            # 入口，Material3 深色主题
├── ui/
│   ├── CameraScreen.kt        # 相机界面、水印预览叠层、设置面板
│   └── CameraViewModel.kt     # 拍摄后处理流水线（解码 → 旋转校正 → 烧水印 → 存相册）
├── watermark/
│   ├── WatermarkConfig.kt     # 水印开关与内容
│   └── WatermarkRenderer.kt   # Canvas 烧录水印（渐变遮罩 + 强调竖线 + 自动省略）
└── util/
    ├── LocationHelper.kt      # 定位 + Geocoder 反解地名
    └── ImageSaver.kt          # MediaStore / 文件两种保存路径
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `CAMERA` | 拍照 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 取地名写进水印；拒绝也不影响拍照 |
| `WRITE_EXTERNAL_STORAGE`（仅 Android 9 及以下） | 老系统写相册 |

## License

MIT
