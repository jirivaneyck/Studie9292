package nl.jvaneyck.citywalk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Checks positions against open goals and celebrates when one is reached. */
object GoalWatcher {
    const val RADIUS_M = 50.0
    const val EXTRA_REACHED_ID = "reached_goal_id"
    private const val CHANNEL_ID = "goals"

    /** Set while the map is visible; otherwise a notification is shown. */
    var onReached: ((Pin) -> Unit)? = null

    fun check(ctx: Context, lat: Double, lon: Double) {
        val reached = PinStore.all(ctx).filter {
            it.isGoal && !it.reached && distance(lat, lon, it) <= RADIUS_M
        }
        for (goal in reached) {
            val done = goal.copy(reached = true)
            PinStore.update(ctx, done)
            onReached?.invoke(done) ?: notify(ctx, done)
        }
    }

    /** Nearest goal not reached yet, with its distance in meters. */
    fun nearestOpen(ctx: Context, lat: Double, lon: Double): Pair<Pin, Double>? =
        PinStore.all(ctx)
            .filter { it.isGoal && !it.reached }
            .map { it to distance(lat, lon, it) }
            .minByOrNull { it.second }

    private fun distance(lat: Double, lon: Double, pin: Pin): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, pin.lat, pin.lon, out)
        return out[0].toDouble()
    }

    private fun notify(ctx: Context, goal: Pin) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.goals_channel), NotificationManager.IMPORTANCE_HIGH
            )
        )
        val open = PendingIntent.getActivity(
            ctx, goal.id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_REACHED_ID, goal.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flag)
            .setContentTitle(ctx.getString(R.string.goal_reached_title))
            .setContentText(goal.note.ifBlank { ctx.getString(R.string.goal_reached_generic) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(goal.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // notification permission revoked in the meantime
        }
    }
}
