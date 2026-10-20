// FILE: DeviceListFixtures.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Synthetic device-list and tnp device_info responses in the shape measured by the probe.
//   SCOPE: JSON strings only, assembled from RedactionFixtures; field NAMES come from
//     01-PROBE-RESULTS.md, every VALUE is invented (SYNTH).
//   DEPENDS: RedactionFixtures
//   LINKS: V-M-CAMERA-LIST, M-CAMERA-LIST, M-P2P-CREDS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.test.RedactionFixtures as F

// START_MODULE_MAP
//   DeviceListFixtures.V4_LIST - two owned rows: TNP row (ipcParam object) and TUTK row (ipcParam JSON string).
//   DeviceListFixtures.SHARED_LIST - one row with category 1 and one row with no ownership field.
//   DeviceListFixtures.WRAPPED_LIST / ENCODED_DATA_LIST - the same rows inside an object / a JSON-encoded string.
//   DeviceListFixtures.EMPTY_20200 - envelope without data (no devices on this server).
//   DeviceListFixtures.TNP_INFO - DID, InitString, License.
//   DeviceListFixtures.TNP_INFO_NOT_TNP - success answer whose DID has no TNP prefix (uid rule decides).
//   DeviceListFixtures.TNP_INFO_REJECTED - non-success code 20253.
//   DeviceListFixtures.TNP_INFO_INCOMPLETE - TNP DID without InitString / License.
//   DeviceListFixtures.V4_LIST_TYPES_SWAPPED - V4_LIST with the list `type` values of the two rows swapped.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - plan 02-01: device_info variants for the vendor transport rule; type-swapped list.
//   PREV_CHANGE: v0.1.0 - Created (plan 01-07).
// END_CHANGE_SUMMARY

internal object DeviceListFixtures {
    const val NAME_1 = "Living Room SYNTH"
    const val NAME_2 = "Front Door SYNTH"
    const val MODEL = "yunyi.camera.SYNTH"

    /** TNP row: every field name the probe saw, ipcParam as an OBJECT, plus unknown extra fields. */
    private val ROW_TNP = """{"activeTime":0,"appParam":"","category":0,"count":0,"did":"${F.DID}",""" +
        """"flag":false,"hasPincode":false,"interVersion":"SYNTH","ipcParam":{"ip":"SYNTH","mac":"SYNTH",""" +
        """"p2p_encrypt":"true","signal_quality":"80","ssid":"SYNTH"},"isNew":false,"message":"",""" +
        """"model":"$MODEL","name":"$NAME_1","nickname":"","online":true,"onlineTime":{"time":0},""" +
        """"password":"${F.PASSWORD_HEX}","relayFlag":0,"sdState":0,"share":false,"state":1,"type":2,""" +
        """"uid":"${F.CLOUD_UID}","webPairStatus":0,"foo":{"bar":[1,2]},"newField":123}"""

    /** TUTK row: no did, empty password, type as a string, ipcParam as a JSON-encoded STRING. */
    private val ROW_TUTK = """{"category":0,"hasPincode":"true","ipcParam":"{\"p2p_encrypt\":\"false\",\"wakeup\":1}",""" +
        """"model":"$MODEL","name":"$NAME_2","online":false,"password":"","share":false,"type":"0",""" +
        """"uid":"${F.TUTK_UID}"}"""

    private val ROWS = "[$ROW_TNP,$ROW_TUTK,{\"name\":\"No Id SYNTH\",\"online\":true}]"

    val V4_LIST = """{"code":"20000","data":$ROWS}"""

    val WRAPPED_LIST = """{"code":"20000","data":{"device_list":$ROWS}}"""

    val ENCODED_DATA_LIST = """{"code":"20000","data":"${ROWS.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""

    val SHARED_LIST = """{"code":"20000","data":[""" +
        """{"category":1,"share":false,"did":"${F.DID}","name":"$NAME_1","nickname":"Owner SYNTH","type":2,"uid":"${F.CLOUD_UID}"},""" +
        """{"name":"$NAME_2","type":0,"uid":"${F.TUTK_UID}"}]}"""

    const val EMPTY_20200 = """{"code":"20200"}"""

    val V4_LIST_TYPES_SWAPPED = V4_LIST
        .replace(""""type":2,""", """"type":"SYNTHSWAP",""")
        .replace(""""type":"0",""", """"type":2,""")
        .replace(""""type":"SYNTHSWAP",""", """"type":"0",""")

    val TNP_INFO_NOT_TNP = """{"code":"20000","data":{"DID":"SYNTHNOTTNPDID","InitString":"${F.INIT_STRING}","License":"${F.LICENSE}"}}"""

    const val TNP_INFO_REJECTED = """{"code":"20253"}"""

    val TNP_INFO_INCOMPLETE = """{"code":"20000","data":{"DID":"${F.DID}","InitString":""}}"""

    val TNP_INFO = """{"code":"20000","data":{"DID":"${F.DID}","InitString":"${F.INIT_STRING}","License":"${F.LICENSE}"}}"""
}
