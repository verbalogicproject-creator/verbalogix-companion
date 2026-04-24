# R8/ProGuard rules for release builds.
#
# Ktor + Netty + kotlinx.serialization use reflection extensively; default
# shrinker rules strip classes they need at runtime. The consumer-rules
# shipped with the libraries cover most cases, but keep the rules below in
# place as belt-and-braces for CIO engine + our own DTO classes.

# Keep all classes in our DTO contract so kotlinx.serialization descriptors work
-keep class com.verbalogix.companion.http.** { *; }

# Ktor — CIO engine
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.application.** { *; }
-keep class io.ktor.server.websocket.** { *; }

# kotlinx.serialization — generated serializers follow $$serializer suffix
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}

# Netty — used indirectly by ktor-server-cio for socket I/O
-dontwarn io.netty.**
-keep class io.netty.** { *; }

# AccessibilityService entry point is referenced by manifest string
-keep class com.verbalogix.companion.accessibility.EngineAccessibilityService { *; }
-keep class com.verbalogix.companion.http.EngineHttpService { *; }
