package com.vowi.vowidroid.ui

import com.vowi.vowidroid.AppLanguage
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vowi.vowidroid.EntitlementStore
import com.vowi.vowidroid.R
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.core.entitlement.Ts43
import com.vowi.vowidroid.core.entitlement.VowifiEntitlement
import com.vowi.vowidroid.ui.theme.VowiTheme
import java.util.concurrent.Executors

/**
 * The carrier's emergency (E911) address for one line. VowiDroid signs in to
 * the carrier's entitlement server with the SIM, shows what the carrier has
 * on file, and hosts the carrier's own page for entering or changing the
 * address, as Android's IMS entitlement app does. The page talks back
 * through `VoWiFiWebServiceFlow.entitlementChanged()` and `dismissFlow()`.
 */
@SuppressLint("SetJavaScriptEnabled")
class EmergencyAddressActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private lateinit var lineId: String
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var webViewHolder: WebView? = null
    private var rechecks = 0

    // What the screen shows; Compose observes these.
    private var checking by mutableStateOf(false)
    private var settling by mutableStateOf(false)
    private var summary by mutableStateOf<EntitlementStore.Summary?>(null)
    private var error by mutableStateOf<String?>(null)
    private var latest by mutableStateOf<VowifiEntitlement?>(null)
    private var pageOpen by mutableStateOf(false)
    private var pageLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lineId = intent.getStringExtra(EXTRA_LINE).orEmpty()
        if (lineId.isEmpty()) {
            finish()
            return
        }
        enableEdgeToEdge()
        summary = EntitlementStore.summary(this, lineId)
        setContent { VowiTheme { Screen() } }
        check(freshSignIn = false, afterPage = false)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        webViewHolder?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webViewHolder = null
        super.onDestroy()
    }

    /** The one WebView for the carrier's page, made when the page is first opened. */
    private fun webView(): WebView = webViewHolder ?: WebView(this).apply {
        settings.javaScriptEnabled = true // The carrier's page is an app of its own and calls back through JavaScript.
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoading = false
            }
        }
        webChromeClient = object : WebChromeClient() {
            // A page that closes itself (window.close) is done with the user.
            override fun onCloseWindow(window: WebView?) {
                closePage()
            }
        }
        addJavascriptInterface(WebSheetBridge(), Ts43.WEB_SHEET_INTERFACE)
        webViewHolder = this
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var menu by remember { mutableStateOf(false) }
        var editServer by remember { mutableStateOf(false) }
        BackHandler(enabled = pageOpen) {
            val web = webViewHolder
            if (web != null && web.canGoBack()) web.goBack() else closePage()
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(stringResource(R.string.emergency_address_title)) },
                    actions = {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.emergency_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.emergency_check_again)) },
                                enabled = !checking,
                                onClick = {
                                    menu = false
                                    EntitlementStore.forgetToken(this@EmergencyAddressActivity, lineId)
                                    closePage()
                                    check(freshSignIn = true, afterPage = false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.emergency_server)) },
                                enabled = !checking,
                                onClick = {
                                    menu = false
                                    editServer = true
                                },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (checking || pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (pageOpen) {
                    Text(
                        stringResource(R.string.emergency_page_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    AndroidView(
                        factory = { webView().also { (it.parent as? ViewGroup)?.removeView(it) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.emergency_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusCard()
                        Button(
                            onClick = { latest?.let(::openPage) },
                            enabled = !checking && latest?.serviceFlowUrl?.isNotEmpty() == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.emergency_open_page))
                        }
                    }
                }
            }
        }
        if (editServer) {
            ServerDialog(
                initial = EntitlementStore.server(this, lineId).orEmpty(),
                onDismiss = { editServer = false },
                onSave = { server ->
                    editServer = false
                    EntitlementStore.setServer(this, lineId, server)
                    closePage()
                    check(freshSignIn = true, afterPage = false)
                },
            )
        }
    }

    /** What the carrier last said, with an icon that says it at a glance. */
    @Composable
    private fun StatusCard() {
        val summary = summary
        val error = error
        val latest = latest
        val scheme = MaterialTheme.colorScheme
        val checked = summary != null && summary.checkedAt != 0L
        val look: Triple<ImageVector, Color, String> = when {
            settling -> Triple(Icons.Filled.Sync, scheme.primary, stringResource(R.string.emergency_status_in_progress))
            checking -> Triple(Icons.Filled.Sync, scheme.primary, stringResource(R.string.emergency_checking))
            error != null -> Triple(Icons.Filled.ErrorOutline, scheme.error, stringResource(R.string.emergency_status_error, error))
            !checked -> Triple(Icons.Outlined.Info, scheme.onSurfaceVariant, stringResource(R.string.emergency_status_unknown))
            summary.entitlementStatus == Ts43.ENTITLEMENT_INCOMPATIBLE -> Triple(Icons.Filled.Block, scheme.error, EntitlementStore.statusText(this, summary))
            summary.addressOnFile || summary.addrStatus == Ts43.STATUS_NOT_REQUIRED -> Triple(Icons.Filled.CheckCircle, scheme.primary, EntitlementStore.statusText(this, summary))
            else -> Triple(Icons.Filled.Warning, scheme.tertiary, EntitlementStore.statusText(this, summary))
        }
        val lines = ArrayList<String>()
        if (checked && summary != null && error == null && !checking) {
            latest?.messageForIncompatible?.takeIf { it.isNotEmpty() && latest.incompatible }?.let { lines += it }
            lines += stringResource(R.string.emergency_details, summary.entitlementStatus, summary.addrStatus, summary.tcStatus, summary.provStatus)
            if (summary.viaCellular) lines += stringResource(R.string.emergency_over_cellular)
            if (summary.pageUrl.isNotEmpty()) lines += stringResource(R.string.emergency_page_url, summary.pageUrl) else lines += stringResource(R.string.emergency_no_page)
        }
        if (checked && summary != null && !checking) lines += EntitlementStore.checkedText(this, summary)
        Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Icon(look.first, contentDescription = null, tint = look.second, modifier = Modifier.size(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(look.third, style = MaterialTheme.typography.titleMedium)
                    for (line in lines) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun ServerDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
        var value by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.emergency_server)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.emergency_server_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    private fun check(freshSignIn: Boolean, afterPage: Boolean) {
        checking = true
        error = null
        executor.execute {
            val result = runCatching { VowiCore.emergencyAddress(lineId, freshSignIn) }
            handler.post {
                if (isDestroyed || isFinishing) return@post
                checking = false
                settling = false
                summary = EntitlementStore.summary(this, lineId)
                result.onSuccess { show(it, afterPage) }.onFailure {
                    latest = null
                    error = it.message ?: it.javaClass.simpleName
                }
            }
        }
    }

    private fun show(answer: VowifiEntitlement, afterPage: Boolean) {
        latest = answer
        val addressSettled = answer.addressOnFile || answer.addrStatus == Ts43.STATUS_NOT_REQUIRED
        when {
            // The page said something changed; the carrier may take a moment to record it.
            afterPage && !answer.incompatible && !addressSettled && rechecks < MAX_RECHECKS -> {
                rechecks++
                settling = true
                checking = true
                handler.postDelayed({ check(freshSignIn = false, afterPage = true) }, RECHECK_DELAY_MS)
            }
            !afterPage && !answer.incompatible && answer.serviceFlowUrl.isNotEmpty() -> openPage(answer)
            else -> closePage()
        }
    }

    /** The carrier's page, posted with the data the server gave for it. */
    private fun openPage(answer: VowifiEntitlement) {
        rechecks = 0
        pageOpen = true
        val web = webView()
        if (answer.serviceFlowUserData.isNotEmpty()) {
            web.postUrl(answer.serviceFlowUrl, answer.serviceFlowUserData.toByteArray(Charsets.UTF_8))
        } else {
            web.loadUrl(answer.serviceFlowUrl)
        }
    }

    /** Back to the status; the page stays loaded for next time. */
    private fun closePage() {
        pageOpen = false
        pageLoading = false
    }

    private inner class WebSheetBridge {
        @JavascriptInterface
        fun entitlementChanged() {
            handler.post {
                if (isDestroyed || isFinishing) return@post
                closePage()
                rechecks = 0
                check(freshSignIn = false, afterPage = true)
            }
        }

        /** The page's own Close or Cancel. */
        @JavascriptInterface
        fun dismissFlow() {
            handler.post { if (!isDestroyed && !isFinishing) closePage() }
        }
    }

    companion object {
        private const val EXTRA_LINE = "line"
        private const val MAX_RECHECKS = 6
        private const val RECHECK_DELAY_MS = 5_000L

        fun intent(context: Context, lineId: String): Intent =
            Intent(context, EmergencyAddressActivity::class.java).putExtra(EXTRA_LINE, lineId)
    }
}
