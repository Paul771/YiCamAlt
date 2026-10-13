// FILE: app/src/main/java/com/tnp/model/st_PPPP_NetInfo.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: JNI binding names required by the vendor libPPPP_API.so (static Java_* exports, JNI_OnLoad lookups,
//     PPPP_Check field writes); interoperability only, no logic.
//   SCOPE: the network-info field set the vendor library knows by name (declared for lookup parity; unused in Phase 2).
//   DEPENDS: vendor libPPPP_API.so extracted at build time (M-P2P-NATIVE)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   st_PPPP_NetInfo - bFlagInternet, bFlagHostResolved, bFlagServerHello, NAT_Type, MyLanIP, MyWanIP.
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: shim created. Renaming any class, method or field breaks native lookups;
//     R8 keep rules in app/proguard-rules.pro.
// END_CHANGE_SUMMARY
package com.tnp.model;

public class st_PPPP_NetInfo {
    public byte bFlagInternet;
    public byte bFlagHostResolved;
    public byte bFlagServerHello;
    public byte NAT_Type;
    public byte[] MyLanIP = new byte[16];
    public byte[] MyWanIP = new byte[16];
}
