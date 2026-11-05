package com.xencode.feature.settings.presentation.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

/**
 * Backward-compatible alias: the implementation has moved to [com.xencode.core.ui.rememberSheetFlingFix],
 * and is integrated automatically in [com.xencode.core.ui.AdaptiveModalBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetFlingFix(sheetState: SheetState): NestedScrollConnection =
    com.xencode.core.ui.rememberSheetFlingFix(sheetState)
