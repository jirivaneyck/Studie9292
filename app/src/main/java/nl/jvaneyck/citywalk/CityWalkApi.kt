package nl.jvaneyck.citywalk

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Blocking client for the City Walk API on the home server (server/api). Call off the main thread. */
object CityWalkApi {
    private const val TIMEOUT_MS = 20_000
    private val PHOTO_ID = Regex("^[A-Za-z0-9-]{8,64}$")

    val enabled get() = BuildConfig.API_TOKEN.isNotEmpty()

    fun isValidPhotoId(id: String) = PHOTO_ID.matches(id)

    fun photoUrl(id: String) = "${BuildConfig.API_BASE}/photo/$id.jpg"

    fun uploadPhoto(id: String, jpeg: ByteArray) {
        request("PUT", "${BuildConfig.API_BASE}/api/photos/$id", "image/jpeg", jpeg)
    }

    /** Uploads a trip and returns the URL of its web page. */
    fun saveTrip(trip: JSONObject): String {
        val response = request(
            "POST", "${BuildConfig.API_BASE}/api/trips", "application/json",
            trip.toString().toByteArray()
        )
        return JSONObject(response).getString("url")
    }

    /** Downloads a shared photo into [target]; returns false if it isn't on the server (yet). */
    fun downloadPhoto(id: String, target: File): Boolean {
        val conn = URL(photoUrl(id)).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != 200) return false
            val tmp = File(target.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return tmp.renameTo(target)
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, url: String, type: String, body: ByteArray): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
        conn.setRequestProperty("Content-Type", type)
        conn.setFixedLengthStreamingMode(body.size)
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
