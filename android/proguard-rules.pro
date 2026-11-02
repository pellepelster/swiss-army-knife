# the rules r8 keeps for the release apks of every app (sak.android.app, build-logic), next to the ones
# kotlinx.serialization, ktor, okhttp and androidx bring along

# the bodies of the api (android/api, generated from openapi.json) are read and written by their
# serializers; their names are the ones of the json
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class io.pelle.sak.api.model.**$$serializer { *; }
-keepclassmembers class io.pelle.sak.api.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# what ktor names but an android app never loads (logging of the jvm, the debug detection)
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
