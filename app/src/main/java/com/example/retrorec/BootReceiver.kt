package com.example.retrorec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 再起動・アプリ更新のあと、録音を続けたい状態なら「タップで再開」通知を出す */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (RecorderService.wantRunning(context)) {
                    RecorderService.postResumeNotification(
                        context, "再起動のため録音が止まっています。タップで再開"
                    )
                }
            }
        }
    }
}
