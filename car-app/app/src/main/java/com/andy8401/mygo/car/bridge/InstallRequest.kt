package com.andy8401.mygo.car.bridge

import androidx.compose.runtime.mutableStateOf

/**
 * Holds a pending install request from an App Link (mygocar://install?repo=...)
 * until the Compose UI is ready to display its result. Also carries the last
 * install message so SAF, App Link and manual repo installs share one surface.
 */
object InstallRequest {
    val message = mutableStateOf("")

    /** Consumed once by the UI after the bridge is initialized. */
    var pendingRepo: String? = null

    fun submitRepo(repo: String) {
        pendingRepo = repo
        message.value = "Pending install: $repo"
    }

    fun report(text: String) {
        message.value = text
    }
}
