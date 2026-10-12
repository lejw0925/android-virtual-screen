# The root display entry point is loaded by app_process using its fully-qualified name.
-keep class io.github.lejw0925.virtualscreen.backend.RootDisplayCommandMain { public static void main(...); }
-keep class io.github.lejw0925.virtualscreen.** { *; }
