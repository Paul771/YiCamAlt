// FILE: app/src/main/java/com/tutk/IOTC/IOTCAPIs.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: JNI binding names required by the vendor libIOTCAPIs.so (static Java_com_tutk_IOTC_IOTCAPIs_* exports);
//     interoperability only, no logic.
//   SCOPE: the 7 IOTC natives of the vendor client call order (init, version, session id, parallel connect, stop,
//     session check, session close), declared public static native with the vendor parameter types.
//   DEPENDS: vendor libIOTCAPIs.so extracted at build time (M-P2P-NATIVE)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK, M-P2P-NATIVE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   IOTCAPIs - static natives: IOTC_Initialize2, IOTC_Get_Version, IOTC_Get_SessionID, IOTC_Connect_ByUID_Parallel,
//     IOTC_Connect_Stop_BySID, IOTC_Session_Check_Ex, IOTC_Session_Close.
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: shim created. Renaming any class or method breaks native binding;
//     R8 keep rule com.tutk.IOTC.** in app/proguard-rules.pro. No library is loaded here: NativeLibs.loadTutk owns
//     loading. IOTC_DeInitialize is intentionally not declared (never called).
// END_CHANGE_SUMMARY
package com.tutk.IOTC;

public final class IOTCAPIs {

    private IOTCAPIs() {
    }

    public static native int IOTC_Initialize2(int udpPort);

    public static native void IOTC_Get_Version(int[] version);

    public static native int IOTC_Get_SessionID();

    public static native int IOTC_Connect_ByUID_Parallel(String uid, int sid);

    public static native int IOTC_Connect_Stop_BySID(int sid);

    public static native int IOTC_Session_Check_Ex(int sid, St_SInfoEx info);

    public static native void IOTC_Session_Close(int sid);
}
