package com.vowi.vowidroid

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What Android refused VowiDroid for lack of a permission: a SIM it could
 * not read, a text or call it could not show, a call without a microphone.
 * The main screen pins a red card on them until the permission is in
 * place; [refresh] drops the ones that are.
 */
object PermissionDenials {
    /** One refusal: the permission it needed and what was refused, as a string resource. */
    data class Denial(val need: AppPermissions.Need, val what: Int, val at: Long = System.currentTimeMillis())

    private val mutable = MutableStateFlow<Map<AppPermissions.Need, Denial>>(emptyMap())

    /** The refusals that stand, by permission; a permission keeps only its latest. */
    val denied: StateFlow<Map<AppPermissions.Need, Denial>> = mutable.asStateFlow()

    /** Records that [what] was refused for lack of [need]; safe from any thread. */
    fun report(need: AppPermissions.Need, what: Int) {
        mutable.update { it + (need to Denial(need, what)) }
    }

    /** Drops the refusals whose permission is in place now. */
    fun refresh(context: Context) = refresh { AppPermissions.status(context, it) == true }

    fun refresh(granted: (AppPermissions.Need) -> Boolean) {
        mutable.update { denials -> denials.filterKeys { !granted(it) } }
    }

    /** The refusal to put into words: the newest. */
    fun latest(denials: Map<AppPermissions.Need, Denial>): Denial? = denials.values.maxByOrNull { it.at }
}
