package com.xlollx.songport.ytmbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/**
 * The liability notice. Every sign-in (YouTube Music or Amazon Music, started here or by Songport)
 * requires the user to have accepted it once; the acceptance is stored on this device only.
 */
object Disclaimer {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bridge_consent", Context.MODE_PRIVATE)

    fun accepted(ctx: Context): Boolean = prefs(ctx).getBoolean("accepted", false)

    fun accept(ctx: Context) { prefs(ctx).edit().putBoolean("accepted", true).apply() }

    /** Runs [onAccepted] at once if already accepted, otherwise after the user accepts; cancelling finishes the activity. */
    fun require(activity: Activity, onAccepted: () -> Unit) {
        if (accepted(activity)) { onAccepted(); return }
        AlertDialog.Builder(activity)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ -> accept(activity); onAccepted() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> activity.setResult(Activity.RESULT_CANCELED); activity.finish() }
            .show()
    }
}
