# Proguard rules for Xiaoxi Android (小汐 2.0)
-keepattributes *Annotation*,InnerClasses,Signature

# Room SQLite
-keep class androidx.room.** { *; }
-keep class com.xiaoxi.android.database.entity.** { *; }
-keep class com.xiaoxi.android.database.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# kotlinx.serialization
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer();
}

# Ktor & OkHttp
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**

# slf4j 1.x 的静态绑定类为可选依赖（Ktor 经由 okhttp 引入），运行时由 android 端回退 NOP 绑定
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep models for Automation & Perception
-keep class com.xiaoxi.android.automation.** { *; }
-keep class com.xiaoxi.android.perception.model.** { *; }
-keep class com.xiaoxi.android.network.model.** { *; }

# WorkManager：Worker 由 WorkerFactory 按类名反射实例化，
# R8 裁剪或重命名会导致运行时 ClassNotFoundException
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}
