package org.debs.mayday.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.model.AppDensity

@Immutable
data class MaydayDensity(
    val screenPadding: Dp,
    val heroPadding: Dp,
    val sectionGap: Dp,
    val cardPadding: Dp,
    val blockGap: Dp,
    val actionHeight: Dp,
)

val CompactMaydayDensity = MaydayDensity(
    screenPadding = 16.dp,
    heroPadding = 18.dp,
    sectionGap = 14.dp,
    cardPadding = 16.dp,
    blockGap = 12.dp,
    actionHeight = 52.dp,
)

val ComfortableMaydayDensity = MaydayDensity(
    screenPadding = 22.dp,
    heroPadding = 24.dp,
    sectionGap = 18.dp,
    cardPadding = 20.dp,
    blockGap = 16.dp,
    actionHeight = 58.dp,
)

val LocalMaydayDensity = staticCompositionLocalOf { ComfortableMaydayDensity }

fun resolveMaydayDensity(density: AppDensity): MaydayDensity {
    return when (density) {
        AppDensity.COMPACT -> CompactMaydayDensity
        AppDensity.COMFORTABLE -> ComfortableMaydayDensity
    }
}
