# BDK native library rules
-keep class org.bitcoindevkit.** { *; }
-dontwarn org.bitcoindevkit.**

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
