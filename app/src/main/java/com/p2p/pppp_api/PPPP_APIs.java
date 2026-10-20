// FILE: app/src/main/java/com/p2p/pppp_api/PPPP_APIs.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: JNI binding names required by the vendor libPPPP_API.so (static Java_* exports, JNI_OnLoad lookups,
//     PPPP_Check field writes); interoperability only, no logic.
//   SCOPE: 13 public static native PPPP_* declarations and the two instance callbacks JNI_OnLoad looks up.
//   DEPENDS: vendor libPPPP_API.so extracted at build time (M-P2P-NATIVE)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   PPPP_APIs - static natives: Initialize, DeInitialize, GetAPIVersion, Config_Debug, Connect,
//     WakeUp_And_Connect, Connect_Break, Check, Read, Write, Check_Buffer, ForceClose, Close.
//   onIncomingConnectionCallback / onR2PMPCallback - callbacks looked up by JNI_OnLoad.
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: shim created. Renaming any class, method or field breaks native lookups;
//     R8 keep rules in app/proguard-rules.pro. No library is loaded here: NativeLibs owns loading.
//     PPPP_DeInitialize is declared for name parity only and is never called (PpppRuntime contract).
// END_CHANGE_SUMMARY
package com.p2p.pppp_api;

import com.tnp.model.st_PPPP_Session;

public final class PPPP_APIs {

    private PPPP_APIs() {
    }

    public static native int PPPP_Initialize(byte[] initString, int maxSessions);

    public static native int PPPP_DeInitialize();

    public static native int PPPP_GetAPIVersion();

    public static native int PPPP_Config_Debug(byte enable, int level);

    public static native int PPPP_Connect(String did, byte flag, int udpPort, String serverString, String licenseDeviceKey);

    public static native int PPPP_WakeUp_And_Connect(String did, byte flag, int udpPort, String serverString, String licenseDeviceKey);

    public static native int PPPP_Connect_Break(String did);

    public static native int PPPP_Check(int handle, st_PPPP_Session out);

    public static native int PPPP_Read(int handle, byte channel, byte[] buf, int[] inOutSize, int timeoutMs);

    public static native int PPPP_Write(int handle, byte channel, byte[] buf, int size);

    public static native int PPPP_Check_Buffer(int handle, byte channel, int[] writeSize, int[] readSize);

    public static native int PPPP_ForceClose(int handle);

    public static native int PPPP_Close(int handle);

    public void onIncomingConnectionCallback() {
        // looked up by JNI_OnLoad; unused in Phase 2
    }

    public void onR2PMPCallback(int value) {
        // looked up by JNI_OnLoad; unused in Phase 2
    }
}
