// FILE: ShimContractTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the JNI shim to the exact class, method and field names the vendor libPPPP_API.so binds to.
//   SCOPE: reflection over com.p2p.pppp_api.PPPP_APIs, com.tnp.model.st_PPPP_Session, com.tnp.model.st_PPPP_NetInfo.
//   DEPENDS: M-P2P-NATIVE (shim classes)
//   LINKS: V-M-P2P-NATIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

// START_MODULE_MAP
//   NATIVES - expected native name -> parameter types.
//   SESSION_FIELDS / NETINFO_FIELDS - expected public field name -> type.
//   class loads / natives / callbacks / session / netinfo tests - reflection contract.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: created with the shim.
// END_CHANGE_SUMMARY

class ShimContractTest {

    private val apis = Class.forName("com.p2p.pppp_api.PPPP_APIs")
    private val session = Class.forName("com.tnp.model.st_PPPP_Session")
    private val netInfo = Class.forName("com.tnp.model.st_PPPP_NetInfo")

    private val bytes = ByteArray::class.java
    private val ints = IntArray::class.java
    private val int = Int::class.javaPrimitiveType!!
    private val byte = Byte::class.javaPrimitiveType!!
    private val str = String::class.java

    private val natives: Map<String, List<Class<*>>> = mapOf(
        "PPPP_Initialize" to listOf(bytes, int),
        "PPPP_DeInitialize" to emptyList(),
        "PPPP_GetAPIVersion" to emptyList(),
        "PPPP_Config_Debug" to listOf(byte, int),
        "PPPP_Connect" to listOf(str, byte, int, str, str),
        "PPPP_WakeUp_And_Connect" to listOf(str, byte, int, str, str),
        "PPPP_Connect_Break" to listOf(str),
        "PPPP_Check" to listOf(int, session),
        "PPPP_Read" to listOf(int, byte, bytes, ints, int),
        "PPPP_Write" to listOf(int, byte, bytes, int),
        "PPPP_Check_Buffer" to listOf(int, byte, ints, ints),
        "PPPP_ForceClose" to listOf(int),
        "PPPP_Close" to listOf(int),
    )

    private val sessionFields: Map<String, Class<*>> = mapOf(
        "Skt" to int, "RemoteIP" to bytes, "RemotePort" to int, "MyLocalIP" to bytes, "MyLocalPort" to int,
        "MyWanIP" to bytes, "MyWanPort" to int, "ConnectTime" to int, "ConnectTimeP2P" to int,
        "ConnectTimeRelay" to int, "DID" to bytes, "bCorD" to byte, "bMode" to byte,
    )

    private val netInfoFields: Map<String, Class<*>> = mapOf(
        "bFlagInternet" to byte, "bFlagHostResolved" to byte, "bFlagServerHello" to byte, "NAT_Type" to byte,
        "MyLanIP" to bytes, "MyWanIP" to bytes,
    )

    @Test
    fun `shim classes load on the JVM without a library`() {
        // Class.forName above initialises the class; reaching here means no static loadLibrary ran.
        Class.forName("com.p2p.pppp_api.PPPP_APIs", true, javaClass.classLoader)
        assertTrue(Modifier.isPublic(apis.modifiers))
    }

    @Test
    fun `every native is public static native int with the vendor parameter types`() {
        natives.forEach { (name, params) ->
            val m = apis.getDeclaredMethod(name, *params.toTypedArray())
            assertTrue(Modifier.isPublic(m.modifiers), "$name public")
            assertTrue(Modifier.isStatic(m.modifiers), "$name static")
            assertTrue(Modifier.isNative(m.modifiers), "$name native")
            assertEquals(int, m.returnType, "$name returns int")
        }
        val declaredNatives = apis.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { it.name }.toSet()
        assertEquals(natives.keys, declaredNatives)
    }

    @Test
    fun `callbacks looked up by JNI_OnLoad exist as public void instance methods`() {
        val incoming = apis.getDeclaredMethod("onIncomingConnectionCallback")
        val r2pmp = apis.getDeclaredMethod("onR2PMPCallback", int)
        listOf(incoming, r2pmp).forEach {
            assertTrue(Modifier.isPublic(it.modifiers), "${it.name} public")
            assertTrue(!Modifier.isStatic(it.modifiers), "${it.name} instance")
            assertEquals(Void.TYPE, it.returnType, "${it.name} void")
        }
    }

    @Test
    fun `st_PPPP_Session declares exactly the 13 public fields PPPP_Check writes`() {
        val declared = session.declaredFields.filter { !it.isSynthetic }
        assertEquals(sessionFields.keys, declared.map { it.name }.toSet())
        declared.forEach {
            assertTrue(Modifier.isPublic(it.modifiers), "${it.name} public")
            assertTrue(!Modifier.isStatic(it.modifiers), "${it.name} instance")
            assertEquals(sessionFields[it.name], it.type, "${it.name} type")
        }
        val s = session.getDeclaredConstructor().newInstance()
        assertEquals(16, (session.getField("RemoteIP").get(s) as ByteArray).size)
        assertEquals(16, (session.getField("MyLocalIP").get(s) as ByteArray).size)
        assertEquals(16, (session.getField("MyWanIP").get(s) as ByteArray).size)
        assertEquals(24, (session.getField("DID").get(s) as ByteArray).size)
    }

    @Test
    fun `st_PPPP_NetInfo declares the 6 listed fields`() {
        val declared = netInfo.declaredFields.filter { !it.isSynthetic }
        assertEquals(netInfoFields.keys, declared.map { it.name }.toSet())
        declared.forEach {
            assertTrue(Modifier.isPublic(it.modifiers), "${it.name} public")
            assertEquals(netInfoFields[it.name], it.type, "${it.name} type")
        }
        val n = netInfo.getDeclaredConstructor().newInstance()
        assertEquals(16, (netInfo.getField("MyLanIP").get(n) as ByteArray).size)
        assertEquals(16, (netInfo.getField("MyWanIP").get(n) as ByteArray).size)
    }
}
