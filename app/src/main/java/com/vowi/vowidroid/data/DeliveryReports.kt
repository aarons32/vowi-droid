package com.vowi.vowidroid.data

/**
 * How an SMS status report (3GPP TS 23.040, TP-Status) changes the text it
 * belongs to. Each part of a sent text waits under its TP-MR; the text is
 * delivered once every part has been reported delivered.
 */
object DeliveryReports {
    /** The new list of waiting references, and the delivery state to record (null: unchanged). */
    class Update(val pendingRefs: String, val deliveryState: String?)

    /**
     * [state] is the report's status as the PDU decoder names it. Returns null
     * when [reference] is not one this text is waiting for.
     */
    fun apply(pendingRefs: String, reference: Int, state: String): Update? {
        val pending = parse(pendingRefs)
        if (reference !in pending) return null
        return when (state) {
            "delivered" -> {
                val rest = pending - reference
                Update(format(rest), if (rest.isEmpty()) "delivered" else null)
            }
            // The service centre keeps trying, and a final report follows.
            "temporary_error" -> Update(pendingRefs, null)
            // Permanent failures, and temporary ones the centre has given up on.
            "permanent_error", "temporary_error_no_retry" -> Update("", "failed")
            else -> Update(pendingRefs, null)
        }
    }

    fun format(references: List<Int>): String = references.joinToString(",")

    fun parse(pendingRefs: String): List<Int> = pendingRefs.split(',').mapNotNull { it.trim().toIntOrNull() }

    /** True when two numbers name the same phone. */
    fun samePeer(a: String, b: String): Boolean = PhoneNumbers.same(a, b)
}
