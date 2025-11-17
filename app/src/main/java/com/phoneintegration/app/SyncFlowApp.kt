package com.phoneintegration.app

import android.app.Application
import com.phoneintegration.app.deals.notify.DealNotificationScheduler

class SyncFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Schedule daily notification windows
        DealNotificationScheduler.scheduleDailyWork(this)
    }
}
