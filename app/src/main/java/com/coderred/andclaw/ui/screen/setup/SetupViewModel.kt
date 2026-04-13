package com.coderred.andclaw.ui.screen.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.data.SetupState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndClawApp
    private val setupManager = app.setupManager
    private val prefs = app.preferencesManager
    private val prorootManager = app.prorootManager

    val state: StateFlow<SetupState> = setupManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SetupState())

    val availableStorageMb: Long
        get() = prorootManager.getAvailableStorageMb()

    val hasEnoughStorage: Boolean
        get() = prorootManager.hasEnoughStorage()

    val isProrootAvailable: Boolean
        get() = prorootManager.isProrootAvailable

    val isAlreadySetup: Boolean
        get() = prorootManager.isFullySetup

    fun startSetup() {
        viewModelScope.launch {
            val success = setupManager.runFullSetup()
            if (success) {
                prefs.setSetupComplete(true)
            }
        }
    }
}
