# ArAr Media Proguard Rules
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.ararmedia.editor.engine.** { *; }
-keep class com.ararmedia.editor.model.** { *; }
