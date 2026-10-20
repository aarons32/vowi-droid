package com.vowi.vowidroid.ui

import com.vowi.vowidroid.UserAgreement
import androidx.compose.runtime.mutableStateOf
import android.content.Context
import com.vowi.vowidroid.AppLanguage
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vowi.vowidroid.ConversationShortcuts
import com.vowi.vowidroid.CoreService
import com.vowi.vowidroid.data.DraftStore
import com.vowi.vowidroid.SetupActivity
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.telecom.CallNotifications
import com.vowi.vowidroid.ui.theme.VowiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The native VowiDroid phone app: Phone, Messages and Contacts tabs plus native
 * settings, laid out like the stock Phone and Messages apps.
 */
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    /** Where to navigate next; consumed by the navigation host. */
    private val destination = MutableStateFlow<Destination?>(null)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation the back stack comes back by itself; acting on the intent again would, for
        // example, add a shared text to a draft twice.
        if (savedInstanceState == null) destination.value = destinationFor(intent)
        // The user agreement comes first: until it is accepted nothing starts and nothing is asked for.
        val agreed = mutableStateOf(UserAgreement.accepted(this))
        if (agreed.value) start(firstLaunch = savedInstanceState == null)
        setContent {
            VowiTheme {
                if (agreed.value) {
                    VowiNavigation(destination)
                } else {
                    AgreementScreen(
                        onAgree = {
                            UserAgreement.accept(this)
                            agreed.value = true
                            start(firstLaunch = true)
                        },
                        onDecline = { finishAndRemoveTask() },
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Lines and conversations come from the core once it is up.
                VowiCore.status.collect { if (it.running) VowiRepository.refreshAll() }
            }
        }
    }

    /** Starts the service and, on a fresh launch, asks for what is still missing. */
    private fun start(firstLaunch: Boolean) {
        CoreService.start(this)
        if (firstLaunch) {
            if (SetupActivity.shouldShow(this)) {
                startActivity(Intent(this, SetupActivity::class.java))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destinationFor(intent)?.let { destination.value = it }
    }

    override fun onResume() {
        super.onResume()
        // SIM access may have been granted from adb, or a card changed, while VowiDroid was in the background.
        VowiCore.rescan()
    }

    private fun queryValue(query: String, key: String): String? =
        query.split('&').firstNotNullOfOrNull { pair ->
            val name = pair.substringBefore('=')
            if (name.equals(key, ignoreCase = true)) Uri.decode(pair.substringAfter('=', "")) else null
        }

    private fun destinationFor(intent: Intent?): Destination? {
        intent ?: return null
        val data = intent.data
        when (intent.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> if (data?.scheme == "tel") {
                return Destination.Dial(data.schemeSpecificPart.orEmpty())
            }
            Intent.ACTION_SENDTO -> if (data?.scheme == "sms" || data?.scheme == "smsto") {
                // sms:<number>[,<number>…][?body=<text>]; apps may also pass the text as "sms_body".
                val part = data.encodedSchemeSpecificPart.orEmpty()
                val number = Uri.decode(part.substringBefore('?')).split(',', ';').firstOrNull().orEmpty().trim()
                val body = intent.getStringExtra("sms_body") ?: queryValue(part.substringAfter('?', ""), "body")
                return Destination.Compose(number, body.orEmpty())
            }
            Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    // A conversation picked in the share sheet (Direct Share) opens with the text as its draft;
                    // otherwise the user picks where it goes.
                    val shortcut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) else null
                    ConversationShortcuts.parse(shortcut)?.let { (deviceId, peer) ->
                        DraftStore.append(this, deviceId, peer, text)
                        return Destination.Thread(deviceId, peer)
                    }
                    return Destination.Compose("", text)
                }
            }
        }
        // Acting on a missed-call notification takes it away.
        intent.getStringExtra(EXTRA_MISSED_DEVICE)?.let { device ->
            CallNotifications.cancelMissed(this, device, intent.getStringExtra(EXTRA_MISSED_NUMBER).orEmpty())
        }
        intent.getStringExtra(EXTRA_VOICEMAIL_LINE)?.let { line -> return Destination.Voicemail(line) }
        val callBackDevice = intent.getStringExtra(EXTRA_CALL_BACK_DEVICE)
        val callBackNumber = intent.getStringExtra(EXTRA_CALL_BACK_NUMBER)
        if (!callBackDevice.isNullOrEmpty() && !callBackNumber.isNullOrEmpty()) return Destination.CallBack(callBackDevice, callBackNumber)
        val device = intent.getStringExtra(EXTRA_THREAD_DEVICE)
        val peer = intent.getStringExtra(EXTRA_THREAD_PEER)
        if (!device.isNullOrEmpty() && !peer.isNullOrEmpty()) return Destination.Thread(device, peer)
        return when (intent.getStringExtra(EXTRA_TAB)) {
            TAB_CALLS -> Destination.Tab(Routes.CALLS)
            TAB_MESSAGES -> Destination.Tab(Routes.MESSAGES)
            TAB_SETTINGS -> Destination.Settings
            TAB_ADD_LINE -> Destination.AddLine
            else -> null
        }
    }

    sealed interface Destination {
        data class Tab(val route: String) : Destination
        data class Thread(val deviceId: String, val peer: String) : Destination
        data class Dial(val number: String) : Destination
        data class CallBack(val deviceId: String, val number: String) : Destination
        data class Voicemail(val deviceId: String) : Destination
        data class Compose(val number: String, val body: String = "") : Destination
        data object Settings : Destination
        data object AddLine : Destination
    }

    companion object {
        const val EXTRA_TAB = "com.vowi.vowidroid.TAB"
        const val EXTRA_THREAD_DEVICE = "com.vowi.vowidroid.THREAD_DEVICE"
        const val EXTRA_THREAD_PEER = "com.vowi.vowidroid.THREAD_PEER"
        const val EXTRA_CALL_BACK_DEVICE = "com.vowi.vowidroid.CALL_BACK_DEVICE"
        const val EXTRA_CALL_BACK_NUMBER = "com.vowi.vowidroid.CALL_BACK_NUMBER"
        const val EXTRA_MISSED_DEVICE = "com.vowi.vowidroid.MISSED_DEVICE"
        const val EXTRA_VOICEMAIL_LINE = "com.vowi.vowidroid.VOICEMAIL_LINE"
        const val EXTRA_MISSED_NUMBER = "com.vowi.vowidroid.MISSED_NUMBER"
        const val TAB_CALLS = "calls"
        const val TAB_MESSAGES = "messages"
        const val TAB_SETTINGS = "settings"
        const val TAB_ADD_LINE = "add_line"
    }
}
