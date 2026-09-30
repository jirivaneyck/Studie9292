package nl.jvaneyck.citywalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var btnTrack: MaterialButton
    private lateinit var infoView: TextView
    private lateinit var celebrateView: TextView

    private val routeLines = mutableListOf<Polyline>()
    private var activeLine: Polyline? = null
    private var distanceM = 0.0
    private var lastFix: GeoPoint? = null

    private val markers = mutableMapOf<String, Marker>()

    // State of the "add pin" dialog while camera / gallery is open
    private var addPinView: View? = null
    private var pendingPhoto: File? = null
    private var cameraTarget: File? = null
    private var centeredOnce = false

    // Own location updates while the map is visible, for goals and the "next goal" distance
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastFix = GeoPoint(loc.latitude, loc.longitude)
            if (!loc.hasAccuracy() || loc.accuracy <= GoalWatcher.RADIUS_M) {
                GoalWatcher.check(this@MainActivity, loc.latitude, loc.longitude)
            }
            updateInfo()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                enableMyLocation()
            } else {
                toast(R.string.need_location_permission)
            }
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val target = cameraTarget ?: return@registerForActivityResult
            cameraTarget = null
            if (ok && target.length() > 0) setPendingPhoto(target) else target.delete()
        }

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) copyFromGallery(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        // Android 15+ draws edge-to-edge: keep the buttons clear of the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        map = findViewById(R.id.map)
        infoView = findViewById(R.id.distance)
        celebrateView = findViewById(R.id.celebrate)
        btnTrack = findViewById(R.id.btnTrack)

        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)
        map.controller.setCenter(GeoPoint(52.3728, 4.8936))

        // Press and hold anywhere on the map to drop a pin there
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showAddPinDialog(p.latitude, p.longitude)
                return true
            }
        }))

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(myLocation)

        PinStore.all(this).forEach { addMarker(it) }

        btnTrack.setOnClickListener { toggleTracking() }
        findViewById<MaterialButton>(R.id.btnPin).setOnClickListener { startAddPin() }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { shareMyLocation() }
        celebrateView.setOnClickListener { hideCelebration() }

        requestPermissions()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        reloadRoute()
        updateTrackButton(TrackingService.isRunning)
        TrackingService.listener = { point -> onNewPoint(point) }
        TrackingService.stateListener = { running ->
            if (running) activeLine = null
            updateTrackButton(running)
        }
        GoalWatcher.onReached = { goal -> onGoalReached(goal) }
    }

    override fun onStop() {
        TrackingService.listener = null
        TrackingService.stateListener = null
        GoalWatcher.onReached = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (hasLocationPermission()) enableMyLocation()
    }

    override fun onPause() {
        fused.removeLocationUpdates(locationCallback)
        myLocation.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CENTER, 0, R.string.center_on_me)
        menu.add(0, MENU_CLEAR, 1, R.string.clear_route)
        if (Build.VERSION.SDK_INT >= 31) menu.add(0, MENU_LINKS, 2, R.string.open_links_setting)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_CENTER -> {
            myLocation.myLocation?.let { map.controller.animateTo(it) }
            myLocation.enableFollowLocation()
            true
        }
        MENU_CLEAR -> {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_route_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    TrackStore.clear(this)
                    reloadRoute()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
        MENU_LINKS -> {
            // Unverified (http / IP) links need a one-time opt-in to skip the browser
            val host = Uri.parse(BuildConfig.LINK_BASE).host.orEmpty()
            Toast.makeText(this, getString(R.string.open_links_help, host), Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------- incoming links & notifications ----------

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(GoalWatcher.EXTRA_REACHED_ID)?.let { id ->
            intent.removeExtra(GoalWatcher.EXTRA_REACHED_ID)
            PinStore.get(this, id)?.let { goal ->
                refreshMarker(goal)
                focus(goal)
                celebrate(goal)
            }
            return
        }
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            intent.data = null // don't handle it again
            receiveLink(uri)
        }
    }

    private fun receiveLink(uri: Uri) {
        val pin = ShareHelper.parseLink(uri)
        if (pin == null) {
            toast(R.string.invalid_link)
            return
        }
        PinStore.get(this, pin.id)?.let { existing ->
            focus(existing)
            showPinDetail(existing)
            return
        }
        val message = listOf(
            pin.note,
            if (pin.isGoal) getString(R.string.received_goal_msg) else "",
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        AlertDialog.Builder(this)
            .setTitle(if (pin.isGoal) R.string.received_goal_title else R.string.received_pin_title)
            .setMessage(message.ifBlank { null })
            .setPositiveButton(R.string.add_to_map) { _, _ ->
                PinStore.add(this, pin)
                addMarker(pin)
                focus(pin)
                updateInfo()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- goals ----------

    private fun onGoalReached(goal: Pin) {
        refreshMarker(goal)
        updateInfo()
        celebrate(goal)
    }

    private fun celebrate(goal: Pin) {
        celebrateView.text = listOf(getString(R.string.goal_reached_title), goal.note)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        celebrateView.animate().cancel()
        celebrateView.alpha = 0f
        celebrateView.scaleX = 0.5f
        celebrateView.scaleY = 0.5f
        celebrateView.visibility = View.VISIBLE
        celebrateView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()
        celebrateView.removeCallbacks(hideRunnable)
        celebrateView.postDelayed(hideRunnable, 6000)
        vibrate()
    }

    private val hideRunnable = Runnable { hideCelebration() }

    private fun hideCelebration() {
        celebrateView.removeCallbacks(hideRunnable)
        celebrateView.animate().alpha(0f).setDuration(300)
            .withEndAction { celebrateView.visibility = View.GONE }
            .start()
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 350), -1))
    }

    // ---------- permissions & location ----------

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun enableMyLocation() {
        myLocation.enableMyLocation()
        try {
            fused.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000L).build(),
                locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // permission revoked; the overlay simply shows nothing
        }
        if (!centeredOnce) {
            centeredOnce = true
            myLocation.enableFollowLocation()
            myLocation.runOnFirstFix {
                runOnUiThread { myLocation.myLocation?.let { map.controller.animateTo(it) } }
            }
        }
    }

    /** Gets a fresh GPS position (or the latest known one) and passes it to [onResult]. */
    private fun withCurrentLocation(onResult: (Double, Double) -> Unit) {
        if (!hasLocationPermission()) {
            requestPermissions()
            return
        }
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        onResult(loc.latitude, loc.longitude)
                    } else {
                        val p = myLocation.myLocation
                        if (p != null) onResult(p.latitude, p.longitude) else toast(R.string.no_location)
                    }
                }
                .addOnFailureListener { toast(R.string.no_location) }
        } catch (e: SecurityException) {
            toast(R.string.need_location_permission)
        }
    }

    private fun focus(pin: Pin) {
        myLocation.disableFollowLocation()
        map.controller.animateTo(GeoPoint(pin.lat, pin.lon), 18.0, 800L)
    }

    // ---------- tracking & route ----------

    private fun toggleTracking() {
        if (TrackingService.isRunning) {
            TrackingService.stop(this)
        } else {
            if (!hasLocationPermission()) {
                requestPermissions()
                return
            }
            activeLine = null
            TrackingService.start(this)
        }
    }

    private fun updateTrackButton(running: Boolean) {
        btnTrack.setText(if (running) R.string.stop_tracking else R.string.start_tracking)
    }

    private fun newLine(points: List<GeoPoint>): Polyline {
        val line = Polyline(map).apply {
            setPoints(points)
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route)
            outlinePaint.strokeWidth = 10f
            // No info bubble when the line is tapped
            setOnClickListener { _, _, _ -> false }
        }
        map.overlays.add(0, line) // below markers and the my-location dot
        routeLines += line
        return line
    }

    private fun reloadRoute() {
        routeLines.forEach { map.overlays.remove(it) }
        routeLines.clear()
        activeLine = null
        val segments = TrackStore.load(this)
        segments.forEach { newLine(it) }
        if (TrackingService.isRunning) activeLine = routeLines.lastOrNull()
        distanceM = TrackStore.distanceMeters(segments)
        updateInfo()
        map.invalidate()
    }

    private fun onNewPoint(point: GeoPoint) {
        val line = activeLine ?: newLine(emptyList()).also { activeLine = it }
        line.actualPoints.lastOrNull()?.let { distanceM += it.distanceToAsDouble(point) }
        line.addPoint(point)
        updateInfo()
        map.invalidate()
    }

    private fun updateInfo() {
        val lines = mutableListOf(getString(R.string.route_distance, distanceM / 1000.0))
        lastFix?.let { fix ->
            GoalWatcher.nearestOpen(this, fix.latitude, fix.longitude)?.let { (_, meters) ->
                lines += getString(R.string.goal_distance, formatDistance(meters))
            }
        }
        infoView.text = lines.joinToString("\n")
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m"
        else String.format(Locale.getDefault(), "%.1f km", meters / 1000)

    // ---------- pins ----------

    private fun markerIcon(pin: Pin): Drawable? = ContextCompat.getDrawable(
        this,
        when {
            pin.isGoal && pin.reached -> R.drawable.marker_goal_reached
            pin.isGoal -> R.drawable.marker_goal_open
            pin.received -> R.drawable.marker_pin_received
            else -> R.drawable.marker_pin_mine
        }
    )

    private fun addMarker(pin: Pin) {
        val marker = Marker(map).apply {
            position = GeoPoint(pin.lat, pin.lon)
            title = pin.note
            icon = markerIcon(pin)
            // Anchor on the pin's tip / the flag's pole
            if (pin.isGoal) setAnchor(0.25f, 0.9f) else setAnchor(Marker.ANCHOR_CENTER, 0.93f)
            setOnMarkerClickListener { _, _ ->
                PinStore.get(this@MainActivity, pin.id)?.let { showPinDetail(it) }
                true
            }
        }
        map.overlays.add(marker)
        markers[pin.id] = marker
        map.invalidate()
    }

    private fun refreshMarker(pin: Pin) {
        markers.remove(pin.id)?.let { map.overlays.remove(it) }
        addMarker(pin)
    }

    private fun startAddPin() {
        withCurrentLocation { lat, lon -> showAddPinDialog(lat, lon) }
    }

    private fun showAddPinDialog(lat: Double, lon: Double) {
        val view = layoutInflater.inflate(R.layout.dialog_add_pin, null)
        addPinView = view
        pendingPhoto = null

        view.findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener {
            val target = File(PinStore.photoDir(this), "${UUID.randomUUID()}.jpg")
            cameraTarget = target
            takePicture.launch(uriFor(target))
        }
        view.findViewById<MaterialButton>(R.id.btnGallery).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_pin)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val note = view.findViewById<EditText>(R.id.note).text.toString().trim()
                val pin = Pin(
                    id = UUID.randomUUID().toString(),
                    lat = lat,
                    lon = lon,
                    time = System.currentTimeMillis(),
                    note = note,
                    photo = pendingPhoto?.name,
                )
                pendingPhoto = null
                PinStore.add(this, pin)
                addMarker(pin)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingPhoto?.delete()
                pendingPhoto = null
            }
            .setOnDismissListener { addPinView = null }
            .show()
    }

    private fun setPendingPhoto(file: File) {
        if (pendingPhoto != file) pendingPhoto?.delete()
        pendingPhoto = file
        addPinView?.findViewById<ImageView>(R.id.photo)?.let { iv ->
            iv.setImageBitmap(loadBitmap(file, 1024))
            iv.visibility = View.VISIBLE
        }
    }

    private fun copyFromGallery(uri: Uri) {
        val target = File(PinStore.photoDir(this), "${UUID.randomUUID()}.jpg")
        Thread {
            val ok = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } != null
            } catch (e: Exception) {
                false
            }
            runOnUiThread { if (ok) setPendingPhoto(target) else target.delete() }
        }.start()
    }

    private fun showPinDetail(pin: Pin) {
        val view = layoutInflater.inflate(R.layout.dialog_pin_detail, null)
        val photoFile = PinStore.photoFile(this, pin)?.takeIf { it.exists() }
        if (photoFile != null) {
            view.findViewById<ImageView>(R.id.photo).apply {
                setImageBitmap(loadBitmap(photoFile, 1024))
                visibility = View.VISIBLE
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uriFor(photoFile), "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { startActivity(intent) }
                }
            }
        }
        view.findViewById<TextView>(R.id.note).apply {
            text = pin.note
            visibility = if (pin.note.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.time).text =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(pin.time))

        val title = when {
            pin.isGoal && pin.reached -> R.string.goal_title_reached
            pin.isGoal -> R.string.goal_title
            pin.received -> R.string.received_pin_title
            else -> R.string.pin_title
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.share_whatsapp) { _, _ -> chooseShareType(pin) }
            .setNeutralButton(R.string.delete) { _, _ -> confirmDeletePin(pin) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmDeletePin(pin: Pin) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_pin_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                PinStore.remove(this, pin.id)
                markers.remove(pin.id)?.let { map.overlays.remove(it) }
                map.invalidate()
                updateInfo()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- sharing ----------

    private fun chooseShareType(pin: Pin) {
        AlertDialog.Builder(this)
            .setTitle(R.string.share_how)
            .setItems(arrayOf(getString(R.string.share_as_pin), getString(R.string.share_as_goal))) { _, which ->
                sharePin(pin, asGoal = which == 1)
            }
            .show()
    }

    private fun sharePin(pin: Pin, asGoal: Boolean) {
        val link = ShareHelper.appLink(pin, asGoal)
        val caption = listOf(
            pin.note,
            getString(if (asGoal) R.string.caption_goal else R.string.caption_pin, link),
            getString(R.string.caption_maps, ShareHelper.mapLink(pin.lat, pin.lon)),
        ).filter { it.isNotBlank() }.joinToString("\n")
        val photo = PinStore.photoFile(this, pin)?.takeIf { it.exists() }
        if (photo != null) ShareHelper.shareImage(this, photo, caption)
        else ShareHelper.shareText(this, caption)
    }

    private fun shareMyLocation() {
        withCurrentLocation { lat, lon ->
            ShareHelper.shareText(this, getString(R.string.im_here, ShareHelper.mapLink(lat, lon)))
        }
    }

    // ---------- helpers ----------

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    /** Decodes a downscaled bitmap and applies the camera's EXIF rotation. */
    private fun loadBitmap(file: File, maxPx: Int): Bitmap? {
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

    companion object {
        private const val MENU_CENTER = 1
        private const val MENU_CLEAR = 2
        private const val MENU_LINKS = 3
    }
}
