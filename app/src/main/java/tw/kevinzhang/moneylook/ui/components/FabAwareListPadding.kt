package tw.kevinzhang.moneylook.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val FabAwareListBottomPadding = 96.dp

internal fun fabAwareListContentPadding(
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
): PaddingValues = PaddingValues(
    start = horizontal,
    top = top,
    end = horizontal,
    bottom = FabAwareListBottomPadding,
)
