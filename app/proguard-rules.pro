# Only shrink; names stay readable in crash logs.
-dontobfuscate

# The patching libraries are kept whole: they are large, used reflectively in places,
# and patching must behave exactly as on the desktop CLI.
-keep class nx.** { *; }
-keep class com.android.tools.smali.** { *; }
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-keep class org.bouncycastle.** { *; }

# Optional dependencies of the libraries above that are not on Android.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xmlpull.**
-dontwarn kotlin.**
