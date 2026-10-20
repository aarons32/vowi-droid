package com.vowi.vowidroid.core.entitlement

import com.google.gson.JsonParser
import com.vowi.vowidroid.core.ike.EapAka
import com.vowi.vowidroid.core.ike.EapPacket
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/** The TS.43 client against a scripted entitlement server that checks what the SIM signed. */
class Ts43ClientTest {
    private val imsi = "310260123456789"
    private val identity = Ts43.eapIdentity(imsi, "310", "260")
    private val ik = ByteArray(16) { (it + 1).toByte() }
    private val ck = ByteArray(16) { (0x10 + it).toByte() }
    private val res = "0102030405060708".hexToBytes()
    private val rand = ByteArray(16) { (0x40 + it).toByte() }
    private val autn = ByteArray(16) { (0x60 + it).toByte() }
    private val kAut = EapAka.deriveKeys(identity.toByteArray(Charsets.US_ASCII), ik, ck).kAut

    private val entitlementXml = """<?xml version="1.0" encoding="UTF-8"?>
        <wap-provisioningdoc version="1.1">
          <characteristic type="VERS"><parm name="version" value="1"/><parm name="validity" value="1728000"/></characteristic>
          <characteristic type="TOKEN"><parm name="token" value="tok-1"/><parm name="validity" value="3600"/></characteristic>
          <characteristic type="APPLICATION">
            <parm name="AppID" value="ap2004"/>
            <parm name="Name" value="WiFi Calling"/>
            <parm name="EntitlementStatus" value="0"/>
            <parm name="AddrStatus" value="0"/>
            <parm name="TC_Status" value="2"/>
            <parm name="ProvStatus" value="2"/>
            <parm name="ServiceFlow_URL" value="https://carrier.example/e911?lang=en"/>
            <parm name="ServiceFlow_UserData" value="token=xyz&imsi=hidden"/>
          </characteristic>
        </wap-provisioningdoc>""".trimIndent()

    private class Server(var script: (HttpCall) -> HttpReply) : HttpTransport {
        val calls = ArrayList<HttpCall>()
        override fun exchange(call: HttpCall): HttpReply {
            calls += call
            return script(call)
        }
    }

    private fun sim(vararg results: AkaResult): (AkaChallenge) -> AkaResult {
        val queue = results.toMutableList()
        return { challenge ->
            assertArrayEquals(rand, challenge.rand)
            assertArrayEquals(autn, challenge.autn)
            queue.removeAt(0)
        }
    }

    private fun request(token: String? = null) = EntitlementRequest(
        serverUrl = "https://entitlement.example/ts43",
        terminalId = "abc-123",
        terminalVendor = "Xiaomi",
        terminalModel = "2509FPN0BC-long",
        terminalSoftwareVersion = "16",
        appVersion = "0.1.0",
        token = token,
    )

    /** EAP-Request/AKA-Challenge with RAND, AUTN and the server's MAC. */
    private fun challenge(identifier: Int, validMac: Boolean = true): ByteArray {
        val data = concat(
            byteArrayOf(EapAka.SUBTYPE_CHALLENGE.toByte(), 0, 0),
            EapAka.marshalAttribute(EapAka.AT_RAND, concat(ByteArray(2), rand)),
            EapAka.marshalAttribute(EapAka.AT_AUTN, concat(ByteArray(2), autn)),
            EapAka.marshalAttribute(EapAka.AT_MAC, ByteArray(18)),
        )
        val packet = EapAka.marshalPacket(EapPacket(EapAka.CODE_REQUEST, identifier, EapAka.TYPE_AKA, data))
        val mac = EapAka.mac(kAut, packet)
        if (!validMac) mac[0] = (mac[0].toInt() xor 0xff).toByte()
        System.arraycopy(mac, 0, packet, packet.size - 16, 16)
        return packet
    }

    private fun challengeReply(identifier: Int, validMac: Boolean = true) = HttpReply(
        200,
        mapOf(
            "Content-Type" to listOf("application/vnd.gsma.eap-relay.v1.0+json"),
            "Set-Cookie" to listOf("JSESSIONID=abc; Path=/; HttpOnly", "other=1"),
        ),
        """{"eap-relay-packet":"${Base64.getEncoder().encodeToString(challenge(identifier, validMac))}"}""".toByteArray(),
    )

