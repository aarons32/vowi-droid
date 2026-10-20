package com.vowi.vowidroid.data

import com.vowi.vowidroid.core.sim.CardIdentity
import com.vowi.vowidroid.core.sim.CardMatcher
import com.vowi.vowidroid.core.sim.FoundCard
import com.vowi.vowidroid.core.sim.SimLocation

/**
 * What a scan means for one line: its saved record after the scan, and
 * what its engine should do. Kept apart from the core so it can be tested.
 */
object LinePlanner {
    enum class Action { START, MOVE, STOP, NONE }

    /**
     * The line's record once its card was looked for: tied to its subscriber
     * and card, the subscription remembered, ejection ended by taking the
     * card out, and its last place kept.
     */
    fun update(config: LineConfig, card: FoundCard?, binding: CardIdentity?): LineConfig = config.copy(
        imsi = config.imsi ?: binding?.imsi,
        iccid = binding?.iccid ?: config.iccid,
        subscriptionId = card?.subscriptionId?.takeIf { it >= 0 } ?: config.subscriptionId,
        ejected = CardMatcher.stillEjected(config.ejected, card != null),
        lastSeen = card?.location?.tag ?: config.lastSeen,
        slot = (card?.location as? SimLocation.PhoneSlot)?.slot ?: config.slot,
    )

    /**
     * A line runs while it is switched on, not ejected, and its card is in
     * and readable. A running line whose card moved reconnects from the new
     * place.
     */
    fun action(config: LineConfig, card: FoundCard?, previous: SimLocation?, running: Boolean): Action {
        val wanted = config.enabled && !config.ejected && card != null && card.readable
        return when {
            wanted && !running -> Action.START
            wanted && previous != null && previous != card?.location -> Action.MOVE
            !wanted && running -> Action.STOP
            else -> Action.NONE
        }
    }
}
