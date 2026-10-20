package com.vowi.vowidroid

import com.vowi.vowidroid.AgreementText.Block
import com.vowi.vowidroid.core.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agreement files' Markdown subset, and the two languages kept section for section. */
class UserAgreementTest {
    @Test
    fun parsesTitleHeadingsParagraphsAndBullets() {
        val blocks = AgreementText.parse(
            """
            # Title

            First line
            continues here.

            ## 1. Section
            - one
            - two
            中文第一行
            接着写。
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Title("Title"),
                Block.Paragraph("First line continues here."),
                Block.Heading("1. Section"),
                Block.Bullet("one"),
                Block.Bullet("two"),
                Block.Paragraph("中文第一行接着写。"),
            ),
            blocks,
        )
    }

    @Test
    fun bothLanguagesHaveTheSameSections() {
        val english = AgreementText.parse(TestSupport.asset("agreement/en.md").readText())
        val chinese = AgreementText.parse(TestSupport.asset("agreement/zh-Hans.md").readText())
        assertEquals(1, english.count { it is Block.Title })
        assertEquals(1, chinese.count { it is Block.Title })
        val sections = english.count { it is Block.Heading }
        assertTrue("the agreement has no sections", sections >= 10)
        assertEquals(sections, chinese.count { it is Block.Heading })
        assertEquals(english.count { it is Block.Bullet }, chinese.count { it is Block.Bullet })
        // Every section is numbered alike in both languages.
        val numbers = { blocks: List<Block> -> blocks.filterIsInstance<Block.Heading>().map { it.text.substringBefore('.') } }
        assertEquals(numbers(english), numbers(chinese))
    }
}
