// FILE: TutkShimContractTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the TUTK JNI shim to the exact class, method, field and callback names the vendor libIOTCAPIs.so /
//     libAVAPIs.so bind to or FindClass.
//   SCOPE: reflection over com.tutk.IOTC.{IOTCAPIs, AVAPIs, AVAPIs$av*Fn, St_SInfoEx, Region, st_LanSearchInfo,
//     st_LanSearchInfo2, st_SearchDeviceInfo, St_AvIdentity}.
//   DEPENDS: M-TUTK-LINK (shim classes)
//   LINKS: V-M-TUTK-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// START_MODULE_MAP
//   SO_CLASS_NAMES - every com/tutk/IOTC/ name found in the strings of libIOTCAPIs.so and libAVAPIs.so.
//   CALLBACK_SIGNATURES - interface -> method name + JNI descriptor as found in libAVAPIs.so strings.
//   IOTC_NATIVES / AV_NATIVES - native name -> (return type, parameter types) from the vendor binding.
//   jniDescriptor(method) - JNI method descriptor of a reflected method.
//   class loads / natives / callbacks / struct field tests - reflection contract.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: created with the TUTK shim.
// END_CHANGE_SUMMARY

class TutkShimContractTest {

    private val pkg = "com.tutk.IOTC"
    private val bytes = ByteArray::class.java
    private val ints = IntArray::class.java
    private val int = Int::class.javaPrimitiveType!!
    private val byte = Byte::class.javaPrimitiveType!!
    private val void = Void.TYPE
    private val str = String::class.java

    private val soClassNames = listOf(
        "com/tutk/IOTC/Region",
        "com/tutk/IOTC/st_LanSearchInfo",
        "com/tutk/IOTC/st_LanSearchInfo2",
        "com/tutk/IOTC/st_SearchDeviceInfo",
        "com/tutk/IOTC/St_AvIdentity",
        "com/tutk/IOTC/AVAPIs\$avAbilityRequestFn",
        "com/tutk/IOTC/AVAPIs\$avChangePasswordRequestFn",
        "com/tutk/IOTC/AVAPIs\$avIdentityArrayRequestFn",
        "com/tutk/IOTC/AVAPIs\$avPasswordAuthFn",
        "com/tutk/IOTC/AVAPIs\$avTokenAuthFn",
        "com/tutk/IOTC/AVAPIs\$avTokenDeleteFn",
        "com/tutk/IOTC/AVAPIs\$avTokenRequestFn",
    )

