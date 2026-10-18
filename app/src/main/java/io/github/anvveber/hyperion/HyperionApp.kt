package io.github.anvveber.hyperion

import android.app.Application

class HyperionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash anywhere after this is kept for the next launch.
        CrashLog.install(this)
    }
}
