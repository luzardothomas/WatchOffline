# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Gson: no ofuscar/strippear modelos que se parsean por reflexión
-keepattributes Signature
-keepattributes *Annotation*

# Mantener clases internas de importers (ApiCover) y sus fields
-keep class com.example.watchoffline.AutoImporter$ApiCover { *; }
-keep class com.example.watchoffline.LocalAutoImporter$ApiCover { *; }

# Mantener modelos persistidos en JSON (SharedPreferences)
-keep class com.example.watchoffline.VideoItem { *; }
-keep class com.example.watchoffline.ImportedJson { *; }
-keep class com.example.watchoffline.JsonDataManager { *; }
