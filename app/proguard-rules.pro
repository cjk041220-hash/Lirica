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

# Keep models for Automation & Perception
-keep class com.xiaoxi.android.automation.** { *; }
-keep class com.xiaoxi.android.perception.model.** { *; }
-keep class com.xiaoxi.android.network.model.** { *; }
