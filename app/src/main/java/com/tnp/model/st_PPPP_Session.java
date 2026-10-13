// FILE: app/src/main/java/com/tnp/model/st_PPPP_Session.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: JNI binding names required by the vendor libPPPP_API.so (static Java_* exports, JNI_OnLoad lookups,
//     PPPP_Check field writes); interoperability only, no logic.
//   SCOPE: the exact field set native PPPP_Check writes by name.
//   DEPENDS: vendor libPPPP_API.so extracted at build time (M-P2P-NATIVE)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   st_PPPP_Session - Skt, RemoteIP, RemotePort, MyLocalIP, MyLocalPort, MyWanIP, MyWanPort, ConnectTime,
//     ConnectTimeP2P, ConnectTimeRelay, DID, bCorD, bMode.
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: shim created. Renaming any class, method or field breaks native lookups;
//     R8 keep rules in app/proguard-rules.pro. Address and DID fields never leave AndroidPpppNative.check().
// END_CHANGE_SUMMARY
package com.tnp.model;

public class st_PPPP_Session {
    public int Skt;
    public byte[] RemoteIP = new byte[16];
    public int RemotePort;
    public byte[] MyLocalIP = new byte[16];
    public int MyLocalPort;
    public byte[] MyWanIP = new byte[16];
    public int MyWanPort;
    public int ConnectTime;
    public int ConnectTimeP2P;
    public int ConnectTimeRelay;
    public byte[] DID = new byte[24];
    public byte bCorD;
    public byte bMode;
}
