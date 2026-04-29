## Release hardening.
## Keep gomobile entry points stable for JNI/reflection and strip Android logs
## from the minified release artifact.

-keep class go.** { *; }
-keep class vpncore.** { *; }
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn go.**
-dontwarn vpncore.**
-dontwarn org.yaml.snakeyaml.**

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
