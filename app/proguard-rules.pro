

# adblock: JNI entry points + JS bridge (names looked up by the native library / WebView)
-keepclasseswithmembers class com.voluene.monobrowser.adblock.NativeAdblock { native <methods>; }
-keepclassmembers class com.voluene.monobrowser.adblock.RustAdBlocker$Bridge { @android.webkit.JavascriptInterface <methods>; }
