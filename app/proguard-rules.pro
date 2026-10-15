# Sceneform 1.17.1 依赖未做 R8 优化,直接 keep 最简单可靠
-keep class com.google.ar.sceneform.** { *; }
-keep class com.google.ar.** { *; }
-keep class com.google.atap.tangoservice.** { *; }
-dontwarn com.google.ar.sceneform.**
