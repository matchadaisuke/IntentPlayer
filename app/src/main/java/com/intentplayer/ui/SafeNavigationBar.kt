package com.intentplayer.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * App-wide bottom navigation wrapper.
 *
 * MainScreen intentionally passes zero content insets to avoid the old extra gap above
 * the bottom bar. The navigation bar itself, however, still needs to stay clear of the
 * Android gesture/navigation area. Keeping that responsibility here prevents the two
 * inset policies from being mixed again.
 */
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
    content: @Composable RowScope.() -> Unit
) {
    androidx.compose.material3.NavigationBar(
        modifier = modifier.navigationBarsPadding(),
        windowInsets = WindowInsets(0, 0, 0, 0),
        content = content
    )
}
