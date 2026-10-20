package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.carrier.SmsCenters
import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.hexToBytes
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStreamReader

/** Shared helpers for the core's JVM tests. */
object TestSupport {
    /** The known-answer vectors (`reference_vectors.json`). */
    val vectors: JsonObject by lazy {
        val stream = TestSupport::class.java.classLoader!!.getResourceAsStream("reference_vectors.json")
            ?: error("reference_vectors.json is missing from the test resources")
        InputStreamReader(stream, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    /** A test resource's bytes, e.g. a certificate. */
    fun resource(name: String): ByteArray =
        TestSupport::class.java.classLoader!!.getResourceAsStream(name)?.use { it.readBytes() } ?: error("$name is missing from the test resources")

    fun JsonObject.hex(name: String): ByteArray = get(name).asString.hexToBytes()
    fun JsonObject.text(name: String): String = get(name).asString

    /** The deterministic byte stream the vectors were generated with. */
    class PatternRandom : RandomSource {
        private var index = 0
        override fun invoke(count: Int): ByteArray = ByteArray(count) { ((index++ * 7 + 3) and 0xff).toByte() }
    }

    fun repeat(value: Int, count: Int): ByteArray = ByteArray(count) { value.toByte() }

    /** Points the carrier profiles, and the SMS centre table, at the app's asset files. */
    fun installCarrierProfiles() {
        val profiles = asset("carrier_profiles.json")
        CarrierProfiles.install { profiles.inputStream() }
        val centers = asset("sms_centers.json")
        SmsCenters.install { centers.inputStream() }
    }

    /** One of the app's asset files, whether the tests run from the project or the module. */
    fun asset(name: String): File {
        val candidates = listOf("src/main/assets/$name", "app/src/main/assets/$name")
        return candidates.map(::File).firstOrNull { it.exists() } ?: error("$name not found from ${File(".").absolutePath}")
    }
}
