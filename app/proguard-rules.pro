# Plugins are loaded outside the APK and call these APIs by their original names.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class dev.lynx.streamhub.api.** { *; }
-keep class com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.nicehttp.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# Optional platform integrations absent on Android (not plugin host APIs).
-dontwarn java.lang.instrument.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.impl.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Runtime helpers may be referenced only by external provider bytecode.
-keep class kotlinx.serialization.** { *; }
-keep class org.mozilla.javascript.** { *; }

# SH_BATCH08_R8_ANDROID_OPTIONALS
# Optional desktop integrations in retained Rhino/Jackson classes; absent on Android.
# These rules do not implement these APIs or make desktop scripting paths usable.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# Nullness metadata referenced by jsoup, not an Android runtime API.
-dontwarn org.jspecify.annotations.**
