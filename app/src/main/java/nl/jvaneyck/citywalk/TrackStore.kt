package nl.jvaneyck.citywalk

import android.content.Context
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Route stored as CSV lines "time,lat,lon". A line "-" starts a new segment,
 * so stopping and restarting tracking doesn't draw a straight jump.
 */
object TrackStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "track.csv")

    @Synchronized
    fun append(ctx: Context, time: Long, lat: Double, lon: Double) {
        file(ctx).appendText("$time,$lat,$lon\n")
    }

    @Synchronized
    fun newSegment(ctx: Context) {
        file(ctx).appendText("-\n")
    }

    @Synchronized
    fun load(ctx: Context): MutableList<MutableList<GeoPoint>> {
        val segments = mutableListOf<MutableList<GeoPoint>>()
        val f = file(ctx)
        if (!f.exists()) return segments
        var current = mutableListOf<GeoPoint>()
        f.forEachLine { line ->
            if (line == "-") {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            } else {
                val parts = line.split(',')
                if (parts.size == 3) {
                    val lat = parts[1].toDoubleOrNull()
                    val lon = parts[2].toDoubleOrNull()
                    if (lat != null && lon != null) current += GeoPoint(lat, lon)
                }
            }
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    fun distanceMeters(segments: List<List<GeoPoint>>): Double =
        segments.sumOf { seg -> seg.zipWithNext { a, b -> a.distanceToAsDouble(b) }.sum() }
}
