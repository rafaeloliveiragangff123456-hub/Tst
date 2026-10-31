# Remove TODOS os logs do APK de produção
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-dontwarn com.google.android.gms.**
-keep class com.botsync.app.MainActivity { *; }
-keep class com.botsync.app.Imap2Service { *; }
-keep class com.botsync.app.App { *; }
