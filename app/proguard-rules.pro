# Proguard rules for Xiaoxi Android
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
