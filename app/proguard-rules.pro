# Remove TODOS os logs do APK de produção
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-keep class com.botsync.app.MainActivity { *; }
-keep class com.botsync.app.NotificacaoService { *; }
-keep class com.botsync.app.App { *; }
