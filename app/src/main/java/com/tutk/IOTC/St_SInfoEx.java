// FILE: app/src/main/java/com/tutk/IOTC/St_SInfoEx.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Field names written by native IOTC_Session_Check_Ex; interoperability only, no logic.
//   SCOPE: the exact public field set (6 bytes, 10 ints, 3 fixed-size byte arrays).
//   DEPENDS: vendor libIOTCAPIs.so (M-P2P-NATIVE)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   St_SInfoEx - CorD, Mode, LocalNatType, RemoteNatType, RelayType, isSecure (byte); GID, IOTCVersion, NetState,
//     PID, RX_count, TX_count, VID, RemotePort, RemoteWANPort, size (int); UID[21], RemoteIP[17], RemoteWANIP[17].
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: shim created. UID and address fields never leave
//     AndroidTutkNative.check(): they are reduced to a boolean and zero-filled.
// END_CHANGE_SUMMARY
package com.tutk.IOTC;

public class St_SInfoEx {
    public byte CorD;
    public byte Mode;
    public byte LocalNatType;
    public byte RemoteNatType;
    public byte RelayType;
    public byte isSecure;
    public int GID;
    public int IOTCVersion;
    public int NetState;
    public int PID;
    public int RX_count;
    public int TX_count;
    public int VID;
    public int RemotePort;
    public int RemoteWANPort;
    public int size;
    public byte[] UID = new byte[21];
    public byte[] RemoteIP = new byte[17];
    public byte[] RemoteWANIP = new byte[17];
}
