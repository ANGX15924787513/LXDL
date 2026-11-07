# jaudiotagger 的桌面图片处理类引用了 AWT/ImageIO，Android 上没有这些类；
# 我们只用它的标签读写与封面字节写入，不会走到这些方法。
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn javax.sound.**
-dontwarn org.jaudiotagger.**

-keep class org.jaudiotagger.** { *; }
-keep class net.jthink.** { *; }

# Flutter 插件入口与服务由清单/注册表引用，显式保留
-keep class com.lxdl.engine.LxdlPlugin { *; }
-keep class com.lxdl.engine.DownloadService { *; }
# @JavascriptInterface 方法必须保留（WebView 只按名字调用）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# QuickJS：JNI 通过类名/方法名回调
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**
