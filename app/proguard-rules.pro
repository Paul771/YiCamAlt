# Add project specific ProGuard rules here.
-keep class com.yicamalt.** { *; }
-keepclassmembers,allowobfuscation class * {
  @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class **$$serializer { *; }