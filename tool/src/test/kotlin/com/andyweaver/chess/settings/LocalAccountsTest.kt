package com.andyweaver.chess.settings

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LocalAccounts.load] is the only thing standing between a hand-edited JSON file and the
 * login flow, so every malformed shape must degrade to an empty list rather than throw —
 * an exception here would break plain manual login for everyone.
 */
class LocalAccountsTest {

    private fun dirWith(contents: String?): File {
        val dir = Files.createTempDirectory("local-accounts").toFile()
        if (contents != null) File(dir, LocalAccounts.FILE_NAME).writeText(contents)
        return dir
    }

    @Test
    fun `missing file yields no accounts`() {
        assertTrue(LocalAccounts.load(dirWith(null)).isEmpty())
    }

    @Test
    fun `invalid json yields no accounts`() {
        assertTrue(LocalAccounts.load(dirWith("{ not json")).isEmpty())
        assertTrue(LocalAccounts.load(dirWith("""{"username":"a","token":"b"}""")).isEmpty())
        assertTrue(LocalAccounts.load(dirWith("""[{"username":"a"}]""")).isEmpty())
    }

    @Test
    fun `reads accounts in order and ignores unknown keys`() {
        val accounts = LocalAccounts.load(
            dirWith("""[{"username":"one","token":"t1","extra":1},{"username":"two","token":"t2"}]"""),
        )
        assertEquals(listOf(ConfiguredAccount("one", "t1"), ConfiguredAccount("two", "t2")), accounts)
    }

    @Test
    fun `blank entries dropped, deduped by username, capped at two`() {
        val accounts = LocalAccounts.load(
            dirWith(
                """
                [
                  {"username":"","token":"t0"},
                  {"username":"one","token":""},
                  {"username":"one","token":"t1"},
                  {"username":"one","token":"t1-dupe"},
                  {"username":"two","token":"t2"},
                  {"username":"three","token":"t3"}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(ConfiguredAccount("one", "t1"), ConfiguredAccount("two", "t2")), accounts)
    }
}
