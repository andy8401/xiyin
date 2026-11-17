package com.andy8401.mygo.car

/**
 * Version identifiers shown in the car UI.
 *
 * [Name] is this Android app's own version, read from the build so it can never
 * drift from `versionName`. [GoFacade] is something else entirely: it is the
 * *host capability* version the Go facade reports to plugins, which they compare
 * against `minAppVersion` (ximalaya-web needs >= 0.1.20). The two must not be
 * conflated — bumping the app version must not change the facade version.
 */
object BuildVersion {
    val Name: String = BuildConfig.VERSION_NAME

    /** Facade host-capability version; see NOTICE.md before changing it. */
    const val GoFacade = "0.1.25"

    /** AGPL-3.0 fork provenance, shown in Settings > About. */
    const val UpstreamRepo = "egoist/mygo-music"
    const val ForkRepo = "andy8401/mygo-music"
    const val License = "AGPL-3.0"
}
