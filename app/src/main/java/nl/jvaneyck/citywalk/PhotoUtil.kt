package nl.jvaneyck.citywalk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object PhotoUtil {
    private const val UPLOAD_MAX_PX = 1600
    private const val UPLOAD_QUALITY = 85

    /** Decodes a downscaled bitmap (longest side about [maxPx]) with the camera's EXIF rotation applied. */
    fun loadBitmap(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * JPEG for upload: at most [UPLOAD_MAX_PX] on the longest side. Re-encoding also drops all
     * EXIF data, so the GPS position and camera details stay on the phone.
     */
    fun uploadJpeg(file: File): ByteArray? {
        val bmp = loadBitmap(file, UPLOAD_MAX_PX) ?: return null
        val longest = max(bmp.width, bmp.height)
        val scaled = if (longest > UPLOAD_MAX_PX) {
            val f = UPLOAD_MAX_PX.toFloat() / longest
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else bmp
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, UPLOAD_QUALITY, out)
            out.toByteArray()
        }
    }
}
