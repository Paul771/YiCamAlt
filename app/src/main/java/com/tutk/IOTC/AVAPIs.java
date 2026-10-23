// FILE: app/src/main/java/com/tutk/IOTC/AVAPIs.java
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: JNI binding names required by the vendor libAVAPIs.so (static Java_com_tutk_IOTC_AVAPIs_* exports and
//     the nested callback interfaces it FindClass-es); interoperability only, no logic.
//   SCOPE: the 11 AV natives of the vendor client call order (incl. audio + IOCTRL readers); the seven AVAPIs$av*Fn interfaces with the method
//     names and JNI signatures found in the library strings.
//   DEPENDS: vendor libAVAPIs.so extracted at build time (M-P2P-NATIVE)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK, M-P2P-NATIVE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   AVAPIs - static natives: avInitialize, avGetAVApiVer, avClientStart2, avSendIOCtrl, avRecvFrameData2,
//     avSendIOCtrlExit, avClientStop, avClientExit, avClientCleanAudioBuf, avRecvAudioData, avRecvIOCtrl.
//   avAbilityRequestFn, avChangePasswordRequestFn, avIdentityArrayRequestFn, avPasswordAuthFn, avTokenAuthFn,
//     avTokenDeleteFn, avTokenRequestFn - nested interfaces looked up by name; never implemented in Phase 2.
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 03-03: avClientCleanAudioBuf, avRecvAudioData, avRecvIOCtrl (audio + IOCTRL
//     readers); exports verified in arm64-v8a and armeabi-v7a libAVAPIs.so.
//   PREV_CHANGE: v0.1.0 - GSD plan 02-10: shim created. Renaming any class, method or interface breaks native
//     lookups; R8 keep rule com.tutk.IOTC.** in app/proguard-rules.pro. No library is loaded here.
//     avDeInitialize is intentionally not declared (never called).
// END_CHANGE_SUMMARY
package com.tutk.IOTC;

import java.util.ArrayList;

public final class AVAPIs {

    private AVAPIs() {
    }

    public static native int avInitialize(int maxChannels);

    public static native int avGetAVApiVer();

    public static native int avClientStart2(int sid, String account, String password, int timeoutSec,
                                            int[] servType, int channel, int[] resend);

    public static native int avSendIOCtrl(int av, int type, byte[] payload, int size);

    public static native int avRecvFrameData2(int av, byte[] buf, int bufSize, int[] actualFrameSize,
                                              int[] expectedFrameSize, byte[] frameInfo, int frameInfoSize,
                                              int[] actualInfoSize, int[] frameIndex);

    public static native int avSendIOCtrlExit(int av);

    public static native void avClientStop(int av);

    public static native void avClientExit(int sid, int channel);

    public static native int avClientCleanAudioBuf(int av);

    public static native int avRecvAudioData(int av, byte[] buf, int bufSize, byte[] frameInfo, int infoSize, int[] frameIndex);

    public static native int avRecvIOCtrl(int av, int[] ioType, byte[] buf, int bufSize, int timeoutMs);

    public interface avAbilityRequestFn {
        void ability_request(int av, String[] ability);
    }

    public interface avChangePasswordRequestFn {
        int change_password_request(int av, String account, String oldPassword, String newPassword, String newIdentity);
    }

    public interface avIdentityArrayRequestFn {
        void identity_array_request(int av, ArrayList<St_AvIdentity> identities, int[] count);
    }

    public interface avPasswordAuthFn {
        int password_auth(String account, String[] password);
    }

    public interface avTokenAuthFn {
        int token_auth(String identity, String[] token);
    }

    public interface avTokenDeleteFn {
        int token_delete(int av, String identity);
    }

    public interface avTokenRequestFn {
        int token_request(int av, String identity, String clientName, String[] token);
    }
}
