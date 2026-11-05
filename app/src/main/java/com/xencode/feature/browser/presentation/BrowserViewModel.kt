package com.xencode.feature.browser.presentation

import androidx.lifecycle.ViewModel
import com.xencode.feature.agent.domain.tool.browser.BrowserManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val browserManager: BrowserManager
) : ViewModel()
