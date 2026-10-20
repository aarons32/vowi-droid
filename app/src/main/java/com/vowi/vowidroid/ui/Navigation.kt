package com.vowi.vowidroid.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.telecom.CallManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.calls.CallDetailsScreen
import com.vowi.vowidroid.ui.calls.CallsScreen
import com.vowi.vowidroid.ui.contacts.ContactsScreen
import com.vowi.vowidroid.ui.messages.ConversationsScreen
import com.vowi.vowidroid.ui.messages.NewMessageScreen
import com.vowi.vowidroid.ui.messages.ThreadScreen
import com.vowi.vowidroid.ui.settings.AddLineScreen
import com.vowi.vowidroid.ui.settings.LineScreen
import com.vowi.vowidroid.ui.settings.LogsScreen
import com.vowi.vowidroid.ui.settings.QuickResponsesScreen
import com.vowi.vowidroid.ui.settings.BlockedNumbersScreen
import com.vowi.vowidroid.ui.settings.CallForwardingScreen
import com.vowi.vowidroid.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val CALLS = "calls"
    const val CONTACTS = "contacts"
    const val MESSAGES = "messages"
    const val THREAD = "thread/{device}/{peer}"
    const val CALL_DETAILS = "calls/details/{number}"
    const val ARCHIVED = "messages/archived"
    const val BLOCKED = "settings/blocked"
    const val QUICK_RESPONSES = "settings/quick-responses"
    const val COMPOSE = "compose?to={to}&body={body}"
    const val SETTINGS = "settings"
    const val LINE = "settings/line/{id}"
    const val FORWARDING = "settings/line/{id}/forwarding"
    const val ADD_LINE = "settings/add"
    const val LOGS = "settings/logs"
    const val AGREEMENT = "settings/agreement"

    fun thread(deviceId: String, peer: String) = "thread/${Uri.encode(deviceId)}/${Uri.encode(peer)}"
    fun callDetails(number: String) = "calls/details/${Uri.encode(number)}"
    fun compose(to: String = "", body: String = "") = "compose?to=${Uri.encode(to)}&body=${Uri.encode(body)}"
    fun line(id: String) = "settings/line/${Uri.encode(id)}"
    fun forwarding(id: String) = "settings/line/${Uri.encode(id)}/forwarding"
}

private data class Tab(val route: String, val label: Int, val selected: ImageVector, val unselected: ImageVector)

