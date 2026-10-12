package com.omlight.dl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * TEMPORARY debug hook for emulator verification (tap input broken).
 * Trigger: `adb shell am broadcast -a com.omlight.dl.DEBUG_ENQUEUE
 *   --esa paths <csv> --ela sizes <csv> --ela taken <csv>`
 * DELETE BEFORE RELEASE.
 */
class DebugEnqueueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val paths = intent.getStringArrayExtra("paths") ?: emptyArray()
        val sizes = intent.getLongArrayExtra("sizes") ?: longArrayOf()
        val taken = intent.getLongArrayExtra("taken") ?: longArrayOf()
        val items = paths.mapIndexed { i, p ->
            DlItem(p, sizes.getOrElse(i) { 0L }, taken.getOrElse(i) { 0L })
        }
        if (items.isNotEmpty()) DownloadService.enqueue(ctx, items)
        if (intent.getBooleanExtra("pause", false)) DownloadService.pause(ctx)
        if (intent.getBooleanExtra("resume", false)) DownloadService.resume(ctx)
    }

    companion object {
        const val ACTION = "com.omlight.dl.DEBUG_ENQUEUE"
    }
}
