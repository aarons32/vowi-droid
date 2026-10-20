package com.vowi.vowidroid.ui.components

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.vowi.vowidroid.data.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Contact photos for avatars, loaded off the main thread and kept in a small cache. */
private object PhotoCache {
    private class Entry(val image: ImageBitmap?)
    private val cache = LruCache<String, Entry>(120)

    fun put(key: String, image: ImageBitmap?) {
        cache.put(key, Entry(image))
    }

    fun image(key: String): ImageBitmap? = cache.get(key)?.image

    fun has(key: String): Boolean = cache.get(key) != null
}

/** The contact photo for a phone number, or null while loading or when there is none. */
@Composable
fun rememberContactPhoto(number: String): ImageBitmap? {
    val context = LocalContext.current
    val key = "tel:$number"
    var image by remember(number) { mutableStateOf(PhotoCache.image(key)) }
    LaunchedEffect(number) {
        if (PhotoCache.has(key)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { Contacts.photoFor(context, number)?.asImageBitmap() }
        PhotoCache.put(key, loaded)
        image = loaded
    }
    return image
}

/** A contact photo by its content URI (from the contacts list), or null. */
@Composable
fun rememberPhoto(uri: String?): ImageBitmap? {
    val context = LocalContext.current
    if (uri == null) return null
    var image by remember(uri) { mutableStateOf(PhotoCache.image(uri)) }
    LaunchedEffect(uri) {
        if (PhotoCache.has(uri)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { Contacts.photo(context, uri)?.asImageBitmap() }
        PhotoCache.put(uri, loaded)
        image = loaded
    }
    return image
}
