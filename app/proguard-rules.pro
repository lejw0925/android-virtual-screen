# The root display entry point is loaded by app_process using its fully-qualified name.
-keep class io.github.lejw0925.virtualscreen.backend.RootDisplayCommandMain { public static void main(...); }
-keep class io.github.lejw0925.virtualscreen.** { *; }

# Ktor's JVM debugger detector references java.lang.management, which Android does not ship.
# The reference is only reached on desktop JVMs, so warn suppression is enough.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
