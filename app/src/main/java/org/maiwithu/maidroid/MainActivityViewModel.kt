package org.maiwithu.maidroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.maiwithu.maidroid.oobe.OobeSetupManager

/** Keeps the first-run setup process alive across Activity configuration changes. */
internal class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    val setupManager = OobeSetupManager(
        application,
        container.settings,
        container.runtimePaths,
        container.runtimeReadiness,
        container.commandRunner
    )

    override fun onCleared() {
        setupManager.close()
        super.onCleared()
    }
}
