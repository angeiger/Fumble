# Room and Hilt ship their own consumer rules; nothing extra is required for them.

# Keep the Room entity/DAO POJOs that are only touched reflectively by generated code.
-keep class com.fumble.app.data.local.** { *; }

# Coil
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin metadata used by coroutines' debug agent in release stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
