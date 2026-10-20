package com.vowi.vowidroid.data

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.core.content.PermissionChecker
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The phone's contacts, for names, photos and the Contacts tab. */
object Contacts {
    private val names = ConcurrentHashMap<String, String>()

    // PermissionChecker also honors app-ops, which some ROMs (e.g. HyperOS) use
    // to deny a permission the runtime reports as granted.
    fun permitted(context: Context): Boolean =
        PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PermissionChecker.PERMISSION_GRANTED

    /** The contact name for a number, or null. Cached; call off the main thread. */
    fun nameFor(context: Context, number: String): String? {
        if (number.isBlank() || !permitted(context)) return null
        names[number]?.let { return it.ifEmpty { null } }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        names[number] = name.orEmpty()
        return name
    }

    fun forget() = names.clear()

    /** The contact's small photo for a number, or null. Call off the main thread. */
    fun photoFor(context: Context, number: String): Bitmap? {
        if (number.isBlank() || !permitted(context)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val photo = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: return null
        return runCatching { context.contentResolver.openInputStream(Uri.parse(photo))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    /** A contact photo by its content URI, or null. Call off the main thread. */
    fun photo(context: Context, uri: String): Bitmap? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()

    /** The contact's lookup URI for a number, to open the contact card; null when unknown. Off the main thread. */
    fun lookupUri(context: Context, number: String): Uri? {
        if (number.isBlank() || !permitted(context)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) ContactsContract.Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1)) else null }
        }.getOrNull()
    }

    /** All contacts with at least one number, sorted by name. Call off the main thread. */
    fun all(context: Context): List<Contact> {
        if (!permitted(context)) return emptyList()
        val byId = linkedMapOf<Long, Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
        )
        // Sorted by the provider's sort key, with its address-book index: the same order and letters
        // as the Contacts app, e.g. Chinese names by pinyin initial (AOSP's contacts list does this).
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.Contacts.EXTRA_ADDRESS_BOOK_INDEX, "true")
            .build()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.SORT_KEY_PRIMARY,
        )?.use { cursor ->
            val titles = cursor.extras?.getStringArray(ContactsContract.Contacts.EXTRA_ADDRESS_BOOK_INDEX_TITLES)
            val counts = cursor.extras?.getIntArray(ContactsContract.Contacts.EXTRA_ADDRESS_BOOK_INDEX_COUNTS)
            var section = 0
            var sectionEnd = counts?.firstOrNull() ?: 0
            while (cursor.moveToNext()) {
                if (titles != null && counts != null) {
                    while (cursor.position >= sectionEnd && section < counts.lastIndex) {
                        section++
                        sectionEnd += counts[section]
                    }
                }
                val title = titles?.getOrNull(section).orEmpty()
                val id = cursor.getLong(0)
                val name = cursor.getString(1).orEmpty()
                val number = cursor.getString(2).orEmpty()
                val photo = cursor.getString(3)
                // "Mobile", "Work" or the user's own label, in the phone's language.
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, cursor.getInt(4), cursor.getString(5)).toString()
                val starred = cursor.getInt(6) != 0
                val lookupKey = cursor.getString(7)
                val existing = byId[id]
                if (existing == null) {
                    byId[id] = Contact(id, name, listOf(number), photo, listOf(label), starred, lookupKey, title)
                } else if (existing.numbers.none { sameNumber(it, number) }) {
                    byId[id] = existing.copy(numbers = existing.numbers + number, labels = existing.labels + label)
                }
            }
        }
        return byId.values.filter { it.name.isNotBlank() }
    }

    /** Whether two numbers are the same phone; the country-aware check needs Android 12. */
    private fun sameNumber(a: String, b: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PhoneNumberUtils.areSamePhoneNumber(a, b, country())
        } else {
            @Suppress("DEPRECATION")
            PhoneNumberUtils.compare(a, b)
        }

    /** Formats a number the way the phone's own dialer would. */
    fun format(number: String): String =
        PhoneNumberUtils.formatNumber(number, country()) ?: number

    private fun country(): String = Locale.getDefault().country.ifEmpty { "US" }
}
