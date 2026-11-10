# kotlinx.serialization keeps generated serializers via its bundled rules.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Room instantiates <Database>_Impl reflectively (WorkManager's database, used by the Glance
# widget). Old Room rules keep the class but not its constructor under R8 full mode.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