    private fun xmlReply(xml: String = entitlementXml) = HttpReply(200, mapOf("Content-Type" to listOf("text/vnd.wap.connectivity-xml; charset=utf-8")), xml.toByteArray())

    private fun query(call: HttpCall): Map<String, String> =
        call.url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    /** The EAP packet a POST carries. */
    private fun posted(call: HttpCall): ByteArray {
        val json = JsonParser.parseString(String(call.body!!, Charsets.UTF_8)).asJsonObject
        return Base64.getDecoder().decode(json.get("eap-relay-packet").asString)
    }

    /** Checks the SIM's answer as the server would, and returns its MAC-less layout. */
    private fun verifyChallengeResponse(packet: ByteArray, identifier: Int): String {
        val mac = packet.copyOfRange(packet.size - 16, packet.size)
        val zeroed = packet.copyOf().also { for (index in it.size - 16 until it.size) it[index] = 0 }
        assertArrayEquals("the response MAC", EapAka.mac(kAut, zeroed), mac)
        assertEquals(identifier, packet[1].toInt() and 0xff)
        return zeroed.toHex()
    }

    @Test
    fun aFullSignInAnswersTheChallengeAndReadsTheStatus() {
        val server = Server { call ->
            when (call.method) {
                "GET" -> challengeReply(identifier = 7)
                else -> xmlReply()
            }
        }
        val client = Ts43Client(server, identity, imsi, sim(AkaResult(res = res, ck = ck, ik = ik)))

        val status = client.queryVowifi(request())

        assertEquals(2, server.calls.size)
        val get = server.calls[0]
        val parameters = query(get)
        assertEquals("https://entitlement.example/ts43", get.url.substringBefore('?'))
        assertEquals("0310260123456789@nai.epc.mnc260.mcc310.3gppnetwork.org", parameters["EAP_ID"])
        assertNull(parameters["token"])
        assertEquals("abc-123", parameters["terminal_id"])
        assertEquals("ap2004", parameters["app"])
        assertEquals("Xiao", parameters["terminal_vendor"])
        assertEquals("2509FPN0BC", parameters["terminal_model"])
        assertEquals("16", parameters["terminal_sw_version"])
        assertEquals("0", parameters["vers"])
        assertEquals("2.0", parameters["entitlement_version"])
        assertEquals("application/vnd.gsma.eap-relay.v1.0+json", get.header("Accept"))
        assertEquals("PRD-TS43 term-Xiao/2509FPN0BC client-IMS-Entitlement/0.1.0 OS-Android/16", get.header("User-Agent"))

        val post = server.calls[1]
        assertEquals("https://entitlement.example/ts43", post.url)
        assertEquals("application/vnd.gsma.eap-relay.v1.0+json", post.header("Content-Type"))
        assertEquals("application/vnd.gsma.eap-relay.v1.0+json, text/vnd.wap.connectivity-xml", post.header("Accept"))
        assertEquals("JSESSIONID=abc; other=1", post.header("Cookie"))
        // Code 2, identifier 7, 40 bytes, EAP-AKA, Challenge, AT_RES (64 bits), AT_MAC, as Android lays it out.
        val expected = "02070028170100000303004001020304050607080b050000" + "00".repeat(16)
        assertEquals(expected, verifyChallengeResponse(posted(post), identifier = 7))

        assertEquals(Ts43.ENTITLEMENT_DISABLED, status.entitlementStatus)
        assertEquals(Ts43.STATUS_NOT_AVAILABLE, status.addrStatus)
        assertEquals(Ts43.STATUS_NOT_REQUIRED, status.tcStatus)
        assertEquals(Ts43.PROVISIONING_NOT_REQUIRED, status.provStatus)
        assertEquals("https://carrier.example/e911?lang=en", status.serviceFlowUrl)
        assertEquals("token=xyz&imsi=hidden", status.serviceFlowUserData)
        assertEquals("tok-1", status.token)
        assertEquals(3600, status.tokenValiditySeconds)
        assertEquals(1, status.version)
        assertTrue(status.serverDataMissing)
        assertFalse(status.entitled)
        assertFalse(status.addressOnFile)
    }

