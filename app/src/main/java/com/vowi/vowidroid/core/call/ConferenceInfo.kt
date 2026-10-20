package com.vowi.vowidroid.core.call

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** One person in a conference call. */
data class Participant(
    /** Their identity as the conference reports it, e.g. tel:+15551234567. */
    val uri: String,
    val name: String?,
    /** RFC 4575 endpoint status: connected, alerting, on-hold, disconnected… */
    val status: String,
)

/** The conference event package's document (RFC 4575, application/conference-info+xml). */
object ConferenceInfo {
    /** A full list replaces what was known; a partial one updates it, and [removed] users leave. */
    class Update(val full: Boolean, val users: List<Participant>, val removed: Set<String>)

    fun parse(xml: String): Update? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // No document types or external entities from the network.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement
        if (localName(root) != "conference-info") return null
        val users = ArrayList<Participant>()
        val removed = HashSet<String>()
        for (user in elements(root, "user")) {
            val entity = user.getAttribute("entity").trim()
            if (entity.isEmpty()) continue
            if (user.getAttribute("state") == "deleted") {
                removed.add(entity)
                continue
            }
            val name = elements(user, "display-text").firstOrNull()?.textContent?.trim()?.ifEmpty { null }
            val status = elements(user, "status").firstOrNull()?.textContent?.trim().orEmpty().ifEmpty { "connected" }
            users.add(Participant(entity, name, status))
        }
        Update(full = root.getAttribute("state") != "partial", users = users, removed = removed)
    }.getOrNull()

    /**
     * Applies an update to the participants known so far. People are matched
     * by number, so a placeholder like sip:5551234567@ims…;user=phone gives
     * way to the conference's own tel:+15551234567 for the same person.
     */
    fun apply(current: List<Participant>, update: Update): List<Participant> {
        val byKey = LinkedHashMap<String, Participant>()
        if (!update.full) current.forEach { byKey[key(it.uri)] = it }
        update.users.forEach { byKey[key(it.uri)] = it }
        update.removed.forEach { byKey.remove(key(it)) }
        return byKey.values.filter { it.status != "disconnected" }
    }

    /** The last ten digits of a telephone identity, or the whole URI for anything else. */
    fun key(uri: String): String {
        val user = uri.substringAfter(':').substringBefore('@').substringBefore(';')
        val digits = user.filter(Char::isDigit)
        val telephone = user.all { it.isDigit() || it in "+-.()" }
        return if (telephone && digits.length >= 7) digits.takeLast(10) else uri.lowercase()
    }

    /** The telephone number in a participant's URI, or null when it names no number. */
    fun number(uri: String): String? {
        val user = uri.substringAfter(':').substringBefore('@').substringBefore(';')
        return user.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c in "+-.()" } && it.count(Char::isDigit) >= 3 }
            ?.filter { it.isDigit() || it == '+' }
    }

    private fun localName(element: Element): String = element.nodeName.substringAfter(':')

    private fun elements(parent: Element, name: String): List<Element> {
        val nodes = parent.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { localName(it) == name }
    }
}
