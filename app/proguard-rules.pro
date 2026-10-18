# TinyPinyin loads its dictionary classes by name; keep them whole.
-keep class com.github.promeg.** { *; }
-dontwarn com.github.promeg.**

# Click handlers referenced from XML are kept by name.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
