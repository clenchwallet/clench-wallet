# BDK native library rules
-keep class org.bitcoindevkit.** { *; }
-dontwarn org.bitcoindevkit.**

# BDK UniFFI loads libbdkffi through JNA at runtime. JNA relies on reflection
# and native registration, so release minification must preserve its names.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn com.sun.jna.**

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class **_HiltModules { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# M-7: Strip debug/verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
