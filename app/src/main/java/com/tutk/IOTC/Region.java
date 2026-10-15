// FILE: app/src/main/java/com/tutk/IOTC/Region.java
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Enum class libIOTCAPIs.so FindClass-es and reads through getValue()I; interoperability only.
//   SCOPE: REGION_ALL(0), REGION_CN(1), REGION_EU(2), REGION_US(3); getValue().
//   DEPENDS: vendor libIOTCAPIs.so (M-P2P-NATIVE)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_MODULE_MAP
//   Region - master region enum; getValue() returns the numeric region id.
// END_MODULE_MAP
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: shim created; declared so FindClass never fails. Unused in Phase 2.
// END_CHANGE_SUMMARY
package com.tutk.IOTC;

public enum Region {
    REGION_ALL(0),
    REGION_CN(1),
    REGION_EU(2),
    REGION_US(3);

    private final int value;

    Region(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
