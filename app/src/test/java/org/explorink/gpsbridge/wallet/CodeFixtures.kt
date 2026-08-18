package org.explorink.gpsbridge.wallet

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * What the laptop generator made of every code case, loaded from
 * `src/test/resources/wallet-code-parity/` (written by
 * `tools/wallet_code_fixture.py`).
 *
 * Shared by the code tests so the payloads and the reference numbers live in one
 * place. The payloads themselves come out of the fixture, not out of a constant
 * copied twice.
 */
object CodeFixtures {

    // `by lazy` and not a plain `val`: these read the fixture, and an object's
    // initialisers run in declaration order -- a plain val here would touch the
    // `cases` delegate below before it exists and NPE.
    /** A realistic IATA BCBP boarding pass with the security block, 136 chars. */
    val BCBP136: String by lazy { payloadOf("qr-bcbp136") }

    /** The same pass without the security block: one leg, mandatory fields. */
    val BCBP61: String by lazy { payloadOf("pdf417-bcbp61") }

    const val SHORT = "TEST12345"
    const val EAN = "5901234123457"

    /** One recorded case: the generator's matrix, layout and stored bytes. */
    class Case(val map: Map<String, Any?>) {
        val name: String get() = Json.asString(map["name"])
        val symbology: Symbology get() = Symbology.require(Json.asString(map["symbology"]))
        val payload: String get() = Json.asString(map["payload"])
        val requestedOrientation: String get() = Json.asString(map["requestedOrientation"])

        fun panel(name: String): Panel = Panel(Json.asMap(Json.asMap(map["panels"])[name]))

        class Panel(val map: Map<String, Any?>) {
            val modulesX: Int get() = Json.asInt(map["modulesX"])
            val modulesY: Int get() = Json.asInt(map["modulesY"])
            val chosen: String get() = Json.asString(map["chosen"])
            fun orientation(o: String): Map<String, Any?> = Json.asMap(map[o])
            val asset: Map<String, Any?> get() = Json.asMap(map["asset"])
        }
    }

    val cases: List<Case> by lazy {
        Json.asList(Json.asMap(Json.parse(text("codes.json")))["cases"])
            .map { Case(Json.asMap(it)) }
    }

    fun case(name: String): Case = cases.firstOrNull { it.name == name }
        ?: throw AssertionError("no fixture case '$name'")

    fun payloadOf(caseName: String): String = case(caseName).payload

    /** The generator's stored asset payload for one case and panel. */
    fun assetBytes(case: Case, panelName: String): ByteArray {
        val gz = Json.asString(case.panel(panelName).asset["gz"])
        return GZIPInputStream(resource(gz)).use { it.readAll() }
    }

    private fun resource(name: String): InputStream =
        CodeFixtures::class.java.classLoader!!.getResourceAsStream("wallet-code-parity/$name")
            ?: throw AssertionError("missing fixture wallet-code-parity/$name " +
                "(regenerate with tools/wallet_code_fixture.py)")

    private fun text(name: String): String =
        resource(name).use { it.readAll().toString(Charsets.UTF_8) }

    private fun InputStream.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
