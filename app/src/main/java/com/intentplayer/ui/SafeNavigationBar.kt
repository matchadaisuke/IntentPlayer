package com.intentplayer.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Bottom navigation that keeps the app bar flush with the content while reserving the
 * Android navigation/gesture area inside the bar itself.
 *
 * The important detail is that system-bar space is handled by Material3's windowInsets,
 * not by an outer Modifier.padding/navigationBarsPadding. Outer padding increases the
 * bottom-bar layout size outside its painted background and can appear as an extra gap.
 */
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
    content: @Composable RowScope.() -> Unit
) {
    androidx.compose.material3.NavigationBar(
        modifier = modifier,
        windowInsets = NavigationBarDefaults.windowInsets,
        content = content
    )
}
