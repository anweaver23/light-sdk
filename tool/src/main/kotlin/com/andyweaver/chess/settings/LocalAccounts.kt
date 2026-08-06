package com.andyweaver.chess.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/** One account entry from the optional local accounts config file — see [LocalAccounts]. */
@Serializable
data class ConfiguredAccount(val username: String, val token: String)

/**
 * Optional bring-your-own multi-account config: a JSON file the user drops directly into
 * the app's private storage themselves (never bundled, never part of a build — the real
 * build server only extracts `tool/build.gradle.kts`, `tool/lighttool.toml` and
 * `tool/src/main/{kotlin,res,assets}`, so anything checked into source or read from
 * `local.properties`/`BuildConfig` would resolve empty in a real build; a runtime file read
 * is the only mechanism that actually works once the app is installed). Lets
 * `HomeScreenViewModel` skip the manual login screen and switch between up to 2 accounts by
 * tapping the title.
 *
 * Absent, unreadable, or invalid JSON → empty list, and the app behaves exactly as if this
 * feature didn't exist (manual login screen as normal). Nothing here may ever throw past
 * [load] or alter the login flow when the file isn't there.
 *
 * Place a JSON array at `<app files dir>/accounts.json`, e.g.:
 * ```json
 * [
 *   {"username": "yourhandle", "token": "lip_..."},
 *   {"username": "yourotherhandle", "token": "lip_..."}
 * ]
 * ```
 * Since this is the app's private internal storage, getting the file onto a device needs
 * either root or (for a debug-signed build) `adb shell run-as <applicationId>`:
 * ```
 * adb push accounts.json /sdcard/accounts.json
 * adb shell run-as com.andyweaver.chess cp /sdcard/accounts.json files/accounts.json
 * ```
 */
object LocalAccounts {

    const val FILE_NAME = "accounts.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** At most 2 accounts, deduplicated by username, blank entries dropped. Never throws. */
    fun load(filesDir: File): List<ConfiguredAccount> {
        val file = File(filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString<List<ConfiguredAccount>>(file.readText())
                .filter { it.username.isNotBlank() && it.token.isNotBlank() }
                .distinctBy { it.username }
                .take(MAX_ACCOUNTS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Cap on configured accounts — the title tap toggles between exactly two. */
    private const val MAX_ACCOUNTS = 2
}
