// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** Vehicle enter and exit events from the phone's activity recognition, passed to [AlertService]. */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents) if (e.activityType == DetectedActivity.IN_VEHICLE)
            AlertService.onVehicle(e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
    }
}