    private val callbackSignatures: Map<String, Pair<String, String>> = mapOf(
        "avAbilityRequestFn" to ("ability_request" to "(I[Ljava/lang/String;)V"),
        "avChangePasswordRequestFn" to ("change_password_request" to
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I"),
        "avIdentityArrayRequestFn" to ("identity_array_request" to "(ILjava/util/ArrayList;[I)V"),
        "avPasswordAuthFn" to ("password_auth" to "(Ljava/lang/String;[Ljava/lang/String;)I"),
        "avTokenAuthFn" to ("token_auth" to "(Ljava/lang/String;[Ljava/lang/String;)I"),
        "avTokenDeleteFn" to ("token_delete" to "(ILjava/lang/String;)I"),
        "avTokenRequestFn" to ("token_request" to "(ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;)I"),
    )

    private val sInfoEx = Class.forName("$pkg.St_SInfoEx")

    private val iotcNatives: Map<String, Pair<Class<*>, List<Class<*>>>> = mapOf(
        "IOTC_Initialize2" to (int to listOf(int)),
        "IOTC_Get_Version" to (void to listOf(ints)),
        "IOTC_Get_SessionID" to (int to emptyList()),
        "IOTC_Connect_ByUID_Parallel" to (int to listOf(str, int)),
        "IOTC_Connect_Stop_BySID" to (int to listOf(int)),
        "IOTC_Session_Check_Ex" to (int to listOf(int, sInfoEx)),
        "IOTC_Session_Close" to (void to listOf(int)),
    )

    private val avNatives: Map<String, Pair<Class<*>, List<Class<*>>>> = mapOf(
        "avInitialize" to (int to listOf(int)),
        "avGetAVApiVer" to (int to emptyList()),
        "avClientStart2" to (int to listOf(int, str, str, int, ints, int, ints)),
        "avSendIOCtrl" to (int to listOf(int, int, bytes, int)),
        "avRecvFrameData2" to (int to listOf(int, bytes, int, ints, ints, bytes, int, ints, ints)),
        "avSendIOCtrlExit" to (int to listOf(int)),
        "avClientStop" to (void to listOf(int)),
        "avClientExit" to (void to listOf(int, int)),
    )

    private fun jniType(c: Class<*>): String = when {
        c == Int::class.javaPrimitiveType -> "I"
        c == Byte::class.javaPrimitiveType -> "B"
        c == Void.TYPE -> "V"
        c.isArray -> "[" + jniType(c.componentType)
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    private fun jniDescriptor(m: Method): String =
        m.parameterTypes.joinToString("", "(", ")") { jniType(it) } + jniType(m.returnType)

    @Test
    fun `every class name found in the TUTK library strings resolves and initialises without a library`() {
        soClassNames.forEach { name ->
            val c = Class.forName(name.replace('/', '.'), true, javaClass.classLoader)
            assertTrue(Modifier.isPublic(c.modifiers), "$name public")
        }
        // Initialising the native holders proves no static block calls loadLibrary (it would throw on the JVM).
        Class.forName("$pkg.IOTCAPIs", true, javaClass.classLoader)
        Class.forName("$pkg.AVAPIs", true, javaClass.classLoader)
    }

    @Test
    fun `IOTC and AV natives are public static native with the vendor signatures and nothing else`() {
        listOf("$pkg.IOTCAPIs" to iotcNatives, "$pkg.AVAPIs" to avNatives).forEach { (cls, natives) ->
            val c = Class.forName(cls)
            natives.forEach { (name, sig) ->
                val m = c.getDeclaredMethod(name, *sig.second.toTypedArray())
                assertTrue(Modifier.isPublic(m.modifiers), "$name public")
                assertTrue(Modifier.isStatic(m.modifiers), "$name static")
                assertTrue(Modifier.isNative(m.modifiers), "$name native")
                assertEquals(sig.first, m.returnType, "$name return type")
            }
            val declared = c.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { it.name }.toSet()
            assertEquals(natives.keys, declared, "$cls native set")
        }
    }

    @Test
    fun `AVAPIs nested callback interfaces carry the method names and JNI descriptors from the library`() {
        callbackSignatures.forEach { (iface, sig) ->
            val c = Class.forName("$pkg.AVAPIs\$$iface")
            assertTrue(c.isInterface, "$iface interface")
            val methods = c.declaredMethods.filter { !it.isSynthetic }
            assertEquals(1, methods.size, "$iface has one method")
            assertEquals(sig.first, methods[0].name, "$iface method name")
            assertEquals(sig.second, jniDescriptor(methods[0]), "$iface descriptor")
        }
    }

    @Test
    fun `St_SInfoEx declares the exact field set with fixed array sizes`() {
        val expected: Map<String, Class<*>> = mapOf(
            "CorD" to byte, "Mode" to byte, "LocalNatType" to byte, "RemoteNatType" to byte, "RelayType" to byte,
            "isSecure" to byte, "GID" to int, "IOTCVersion" to int, "NetState" to int, "PID" to int,
            "RX_count" to int, "TX_count" to int, "VID" to int, "RemotePort" to int, "RemoteWANPort" to int,
            "size" to int, "UID" to bytes, "RemoteIP" to bytes, "RemoteWANIP" to bytes,
        )
        assertFields(sInfoEx, expected)
        val s = sInfoEx.getDeclaredConstructor().newInstance()
        assertEquals(21, (sInfoEx.getField("UID").get(s) as ByteArray).size)
        assertEquals(17, (sInfoEx.getField("RemoteIP").get(s) as ByteArray).size)
        assertEquals(17, (sInfoEx.getField("RemoteWANIP").get(s) as ByteArray).size)
    }

    @Test
    fun `search and identity structs declare the fields the libraries read and write`() {
        assertFields(Class.forName("$pkg.st_LanSearchInfo"), mapOf("UID" to bytes, "IP" to bytes, "port" to int))
        assertFields(
            Class.forName("$pkg.st_LanSearchInfo2"),
            mapOf("UID" to bytes, "IP" to bytes, "port" to int, "DeviceName" to bytes),
        )
        assertFields(
            Class.forName("$pkg.st_SearchDeviceInfo"),
            mapOf(
                "Reserved" to byte, "UID" to bytes, "IP" to bytes, "port" to int, "DeviceName" to bytes,
                "nNew" to int,
            ),
        )
        assertFields(Class.forName("$pkg.St_AvIdentity"), mapOf("identity" to str, "length" to int))
        listOf("st_LanSearchInfo", "st_LanSearchInfo2", "st_SearchDeviceInfo", "St_AvIdentity").forEach {
            val ctor = Class.forName("$pkg.$it").getDeclaredConstructor()
            assertTrue(Modifier.isPublic(ctor.modifiers), "$it public no-arg constructor (NewObject ()V)")
        }
    }

    @Test
    fun `Region is an enum with the four vendor constants and getValue()I`() {
        val c = Class.forName("$pkg.Region")
        assertTrue(c.isEnum)
        val getValue = c.getDeclaredMethod("getValue")
        assertEquals("()I", jniDescriptor(getValue))
        val values = c.enumConstants.associate { (it as Enum<*>).name to getValue.invoke(it) as Int }
        assertEquals(mapOf("REGION_ALL" to 0, "REGION_CN" to 1, "REGION_EU" to 2, "REGION_US" to 3), values)
    }

    private fun assertFields(c: Class<*>, expected: Map<String, Class<*>>) {
        val declared = c.declaredFields.filter { !it.isSynthetic }
        assertEquals(expected.keys, declared.map { it.name }.toSet(), "${c.simpleName} field set")
        declared.forEach {
            assertTrue(Modifier.isPublic(it.modifiers), "${c.simpleName}.${it.name} public")
            assertTrue(!Modifier.isStatic(it.modifiers), "${c.simpleName}.${it.name} instance")
            assertEquals(expected[it.name], it.type, "${c.simpleName}.${it.name} type")
        }
    }
}
