package org.maiwithu.maidroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.maiwithu.maidroid.oobe.OobeSetupManager

/** Keeps the first-run setup process alive across Activity configuration changes. */
internal class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    val setupManager = OobeSetupManager(application)

    override fun onCleared() {
        setupManager.close()
        super.onCleared()
    }
}
