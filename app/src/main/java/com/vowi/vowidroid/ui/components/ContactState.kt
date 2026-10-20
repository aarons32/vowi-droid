package com.vowi.vowidroid.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vowi.vowidroid.data.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The contact name for [number], looked up off the main thread; null until found, or when unknown. */
@Composable
fun rememberContactName(number: String): String? {
    val context = LocalContext.current
    var name by remember(number) { mutableStateOf<String?>(null) }
    LaunchedEffect(number) { name = withContext(Dispatchers.IO) { Contacts.nameFor(context, number) } }
    return name
}

/** The contact card for [number], or null when the number is not in Contacts. [refresh] repeats the lookup. */
@Composable
fun rememberContactUri(number: String, refresh: Any? = null): Uri? {
    val context = LocalContext.current
    var uri by remember(number) { mutableStateOf<Uri?>(null) }
    LaunchedEffect(number, refresh) { uri = withContext(Dispatchers.IO) { Contacts.lookupUri(context, number) } }
    return uri
}
