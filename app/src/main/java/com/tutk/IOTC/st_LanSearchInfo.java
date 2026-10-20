// FILE: app/src/main/java/com/tutk/IOTC/st_LanSearchInfo.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Class libIOTCAPIs.so FindClass-es and fills by field name (LAN search); interoperability only.
//   SCOPE: UID, IP, port; public no-arg constructor (native NewObject with ()V).
//   DEPENDS: vendor libIOTCAPIs.so (M-P2P-NATIVE)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   st_LanSearchInfo - UID (byte[]), IP (byte[]), port (int).
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: shim created; declared so FindClass never fails. Unused in Phase 2.
// END_CHANGE_SUMMARY
package com.tutk.IOTC;

public class st_LanSearchInfo {
    public byte[] UID = null;
    public byte[] IP = null;
    public int port = 0;
}