// Phone and Messages, the two things VowiDroid carries, sit side by side; Contacts serves both, and Settings
// holds the lines.
private val tabs = listOf(
    Tab(Routes.CALLS, R.string.tab_calls, Icons.Filled.Call, Icons.Outlined.Call),
    Tab(Routes.MESSAGES, R.string.tab_messages, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    Tab(Routes.CONTACTS, R.string.tab_contacts, Icons.Filled.Person, Icons.Outlined.Person),
    Tab(Routes.SETTINGS, R.string.settings_title, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun VowiNavigation(destination: MutableStateFlow<MainActivity.Destination?>) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val conversations by VowiRepository.conversations.collectAsStateWithLifecycle()
    val unread = conversations.sumOf { it.unread }
    var dialNumber by remember { mutableStateOf<String?>(null) }
    var callBack by remember { mutableStateOf<Pair<String, String>?>(null) }
    var voicemailLine by remember { mutableStateOf<String?>(null) }

    val pending by destination.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        // Links from notifications and other apps land on a fresh tab, not on
        // whatever that tab's saved back stack last showed.
        when (val target = pending ?: return@LaunchedEffect) {
            is MainActivity.Destination.Tab -> navController.openTab(target.route)
            is MainActivity.Destination.Thread -> {
                navController.openTab(Routes.MESSAGES)
                navController.navigate(Routes.thread(target.deviceId, target.peer))
            }
            is MainActivity.Destination.Compose -> {
                navController.openTab(Routes.MESSAGES)
                navController.navigate(Routes.compose(target.number, target.body))
            }
            is MainActivity.Destination.Dial -> {
                navController.openTab(Routes.CALLS)
                dialNumber = target.number
            }
            is MainActivity.Destination.CallBack -> {
                navController.openTab(Routes.CALLS)
                callBack = target.deviceId to target.number
            }
            is MainActivity.Destination.Voicemail -> {
                navController.openTab(Routes.CALLS)
                voicemailLine = target.deviceId
            }
            MainActivity.Destination.Settings -> navController.openTab(Routes.SETTINGS)
            MainActivity.Destination.AddLine -> {
                navController.openTab(Routes.SETTINGS)
                navController.navigate(Routes.ADD_LINE)
            }
        }
        destination.value = null
    }

    Scaffold(
        // Every screen has its own top bar, which reaches under the status bar by itself. This outer
        // scaffold must not pad for the system bars too, or each screen starts a status bar's height
        // too low; it only makes room for the tabs.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // A call that is ringing or in progress, and a line that is still connecting or failed, sit above the
        // tab screens. They pad for the status bar themselves, and the screens below consume that padding,
        // so nothing is padded twice.
        topBar = { if (tabs.any { it.route == route }) TopBanners() },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.selectTab(tab.route) },
                            icon = {
                                BadgedBox(badge = {
                                    if (tab.route == Routes.MESSAGES && unread > 0) Badge { Text(unread.toString()) }
                                }) { Icon(if (selected) tab.selected else tab.unselected, contentDescription = null) }
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The tabs already cover the navigation bar, so screens above them add no room for it again.
        NavHost(navController, startDestination = Routes.CALLS, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.CALLS) {
                CallsScreen(
                    dialNumber = dialNumber,
                    onDialNumberConsumed = { dialNumber = null },
                    callBack = callBack,
                    onCallBackConsumed = { callBack = null },
                    voicemailLine = voicemailLine,
                    onVoicemailConsumed = { voicemailLine = null },
                    onMessage = { device, number -> navController.navigate(Routes.thread(device, number)) },
                    onDetails = { number -> navController.navigate(Routes.callDetails(number)) },
                    onSettings = { navController.selectTab(Routes.SETTINGS) },
                    onOpenLine = { navController.navigate(Routes.line(it)) },
                )
            }
            composable(Routes.CALL_DETAILS, arguments = listOf(navArgument("number") { type = NavType.StringType })) { backStack ->
                CallDetailsScreen(
                    number = backStack.arguments?.getString("number").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onMessage = { device, number -> navController.navigate(Routes.thread(device, number)) },
                )
            }
            composable(Routes.CONTACTS) {
                ContactsScreen(
                    onMessage = { number -> navController.navigate(Routes.compose(number)) },
                )
            }
            composable(Routes.MESSAGES) {
                ConversationsScreen(
                    archived = false,
                    onOpen = { conversation -> navController.navigate(Routes.thread(conversation.deviceId, conversation.peer)) },
                    onOpenMessage = { message -> navController.navigate(Routes.thread(message.deviceId, message.peer)) },
                    onNew = { navController.navigate(Routes.compose()) },
                    onSettings = { navController.selectTab(Routes.SETTINGS) },
                    onOpenLine = { navController.navigate(Routes.line(it)) },
                    onArchived = { navController.navigate(Routes.ARCHIVED) },
                    onBlocked = { navController.navigate(Routes.BLOCKED) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ARCHIVED) {
                ConversationsScreen(
                    archived = true,
                    onOpen = { conversation -> navController.navigate(Routes.thread(conversation.deviceId, conversation.peer)) },
                    onOpenMessage = { message -> navController.navigate(Routes.thread(message.deviceId, message.peer)) },
                    onNew = {},
                    onSettings = { navController.selectTab(Routes.SETTINGS) },
                    onOpenLine = { navController.navigate(Routes.line(it)) },
                    onArchived = {},
                    onBlocked = { navController.navigate(Routes.BLOCKED) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.BLOCKED) {
                BlockedNumbersScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.QUICK_RESPONSES) {
                QuickResponsesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.THREAD,
                arguments = listOf(
                    navArgument("device") { type = NavType.StringType },
                    navArgument("peer") { type = NavType.StringType },
                ),
            ) { backStack ->
                ThreadScreen(
                    deviceId = backStack.arguments?.getString("device").orEmpty(),
                    peer = backStack.arguments?.getString("peer").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onForward = { body -> navController.navigate(Routes.compose(body = body)) },
                    onOpenThread = { device, number -> navController.navigate(Routes.thread(device, number)) },
                    onSwitchLine = { device ->
                        val number = backStack.arguments?.getString("peer").orEmpty()
                        navController.navigate(Routes.thread(device, number)) { popUpTo(Routes.THREAD) { inclusive = true } }
                    },
                    onOpenLine = { navController.navigate(Routes.line(it)) },
                )
            }
            composable(
                Routes.COMPOSE,
                arguments = listOf(
                    navArgument("to") { type = NavType.StringType; defaultValue = "" },
                    navArgument("body") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { backStack ->
                NewMessageScreen(
                    initialNumber = backStack.arguments?.getString("to").orEmpty(),
                    initialBody = backStack.arguments?.getString("body").orEmpty(),
                    onStart = { device, number ->
                        navController.navigate(Routes.thread(device, number)) {
                            popUpTo(Routes.COMPOSE) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onLine = { navController.navigate(Routes.line(it)) },
                    onAddLine = { navController.navigate(Routes.ADD_LINE) },
                    onLogs = { navController.navigate(Routes.LOGS) },
                    onAgreement = { navController.navigate(Routes.AGREEMENT) },
                    onBlocked = { navController.navigate(Routes.BLOCKED) },
                    onQuickResponses = { navController.navigate(Routes.QUICK_RESPONSES) },
                    onBack = null, // a tab, not a pushed screen
                )
            }
            composable(Routes.LOGS) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AGREEMENT) {
                AgreementScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LINE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
                LineScreen(
                    lineId = backStack.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onForwarding = { navController.navigate(Routes.forwarding(it)) },
                )
            }
            composable(Routes.FORWARDING, arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
                CallForwardingScreen(
                    lineId = backStack.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ADD_LINE) {
                AddLineScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}


/**
 * Opens a tab from a link: its root screen, without restoring saved state.
 * The pop still saves state, as [selectTab]'s does: Navigation keys the saved
 * stack to the start destination the first time something pops to it, and a
 * pop without saveState leaves that key unset, so the next tap on the Phone
 * tab would "restore" the stack it had just popped and stay where it was.
 */
private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}

/** Switches to a tab the way the bottom bar does: each tab keeps its own back stack. */
private fun NavHostController.selectTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
