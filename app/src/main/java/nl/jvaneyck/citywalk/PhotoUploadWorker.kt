package nl.jvaneyck.citywalk

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Uploads pin photos that aren't on the server yet; WorkManager retries it when offline. */
class PhotoUploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        private const val TAG = "PhotoUpload"
        private const val WORK_NAME = "photo-upload"

        /** Call whenever there may be new photos to upload; runs as soon as there's internet. */
        fun enqueue(ctx: Context) {
            if (!CityWalkApi.enabled) return
            val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override fun doWork(): Result {
        var failed = false
        for (pin in PinStore.all(applicationContext)) {
            if (pin.photoUploaded) continue
            val id = pin.photoId ?: continue
            val file = PinStore.photoFile(applicationContext, pin)?.takeIf { it.exists() } ?: continue
            try {
                val jpeg = PhotoUtil.uploadJpeg(file) ?: continue
                CityWalkApi.uploadPhoto(id, jpeg)
                PinStore.modify(applicationContext, pin.id) { it.copy(photoUploaded = true) }
            } catch (e: Exception) {
                Log.w(TAG, "Upload of $id failed: ${e.message}")
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }
}
