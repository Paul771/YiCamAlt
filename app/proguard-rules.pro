# Add project specific ProGuard rules here.
-keep class com.yicamalt.** { *; }
-keepclassmembers,allowobfuscation class * {
  @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class **$$serializer { *; }
# JNI shim for the vendor libraries (GSD Phase 2, M-P2P-NATIVE). libPPPP_API.so JNI_OnLoad looks up
# com/p2p/pppp_api/PPPP_APIs and its callbacks; PPPP_Check writes st_PPPP_Session fields by name.
-keep class com.p2p.pppp_api.** { *; }
-keep class com.tnp.model.** { *; }
-keep class com.tutk.IOTC.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