    @Test
    fun aTokenSkipsTheSignIn() {
        val server = Server { xmlReply(entitlementXml.replace("\"EntitlementStatus\" value=\"0\"", "\"EntitlementStatus\" value=\"1\"").replace("\"AddrStatus\" value=\"0\"", "\"AddrStatus\" value=\"1\"")) }
        val client = Ts43Client(server, identity, imsi) { fail("the SIM must not be asked"); throw AssertionError() }

        val status = client.queryVowifi(request(token = "tok-1"))

        assertEquals(1, server.calls.size)
        val parameters = query(server.calls[0])
        assertEquals(imsi, parameters["IMSI"])
        assertEquals("tok-1", parameters["token"])
        assertNull(parameters["EAP_ID"])
        assertEquals("text/vnd.wap.connectivity-xml", server.calls[0].header("Accept"))
        assertTrue(status.entitled)
        assertTrue(status.addressOnFile)
    }

    @Test
    fun anExpiredTokenFallsBackToAFullSignIn() {
        val server = Server { call ->
            when {
                call.url.contains("token=") -> HttpReply(511, emptyMap(), ByteArray(0))
                call.method == "GET" -> challengeReply(identifier = 3)
                else -> xmlReply()
            }
        }
        val client = Ts43Client(server, identity, imsi, sim(AkaResult(res = res, ck = ck, ik = ik)))

        client.queryVowifi(request(token = "stale"))

        assertEquals(3, server.calls.size)
        assertTrue(query(server.calls[1]).containsKey("EAP_ID"))
        verifyChallengeResponse(posted(server.calls[2]), identifier = 3)
    }

    @Test
    fun aSynchronizationFailureGetsANewChallenge() {
        val auts = ByteArray(14) { (0x70 + it).toByte() }
        var posts = 0
        val server = Server { call ->
            when {
                call.method == "GET" -> challengeReply(identifier = 1)
                ++posts == 1 -> {
                    val packet = posted(call)
                    // Code 2, identifier 1, 20 bytes, EAP-AKA, Synchronization-Failure, AT_AUTS.
                    assertEquals("02010018170400000404" + auts.toHex(), packet.toHex())
                    assertEquals("application/vnd.gsma.eap-relay.v1.0+json", call.header("Accept"))
                    challengeReply(identifier = 2)
                }
                else -> xmlReply()
            }
        }
        val client = Ts43Client(server, identity, imsi, sim(AkaResult(auts = auts), AkaResult(res = res, ck = ck, ik = ik)))

        val status = client.queryVowifi(request())

        assertEquals(3, server.calls.size)
        verifyChallengeResponse(posted(server.calls[2]), identifier = 2)
        assertEquals("tok-1", status.token)
    }

    @Test
    fun aServerErrorCarriesTheRetryAfter() {
        val server = Server { HttpReply(503, mapOf("Retry-After" to listOf("120")), ByteArray(0)) }
        val client = Ts43Client(server, identity, imsi, sim())
        try {
            client.queryVowifi(request())
            fail("expected an error")
        } catch (error: EntitlementException) {
            assertEquals(503, error.httpStatus)
            assertEquals(120, error.retryAfterSeconds)
        }
    }

    @Test
    fun aBadServerMacIsRejected() {
        val server = Server { challengeReply(identifier = 9, validMac = false) }
        val client = Ts43Client(server, identity, imsi, sim(AkaResult(res = res, ck = ck, ik = ik)))
        try {
            client.queryVowifi(request())
            fail("expected an error")
        } catch (error: EntitlementException) {
            assertTrue(error.message!!, error.message!!.contains("invalid MAC"))
        }
        assertEquals(1, server.calls.size)
    }

    @Test
    fun aMissingApplicationReadsAsIncompatible() {
        val status = Ts43.parse(
            """<wap-provisioningdoc version="1.1"><characteristic type="VERS"><parm name="version" value="1"/><parm name="validity" value="0"/></characteristic>
               <characteristic type="TOKEN"><parm name="token" value="t"/><parm name="validity" value="0"/></characteristic></wap-provisioningdoc>""",
        )
        assertTrue(status.incompatible)
        assertEquals(Ts43.STATUS_NOT_REQUIRED, status.addrStatus)
        assertEquals("", status.serviceFlowUrl)
    }

    @Test
    fun theIdentityPadsTheMnc() {
        assertEquals("0262011234567890@nai.epc.mnc001.mcc262.3gppnetwork.org", Ts43.eapIdentity("262011234567890", "262", "01"))
    }
}
