package com.bridge.app

import android.app.Application
import com.bridge.app.service.BridgeForegroundService

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeForegroundService.startService(this)
    }
}
