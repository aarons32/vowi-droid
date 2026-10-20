package com.vowi.vowidroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.AgreementText
import com.vowi.vowidroid.R
import com.vowi.vowidroid.UserAgreement

/**
 * The user agreement. On first use ([onAgree] set) it ends in a tick box and
 * Decline / Agree and continue; from Settings it is read-only with a back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgreementScreen(
    onAgree: (() -> Unit)? = null,
    onDecline: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val blocks = remember { UserAgreement.load(context) }
    var checked by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                title = { Text(stringResource(R.string.agreement_title)) },
            )
        },
        bottomBar = {
            if (onAgree != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { checked = !checked },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Text(stringResource(R.string.agreement_check), style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            stringResource(R.string.agreement_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.agreement_decline)) }
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = onAgree, enabled = checked) { Text(stringResource(R.string.agreement_agree)) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            items(blocks) { block -> AgreementBlock(block) }
            item { Spacer(Modifier.padding(bottom = 16.dp)) }
        }
    }
}

@Composable
private fun AgreementBlock(block: AgreementText.Block) {
    when (block) {
        is AgreementText.Block.Title -> Text(
            block.text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        is AgreementText.Block.Heading -> Column {
            HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp))
            Text(block.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        is AgreementText.Block.Paragraph -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        is AgreementText.Block.Bullet -> Row(Modifier.padding(top = 6.dp, start = 4.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
            Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
