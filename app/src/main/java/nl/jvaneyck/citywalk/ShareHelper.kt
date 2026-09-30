package nl.jvaneyck.citywalk

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

object ShareHelper {
    private const val WHATSAPP = "com.whatsapp"
    private const val MAX_NOTE = 300

    fun mapLink(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

    /** Link that opens City Walk on the recipient's phone (via the page on our web server). */
    fun appLink(pin: Pin, asGoal: Boolean): String =
        Uri.parse(BuildConfig.LINK_BASE).buildUpon()
            .appendQueryParameter("id", if (asGoal) "${pin.id}-goal" else pin.id)
            .appendQueryParameter("lat", String.format(Locale.US, "%.6f", pin.lat))
            .appendQueryParameter("lon", String.format(Locale.US, "%.6f", pin.lon))
            .appendQueryParameter("type", if (asGoal) Pin.TYPE_GOAL else Pin.TYPE_PIN)
            .apply { if (pin.note.isNotBlank()) appendQueryParameter("note", pin.note.take(MAX_NOTE)) }
            .build()
            .toString()

    /**
     * Parses a shared link: either the web link (http://host/citywalk?...) or the
     * citywalk://pin?... link the web page forwards to. Returns null if it isn't valid.
     */
    fun parseLink(uri: Uri): Pin? {
        val lat = uri.getQueryParameter("lat")?.toDoubleOrNull() ?: return null
        val lon = uri.getQueryParameter("lon")?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val type = if (uri.getQueryParameter("type") == Pin.TYPE_GOAL) Pin.TYPE_GOAL else Pin.TYPE_PIN
        val id = uri.getQueryParameter("id")
            ?.filter { it.isLetterOrDigit() || it == '-' }
            ?.take(64)
            ?.takeIf { it.isNotEmpty() }
            ?: String.format(Locale.US, "%s-%.6f-%.6f", type, lat, lon)
        return Pin(
            id = id,
            lat = lat,
            lon = lon,
            time = System.currentTimeMillis(),
            note = uri.getQueryParameter("note")?.take(MAX_NOTE).orEmpty(),
            photo = null,
            type = type,
            received = true,
        )
    }

    fun shareText(ctx: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        send(ctx, intent)
    }

    fun shareImage(ctx: Context, image: File, caption: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", image)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        send(ctx, intent)
    }

    /** Send straight to WhatsApp; fall back to the normal share sheet if it isn't installed. */
    private fun send(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(Intent(intent).setPackage(WHATSAPP))
        } catch (e: ActivityNotFoundException) {
            ctx.startActivity(Intent.createChooser(intent, null))
        }
    }
}
