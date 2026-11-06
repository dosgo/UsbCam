# UVC 库：保留 usb 核心，其余类可能引用已不存在的 common 包，不混淆避免问题
-keep class com.serenegiant.usb.USBMonitor { *; }
-keep class com.serenegiant.usb.UVCCamera { *; }
-dontwarn com.serenegiant.common.**
-dontwarn com.jiangdg.**
