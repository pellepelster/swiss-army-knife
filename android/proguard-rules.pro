# the rules of r8 for every app, next to the ones the libraries bring

# the bodies of the api are read and written by their generated serializers
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class io.pelle.sak.api.model.**$$serializer { *; }
-keepclassmembers class io.pelle.sak.api.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# what ktor names but an android app never loads (logging of the jvm, the debug detection)
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
