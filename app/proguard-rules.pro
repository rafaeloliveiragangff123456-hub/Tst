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

# depuração por Wi-Fi (libadb-android + Conscrypt + sun.security)
-keep class io.github.muntashirakon.** { *; }
-keep class com.github.MuntashirAkon.** { *; }
-keep class sun.security.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn org.conscrypt.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
