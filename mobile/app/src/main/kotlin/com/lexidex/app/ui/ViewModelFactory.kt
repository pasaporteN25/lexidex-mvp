package com.lexidex.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** No Hilt in this pass (single read-only repository) - screens build their ViewModel with this. */
fun <T : ViewModel> viewModelFactoryOf(factory: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = factory() as VM
    }
