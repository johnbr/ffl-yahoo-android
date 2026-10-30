package io.github.johnbr.fflyahoo.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import io.github.johnbr.fflyahoo.core.present.Present

/** The card's own palette (`--ffl-*` and the slot chips), in both themes. */
@Immutable
data class FflColors(
    val winner: Color,
    val play: Color,
    val up: Color,
    val down: Color,
    val flat: Color,
    val liveTint: Color,
    val scoreFlash: Color,
    val live: Color,
    val redZone: Color,
    val qb: Color,
    val rb: Color,
    val wr: Color,
    val te: Color,
    val k: Color,
    val def: Color,
) {
    fun slot(kind: Present.Slot, flex: Color, bench: Color): Color = when (kind) {
        Present.Slot.QB -> qb
        Present.Slot.RB -> rb
        Present.Slot.WR -> wr
        Present.Slot.TE -> te
        Present.Slot.K -> k
        Present.Slot.DEF -> def
        Present.Slot.BENCH -> bench
        Present.Slot.FLEX -> flex
    }
}

private val LightFfl = FflColors(
    winner = Color(0xFFB8860B), // the card's #fbc02d reads poorly on white; a deeper gold keeps contrast
    play = Color(0xFF0277BD), // a matchup's last scoring play; the card's --info-color, a shade deeper for white
    up = Color(0xFF2E7D32),
    down = Color(0xFFC62828),
    flat = Color(0xFF757575),
    liveTint = Color(0x1A43A047),
    scoreFlash = Color(0xFFC62828),
    live = Color(0xFF2E7D32),
    redZone = Color(0xFFD32F2F),
    qb = Color(0xFF2F6FDB),
    rb = Color(0xFF1E9E52),
    wr = Color(0xFFD9781A),
    te = Color(0xFFC2549A),
    k = Color(0xFF8A5CD6),
    def = Color(0xFF2E8C85),
)

private val DarkFfl = FflColors(
    winner = Color(0xFFFBC02D),
    play = Color(0xFF4FC3F7),
    up = Color(0xFF66BB6A),
    down = Color(0xFFEF5350),
    flat = Color(0xFF9E9E9E),
    liveTint = Color(0x2643A047),
    scoreFlash = Color(0xFFC62828),
    live = Color(0xFF66BB6A),
    redZone = Color(0xFFEF5350),
    qb = Color(0xFF5B9BFF),
    rb = Color(0xFF3EC46D),
    wr = Color(0xFFFFA23E),
    te = Color(0xFFEF7FC0),
    k = Color(0xFFB98CFF),
    def = Color(0xFF8FD6D0),
)

val LocalFfl = staticCompositionLocalOf { LightFfl }

private val LightScheme: ColorScheme = lightColorScheme(primary = Color(0xFF1B5E20), secondary = Color(0xFF4E6352))
private val DarkScheme: ColorScheme = darkColorScheme(primary = Color(0xFF8BD58F), secondary = Color(0xFFB5CCB7))

/**
 * Material's body and label styles carry fixed line heights (24sp for body text), which every smaller
 * line in the app inherited — a 11sp stat line spaced like a 24sp one, roughly double the air the
 * card gives it. Proportional line heights keep each line as tall as its own type needs.
 */
private val Compact: Typography = Typography().let { t ->
    fun TextStyle.tight() = copy(lineHeight = 1.3.em)
    t.copy(
        bodyLarge = t.bodyLarge.tight(),
        bodyMedium = t.bodyMedium.tight(),
        bodySmall = t.bodySmall.tight(),
        labelLarge = t.labelLarge.tight(),
        labelMedium = t.labelMedium.tight(),
        labelSmall = t.labelSmall.tight(),
    )
}

@Composable
fun FflTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalFfl provides if (dark) DarkFfl else LightFfl) {
        MaterialTheme(colorScheme = scheme, typography = Compact, content = content)
    }
}
