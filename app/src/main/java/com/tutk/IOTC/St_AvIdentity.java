// FILE: app/src/main/java/com/tutk/IOTC/St_AvIdentity.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Class libAVAPIs.so FindClass-es and reads by field name (identity array); interoperability only.
//   SCOPE: identity, length; public no-arg constructor.
//   DEPENDS: vendor libAVAPIs.so (M-P2P-NATIVE)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   St_AvIdentity - identity (String), length (int).
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: shim created; declared so FindClass never fails. Unused in Phase 2.
// END_CHANGE_SUMMARY
package com.tutk.IOTC;

public class St_AvIdentity {
    public String identity = null;
    public int length;
}
