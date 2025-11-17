package com.phoneintegration.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HeadlessSmsSendService", "respond-via-message triggered")
        return START_NOT_STICKY
    }
}
