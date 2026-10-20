package com.vowi.vowidroid

import android.content.Context

/**
 * The user agreement VowiDroid asks for on first use, and again whenever
 * [VERSION] goes up. Its text lives in `assets/agreement/`, one file per
 * language; `R.string.agreement_asset` names the file for the app's language.
 */
object UserAgreement {
    /** Raise when the agreement's terms change, so every user is asked again. */
    const val VERSION = 1

    private const val PREFERENCES = "user_agreement"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_AT = "accepted_at"

    fun accepted(context: Context): Boolean = preferences(context).getInt(KEY_VERSION, 0) >= VERSION

    fun accept(context: Context) {
        preferences(context).edit().putInt(KEY_VERSION, VERSION).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    /** When the current version was accepted, or 0. */
    fun acceptedAt(context: Context): Long = if (accepted(context)) preferences(context).getLong(KEY_AT, 0) else 0

    /** The agreement in the app's language, as blocks to show. */
    fun load(context: Context): List<AgreementText.Block> =
        context.assets.open(context.getString(R.string.agreement_asset)).bufferedReader(Charsets.UTF_8).use { AgreementText.parse(it.readText()) }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

/**
 * The agreement files' small Markdown subset: "# " the title, "## " a
 * section heading, "- " a bullet, and anything else a paragraph, whose lines
 * join until a blank line.
 */
object AgreementText {
    sealed interface Block {
        val text: String

        data class Title(override val text: String) : Block
        data class Heading(override val text: String) : Block
        data class Paragraph(override val text: String) : Block
        data class Bullet(override val text: String) : Block
    }

    fun parse(source: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.toString())
            paragraph.clear()
        }
        for (raw in source.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("## ") -> { flush(); blocks += Block.Heading(line.removePrefix("## ").trim()) }
                line.startsWith("# ") -> { flush(); blocks += Block.Title(line.removePrefix("# ").trim()) }
                line.startsWith("- ") -> { flush(); blocks += Block.Bullet(line.removePrefix("- ").trim()) }
                else -> {
                    if (paragraph.isNotEmpty() && !paragraph.endsWith(" ") && !startsWide(line)) paragraph.append(' ')
                    paragraph.append(line)
                }
            }
        }
        flush()
        return blocks
    }

    /** Chinese runs on without spaces when a paragraph wraps in the file. */
    private fun startsWide(line: String): Boolean = line.first().code >= 0x2E80
}
