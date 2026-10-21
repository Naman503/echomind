# sherpa-onnx and LiteRT-LM read Kotlin config objects field-by-field from JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** serializer(...); }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# Optional dependencies of the Anthropic SDK / OkHttp that are not used on Android
-dontwarn com.google.auto.value.AutoValue
# Anthropic SDK (Jackson-based models)
-keep class com.anthropic.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.github.victools.jsonschema.**
-dontwarn io.opentelemetry.**
