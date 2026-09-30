package nl.jvaneyck.citywalk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single in-memory copy of the pins, shared by the activity and the tracking service
 * (both only touch it on the main thread), persisted to pins.json on every change.
 */
object PinStore {
    private var cache: MutableList<Pin>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "pins.json")

    fun photoDir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun photoFile(ctx: Context, pin: Pin): File? = pin.photo?.let { File(photoDir(ctx), it) }

    fun all(ctx: Context): List<Pin> = pins(ctx)

    fun get(ctx: Context, id: String): Pin? = pins(ctx).find { it.id == id }

    fun add(ctx: Context, pin: Pin) {
        pins(ctx) += pin
        save(ctx)
    }

    fun update(ctx: Context, pin: Pin) {
        val list = pins(ctx)
        val i = list.indexOfFirst { it.id == pin.id }
        if (i >= 0) {
            list[i] = pin
            save(ctx)
        }
    }

    fun remove(ctx: Context, id: String) {
        val list = pins(ctx)
        list.find { it.id == id }?.let { photoFile(ctx, it)?.delete() }
        list.removeAll { it.id == id }
        save(ctx)
    }

    private fun pins(ctx: Context): MutableList<Pin> = cache ?: load(ctx).also { cache = it }

    private fun load(ctx: Context): MutableList<Pin> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val arr = JSONArray(f.readText())
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Pin(
                id = o.getString("id"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                time = o.getLong("time"),
                note = o.optString("note"),
                photo = if (o.isNull("photo")) null else o.getString("photo"),
                type = o.optString("type", Pin.TYPE_PIN),
                received = o.optBoolean("received"),
                reached = o.optBoolean("reached"),
            )
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        pins(ctx).forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("lat", p.lat)
                    .put("lon", p.lon)
                    .put("time", p.time)
                    .put("note", p.note)
                    .put("photo", p.photo ?: JSONObject.NULL)
                    .put("type", p.type)
                    .put("received", p.received)
                    .put("reached", p.reached)
            )
        }
        val tmp = File(ctx.filesDir, "pins.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }
}
