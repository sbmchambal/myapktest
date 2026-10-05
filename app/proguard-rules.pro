# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-dontwarn kotlinx.serialization.**
-keep class kotlinx.serialization.** { *; }
