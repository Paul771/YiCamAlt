// FILE: YiCamAltApp.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Application entry point that initializes Hilt DI graph and Timber logging.
//   SCOPE: Hilt bootstrapping, Timber plant, global config init.
//   DEPENDS: M-CONFIG
//   LINKS: M-CONFIG
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

// START_MODULE_MAP
//   YiCamAltApp - Hilt-annotated Application; plants Timber debug tree.
// END_MODULE_MAP

@HiltAndroidApp
class YiCamAltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // START_BLOCK_INIT_APP
        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String =
                    "YiCamAlt/${super.createStackElementTag(element)}"
            })
        }
        // END_BLOCK_INIT_APP
    }
}