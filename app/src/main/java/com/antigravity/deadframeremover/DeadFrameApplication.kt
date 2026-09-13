package com.antigravity.deadframeremover

import android.app.Application
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.CrashHandler

class DeadFrameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            CrashHandler.install(this)
            AppLogManager.init(this)
        } catch (_: Throwable) {
        }
    }
}
