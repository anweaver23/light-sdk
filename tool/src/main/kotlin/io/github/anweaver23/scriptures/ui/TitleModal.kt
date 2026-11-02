package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred

/** How long a tapped title stays up unless tapped away. */
private val TITLE_SHOWN = 4.seconds

/**
 * A page's whole title over the screen, for titles the top bar cuts short: tap the title to see it, tap
 * anywhere (or wait) to close. Uses LightOS's own transient overlay ([LightModalManager]).
 */
class TitleModal(private val lines: List<String>) : LightModal {
    private val closed = CompletableDeferred<Unit>()
    override val onExpired: () -> Unit = {}
    override fun dismiss() {
        closed.complete(Unit)
    }

    override suspend fun awaitDismiss() {
        closed.await()
    }

    @Composable
    override fun Content() {
        AppTheme {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .lightClickable { dismiss() }
                    .padding(horizontal = 3f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp(), Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                lines.forEachIndexed { i, line ->
                    AppText(
                        text = line,
                        variant = if (i == 0) LightTextVariant.Heading else LightTextVariant.Subheading,
                        lighten = i > 0,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    companion object {
        fun show(lines: List<String>) {
            val shown = lines.filter { it.isNotBlank() }.distinct()
            if (shown.isNotEmpty()) LightModalManager.show(TitleModal(shown), TITLE_SHOWN)
        }
    }
}
