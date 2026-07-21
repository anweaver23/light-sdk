package com.andyweaver.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

private const val EDGE_UNITS = 1f

/**
 * The logged-out login gate. Because LightOS blocks launching a browser (no OAuth
 * redirect is possible in-app), login is a user-supplied Lichess personal-access token:
 * brief instructions to create one on another device, then a text editor to enter it.
 *
 * Rendered inline by [HomeScreen] when there is no stored session (rather than a pushed
 * screen, to avoid a navigate-back loop against the login wall). [onSubmit] validates and
 * persists the token; a failure surfaces in [loginError].
 */
@Composable
fun LoginPane(
    tokenState: TextFieldState,
    loginError: String?,
    onSubmit: (String) -> Unit,
    onDismissError: () -> Unit,
    onStartInPerson: () -> Unit,
) {
    var entering by remember { mutableStateOf(false) }

    if (entering) {
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Lichess token",
            state = tokenState,
            onSubmit = { onSubmit(it.toString()) },
            onBack = { entering = false },
            keyboardOptionsFlow = keyboardOptions,
            submitLabel = "LOG IN",
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                center = LightTopBarCenter.Text("Log in"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = EDGE_UNITS.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Log in with a Lichess token to play online. " +
                            "You can also play in-person games without logging in.",
                        variant = LightTextVariant.Copy,
                    )
                    LightText(
                        text = "1. On another device, open:",
                        variant = LightTextVariant.Detail,
                    )
                    LightText(
                        text = "lichess.org/account/oauth/token/create",
                        variant = LightTextVariant.Detail,
                    )
                    LightText(
                        text = "2. Enable these scopes: Play games with the Board API, " +
                            "Read incoming challenges, Create/accept challenges, Read followed players.",
                        variant = LightTextVariant.Detail,
                    )
                    LightText(
                        text = "3. Create the token, then enter it here.",
                        variant = LightTextVariant.Detail,
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    // Bottom-left: play without an account. Bottom-right: the login path.
                    LightBarButton.Text(text = "PLAY", onClick = onStartInPerson),
                    LightBarButton.Text(text = "LOGIN", onClick = { entering = true }),
                ),
            )
        }
    }

    loginError?.let { LightFullscreenModal(message = it, onClose = onDismissError) }
}
