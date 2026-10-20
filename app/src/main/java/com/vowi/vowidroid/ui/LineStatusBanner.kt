package com.vowi.vowidroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.vowi.vowidroid.R
import com.vowi.vowidroid.core.WifiCallingEngine
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.telecom.CallManager
import com.vowi.vowidroid.telecom.InCallActivity
import com.vowi.vowidroid.ui.theme.CallGreen

/** The words for a connection step, for the banner and the line's details. */
fun stepLabel(stage: String): Int = when (stage) {
    WifiCallingEngine.STAGE_SIM -> R.string.step_sim
    WifiCallingEngine.STAGE_CARRIER -> R.string.step_carrier
    WifiCallingEngine.STAGE_TUNNEL -> R.string.step_tunnel
    WifiCallingEngine.STAGE_IMS -> R.string.step_ims
    else -> R.string.step_sim
}

/** What sits above the tab screens: a call that is ringing or in progress, taking the status bar's height. */
@Composable
fun TopBanners() {
    val state by CallManager.state.collectAsStateWithLifecycle()
    val call = state?.takeIf { it.phase != CallManager.Phase.ENDED } ?: return
    CallBanner(call)
}

/**
 * The call banner: "Incoming call" while one rings, "Return to call" while
 * one is in progress, with who it is with. Tapping opens the call screen.
 */
@Composable
fun CallBanner(call: CallManager.CallState) {
    val context = LocalContext.current
    val incoming = call.phase == CallManager.Phase.INCOMING
    val who = if (call.conference) stringResource(R.string.incall_conference) else call.name ?: Contacts.format(call.number)
    Surface(
        color = CallGreen,
        contentColor = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { context.startActivity(InCallActivity.intent(context)) },
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (incoming) Icons.Filled.RingVolume else Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (incoming) R.string.call_banner_incoming else R.string.call_return),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(who, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The lines that are on but not registered, as one card with a page per
 * line: which step a line is on while connecting, and on a failure the
 * step it failed at, the reason and the error code, with when the next
 * attempt comes. The page counter says how many lines wait; tapping opens
 * the line on screen. Nothing while every line is registered.
 */
@Composable
fun LineStatusCard(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val lines by VowiRepository.lines.collectAsStateWithLifecycle()
    val waiting = lines.filter { it.wifiCallingEnabled && it.present && it.sim == Line.SimStatus.IN_USE && !it.registered }
    if (waiting.isEmpty()) return
    val pager = rememberPagerState { waiting.size }
    // A line that registers takes its page away; the card stays on one that exists.
    LaunchedEffect(waiting.size) {
        if (pager.currentPage >= waiting.size) pager.scrollToPage(waiting.size - 1)
    }
    val current = waiting[pager.currentPage.coerceIn(0, waiting.size - 1)]
    val scheme = MaterialTheme.colorScheme
    val color = if (current.phase == "failed" || current.phase == "blocked") scheme.errorContainer else scheme.secondaryContainer
    Card(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(current.id) },
        colors = CardDefaults.cardColors(containerColor = color, contentColor = contentColorFor(color)),
    ) {
        Box {
            HorizontalPager(state = pager, key = { waiting[it].id }) { page ->
                LineStatusPage(waiting[page], reserveCounter = waiting.size > 1)
            }
            if (waiting.size > 1) {
                Text(
                    stringResource(R.string.status_card_page, pager.currentPage + 1, waiting.size),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** One line's page of the status card. */
@Composable
private fun LineStatusPage(line: Line, reserveCounter: Boolean) {
    val failed = line.phase == "failed"
    val blocked = line.phase == "blocked"
    val stage = (if (failed || blocked) line.failedStage else line.stage).ifEmpty { WifiCallingEngine.STAGE_SIM }
    val stepNumber = (WifiCallingEngine.STAGES.indexOf(stage) + 1).coerceAtLeast(1)
    val step = stringResource(stepLabel(stage))
    val headline = when {
        blocked -> stringResource(R.string.banner_blocked, line.name, step)
        failed -> stringResource(R.string.banner_failed, line.name, step)
        else -> stringResource(R.string.banner_connecting, line.name, stepNumber, WifiCallingEngine.STAGES.size, step)
    }
    // Counts down to the next attempt, a second at a time, while the page is on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(line.retryAt, failed) {
        while (failed && line.retryAt > now) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val lines = buildList {
        if (!failed && !blocked) return@buildList
        if (line.lastError.isNotEmpty()) add(line.lastError)
        add(
            when {
                blocked -> stringResource(R.string.banner_no_retry)
                line.retryAt > now -> {
                    val seconds = ((line.retryAt - now + 999) / 1_000).toInt()
                    if (seconds < 90) stringResource(R.string.banner_retry_in_seconds, seconds) else stringResource(R.string.banner_retry_in_minutes, (seconds + 30) / 60)
                }
                else -> stringResource(R.string.banner_retrying)
            },
        )
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = if (reserveCounter) 40.dp else 0.dp)) {
            Icon(
                when {
                    blocked -> Icons.Filled.Block
                    failed -> Icons.Filled.ErrorOutline
                    else -> Icons.Filled.Sync
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Text(headline, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!failed && !blocked) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            }
        }
        lines.forEachIndexed { index, text ->
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (index == 0) 8.dp else 4.dp))
        }
    }
}
