package com.andyweaver.chess

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Push-notification plumbing (app side).
 *
 * ARCHITECTURE (see CLAUDE.md "Notifications & Push"): this app never holds an open connection to
 * Lichess in the background — that would drain the battery, against the LP ethos. Instead:
 *
 *   Lichess  ──(watched by)──►  relay service (external, developer-run)  ──(UnifiedPush)──►  LightOS  ──►  this tool
 *
 * The relay watches the user's games via the Lichess streaming API and, when it becomes the user's
 * move or a game ends, sends a push through UnifiedPush, routed via LightOS's notification channel.
 *
 * STATUS: the relay itself is explicitly DEFERRED (not built) — so push can't be exercised end-to-end
 * yet. What's wired here is the app-side contract:
 *   1. [onToolCreate] receives LightOS registration data (the UnifiedPush endpoint + keys). The relay
 *      needs this to target this device, so this is where we'd POST it to the relay/app server.
 *   2. [onPushNotification] receives a push payload. Per the notification policy we only expect pushes
 *      for "it's your move" and game-ending events (never ambient events). On receipt we should refresh
 *      state and ensure any stale "your move" marker is cleared once the move is made (stale markers
 *      erode trust in the signal).
 *
 * TODO(relay): stand up the relay, define the push payload shape (e.g. { gameId, kind }), POST the
 * registration data to it, and wire [onPushNotification] to surface/clear the LightOS notification.
 * The notifications on/off toggle already lives in Settings (ChessSettings.notificationsEnabled).
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        serverData.collect { data ->
            // TODO(relay): POST this registration data (UnifiedPush endpoint + VAPID key) to the
            // developer-run relay so it can push "your move" / game-over events to this device.
            Log.d("ToolEntryPoint", "LightOS registration data: $data")
        }
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) {
        // TODO(relay): parse the relay's payload; if it's a "your move"/game-over signal, refresh the
        // affected game and clear the marker once the user has moved. Only these event kinds are ever
        // expected here (never ambient events) per the notification policy.
        Log.d("ToolEntryPoint", "received push notification (${data.size} bytes): ${data.decodeToString()}")
    }
}
