# Consumer R8/ProGuard rules for the Expys Kotlin SDK (com.expys:sdk).
#
# These ship inside the published jar at META-INF/proguard/expys-sdk.pro (wired in
# build.gradle.kts), so an Android app's R8 applies them automatically when it
# shrinks a release build - no app-side configuration required. They keep the public
# API the app calls into and the kotlinx.serialization metadata the model DTOs need.

# Keep the public SDK surface (the package the app calls into) and its public members.
-keep public class com.expys.sdk.** { public *; }
-keep public interface com.expys.sdk.** { public *; }

# kotlinx.serialization: keep the generated serializers, the Companion serializer()
# accessors, and the serializable fields of the model DTOs so (de)serialization keeps
# working after shrinking. kotlinx-serialization ships generic rules of its own; these
# scope the same guarantees to the SDK models so the contract is explicit.
-keepclassmembers @kotlinx.serialization.Serializable class com.expys.sdk.models.** {
    *** Companion;
    <fields>;
}
-keepclasseswithmembers class com.expys.sdk.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.expys.sdk.models.**$$serializer { *; }
