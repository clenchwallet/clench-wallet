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
