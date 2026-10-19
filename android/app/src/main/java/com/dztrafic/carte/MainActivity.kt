package com.dztrafic.carte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private const val MAP_FILE = "algeria-map.mbtiles"
private const val STYLE_FILE = "style.json"
private const val ALERT_SOURCE = "alerts"
private const val ALERT_LAYER = "alerts"
private const val PERMISSION_REQUEST = 1
private const val START_LATITUDE = 36.75
private const val START_LONGITUDE = 3.06
private const val START_ZOOM = 11.0
private const val MIN_ZOOM = 5.0
private const val TRACKING_ZOOM = 15.0
private const val SEARCH_ZOOM = 13.0
private const val COPY_BUFFER_BYTES = 1 shl 16
private const val MAX_GPS_AGE_MS = 300_000L
private const val VOYANT_MS = 5_000L
private const val CONFIRM_MS = 10_000L
private const val ALERT_ICON_DP = 34
private const val TAP_RADIUS_DP = 22
private const val ORANGE = 0xFFFF6D00.toInt()

private enum class LabelLanguage(val buttonText: String, val expression: String) {
    ARABIC("AR", """["coalesce",["get","name_ar"],["get","name"],["get","name_fr"]]"""),
    FRENCH("FR", """["coalesce",["get","name_fr"],["get","name"],["get","name_ar"]]"""),
}

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var speedText: TextView
    private lateinit var tripText: TextView
    private lateinit var gpsBanner: TextView
    private lateinit var voyant: TextView
    private lateinit var confirmBox: LinearLayout
    private lateinit var languageButton: Button
    private lateinit var modeButton: Button
    private lateinit var trackingButton: Button
    private var map: MapLibreMap? = null
    private var mapUrl = ""
    private var language = LabelLanguage.ARABIC
    private var courseUp = true
    private var keepScreenOn = false
    private var pendingConfirmation: Alert? = null

    private val hideVoyant = Runnable { voyant.visibility = View.GONE }
    private val hideConfirmation = Runnable { closeConfirmation() }

    private val tracker = object : TrackerListener {
        override fun onLocation(location: Location) = showLocation(location)

        override fun onAlerts(alerts: List<Alert>) = renderAlerts(alerts)

        override fun onProximity(alert: Alert, distanceMeters: Int) = showVoyant()

        override fun onConfirmationRequest(alert: Alert) = showConfirmation(alert)

        override fun onGpsState(enabled: Boolean) {
            gpsBanner.visibility = if (enabled) View.GONE else View.VISIBLE
        }

        override fun onTrackingState(active: Boolean) {
            trackingButton.text = if (active) "GPS ●" else "GPS ○"
        }

        override fun onMessage(text: String) {
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        AlertsRepository.init(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { message -> showStatus("Erreur carte : $message") }
        setContentView(buildLayout())
        requestPermissionsIfNeeded()
        prepareMapInBackground()
        Thread { PlaceSearch.load(applicationContext) }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        speedText = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val unitText = TextView(this).apply {
            text = "km/h"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        tripText = TextView(this).apply {
            text = "0.0 km · 0 min"
            textSize = 10f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        val speedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 10, Color.rgb(46, 51, 55))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(speedText)
            addView(unitText)
            addView(tripText)
            setOnLongClickListener {
                TrackerHub.startTrip()
                tripText.text = "0.0 km · 0 min"
                true
            }
        }
        speedText.text = "0"
        root.addView(speedPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(8), dp(8), 0, 0) })

        voyant = TextView(this).apply {
            text = "!"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(dp(3), Color.WHITE)
            }
            visibility = View.GONE
        }
        root.addView(voyant, FrameLayout.LayoutParams(dp(65), dp(65), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(8), dp(8), 0) })

        gpsBanner = TextView(this).apply {
            text = "📡 فعّل نظام تحديد الموقع (GPS)"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(220, 0, 0), 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        root.addView(gpsBanner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(8), dp(110), 0, 0) })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        confirmBox = buildConfirmBox()
        root.addView(confirmBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, dp(170), 0, 0) })

        val attribution = TextView(this).apply {
            text = "© OpenStreetMap contributors"
            textSize = 10f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        root.addView(attribution, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, 0, 0, dp(4)) })

        val reportButton = Button(this).apply {
            text = "⚠️  تبليغ"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = rounded(ORANGE, 14)
            setOnClickListener { onReportClicked() }
        }
        root.addView(reportButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(60),
            Gravity.BOTTOM or Gravity.START,
        ).apply { setMargins(dp(8), 0, 0, dp(36)) })

        languageButton = smallButton(language.buttonText) {
            language = if (language == LabelLanguage.ARABIC) LabelLanguage.FRENCH else LabelLanguage.ARABIC
            languageButton.text = language.buttonText
            reloadStyle()
        }
        modeButton = smallButton(modeText()) {
            courseUp = !courseUp
            modeButton.text = modeText()
            applyTracking()
        }
        trackingButton = smallButton("GPS ○") { toggleTracking() }
        val screenButton = smallButton("☀") { }
        screenButton.alpha = 0.5f
        screenButton.setOnClickListener { toggleKeepScreenOn(screenButton) }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val entries = listOf(
                smallButton("🔍") { showSearchDialog() },
                languageButton,
                modeButton,
                smallButton("◎") { applyTracking() },
                trackingButton,
                screenButton,
            )
            entries.forEach { addView(it, LinearLayout.LayoutParams(dp(54), dp(44)).apply { setMargins(0, 0, 0, dp(4)) }) }
        }
        root.addView(buttons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(8), dp(36)) })
        return root
    }

    private fun buildConfirmBox(): LinearLayout {
        val title = TextView(this).apply {
            text = "هل التبليغ صحيح؟"
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val yes = Button(this).apply {
            text = "👍 نعم"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(22, 138, 53), 10)
            setOnClickListener {
                pendingConfirmation?.let { AlertsRepository.confirm(it.id) }
                closeConfirmation()
            }
        }
        val no = Button(this).apply {
            text = "👎 لا"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(214, 40, 40), 10)
            setOnClickListener { closeConfirmation() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(yes, LinearLayout.LayoutParams(dp(110), dp(48)).apply { setMargins(dp(6), dp(10), dp(6), 0) })
            addView(no, LinearLayout.LayoutParams(dp(110), dp(48)).apply { setMargins(dp(6), dp(10), dp(6), 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14, Color.rgb(46, 51, 55))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            addView(title)
            addView(row)
        }
    }

    private fun modeText() = if (courseUp) "Cap" else "Nord"

    private fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasFineLocation()) missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) onLocationReady() else requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasFineLocation()) onLocationReady()
    }

    private fun onLocationReady() {
        map?.style?.let { enableLocation(it) }
        if (TrackingService.isEnabled(this)) TrackingService.start(this)
    }

    private fun toggleTracking() {
        if (TrackerHub.trackingActive) {
            TrackingService.stop(this)
        } else if (hasFineLocation()) {
            TrackingService.start(this)
        } else {
            requestPermissionsIfNeeded()
        }
    }

    private fun toggleKeepScreenOn(button: Button) {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        button.alpha = if (keepScreenOn) 1f else 0.5f
    }

    private fun prepareMapInBackground() {
        showStatus("Préparation de la carte…")
        Thread {
            val result = runCatching { copyMapFromAssets() }
            runOnUiThread {
                result
                    .onSuccess { file ->
                        mapUrl = "mbtiles://${file.absolutePath}"
                        mapView.getMapAsync { onMapReady(it) }
                    }
                    .onFailure { showStatus("Carte absente de l'APK : ${it.message}") }
            }
        }.start()
    }

    private fun copyMapFromAssets(): File {
        val target = File(filesDir, MAP_FILE)
        val marker = File(filesDir, "$MAP_FILE.stamp")
        val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (target.exists() && marker.exists() && marker.readText() == stamp) return target

        val temporary = File(filesDir, "$MAP_FILE.tmp")
        assets.open(MAP_FILE).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
        check(temporary.renameTo(target)) { "copie impossible" }
        marker.writeText(stamp)
        return target
    }

    private fun onMapReady(readyMap: MapLibreMap) {
        map = readyMap
        readyMap.setMinZoomPreference(MIN_ZOOM)
        readyMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(START_LATITUDE, START_LONGITUDE))
            .zoom(START_ZOOM)
            .build()
        readyMap.addOnMapClickListener { showAlertInfo(it) }
        reloadStyle()
    }

    private fun reloadStyle() {
        val currentMap = map ?: return
        val template = assets.open(STYLE_FILE).bufferedReader().use { it.readText() }
        val json = template
            .replace("{{MBTILES_URL}}", mapUrl)
            .replace("\"{{TEXT_FIELD}}\"", language.expression)
        currentMap.setStyle(Style.Builder().fromJson(json)) { style ->
            showStatus(null)
            AlertTypes.all.forEach { style.addImage(it, alertIcon(AlertTypes.emoji(it))) }
            renderAlerts(TrackerHub.alerts)
            enableLocation(style)
        }
    }

    private fun alertIcon(emoji: String): Bitmap {
        val size = dp(ALERT_ICON_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val border = size * 0.12f
        val radius = center - border / 2
        canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ORANGE
            style = Paint.Style.STROKE
            strokeWidth = border
        })
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(emoji, center, center - (glyph.descent() + glyph.ascent()) / 2, glyph)
        return bitmap
    }

    private fun renderAlerts(alerts: List<Alert>) {
        val features = JSONArray()
        alerts.forEach { alert ->
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(alert.longitude).put(alert.latitude)))
                    .put("properties", JSONObject()
                        .put("type", alert.type)
                        .put("title", alert.title)
                        .put("confirmations", alert.confirmations)),
            )
        }
        val collection = JSONObject().put("type", "FeatureCollection").put("features", features)
        map?.style?.getSourceAs<GeoJsonSource>(ALERT_SOURCE)?.setGeoJson(collection.toString())
    }

    private fun showAlertInfo(latLng: LatLng): Boolean {
        val currentMap = map ?: return false
        val point = currentMap.projection.toScreenLocation(latLng)
        val radius = dp(TAP_RADIUS_DP).toFloat()
        val area = RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
        val feature = currentMap.queryRenderedFeatures(area, ALERT_LAYER).firstOrNull() ?: return false
        val title = feature.getStringProperty("title") ?: return false
        val confirmations = feature.getNumberProperty("confirmations")?.toInt() ?: 1
        Toast.makeText(this, "$title ($confirmations)", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun enableLocation(style: Style) {
        if (!hasFineLocation()) return
        val component = map?.locationComponent ?: return
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(true)
                .build()
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.GPS
        applyTracking()
    }

    private fun applyTracking() {
        val component = map?.locationComponent ?: return
        if (!component.isLocationComponentActivated) return
        component.cameraMode = if (courseUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
        component.zoomWhileTracking(TRACKING_ZOOM)
    }

    private fun showLocation(location: Location) {
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        speedText.text = kmh.toString()
        val kilometers = TrackerHub.tripDistanceMeters / 1000.0
        val minutes = (SystemClock.elapsedRealtime() - TrackerHub.tripStartMs) / 60_000
        tripText.text = String.format(Locale.US, "%.1f km · %d min", kilometers, minutes)
    }

    private fun showVoyant() {
        voyant.visibility = View.VISIBLE
        handler.removeCallbacks(hideVoyant)
        handler.postDelayed(hideVoyant, VOYANT_MS)
    }

    private fun showConfirmation(alert: Alert) {
        if (confirmBox.visibility == View.VISIBLE) return
        pendingConfirmation = alert
        confirmBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideConfirmation)
        handler.postDelayed(hideConfirmation, CONFIRM_MS)
    }

    private fun closeConfirmation() {
        handler.removeCallbacks(hideConfirmation)
        confirmBox.visibility = View.GONE
        pendingConfirmation = null
    }

    private fun onReportClicked() {
        val location = TrackerHub.lastLocation
        if (location == null || System.currentTimeMillis() - location.time > MAX_GPS_AGE_MS) {
            Toast.makeText(this, "📡 فعّل نظام تحديد الموقع", Toast.LENGTH_LONG).show()
            return
        }
        val labels = AlertTypes.all.map { "${AlertTypes.emoji(it)}  ${AlertTypes.label(it)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر نوع الخطر")
            .setItems(labels) { _, which ->
                AlertsRepository.submitReport(AlertTypes.all[which], location.latitude, location.longitude)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "ابحث عن مدينة أو بلدية — Rechercher"
            setSingleLine()
        }
        val resultsAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this)
        list.adapter = resultsAdapter
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(input)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔍")
            .setView(container)
            .setNegativeButton("إغلاق", null)
            .create()

        var results = emptyList<PlacePoint>()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(text: Editable?) {
                results = PlaceSearch.search(text?.toString().orEmpty())
                resultsAdapter.clear()
                resultsAdapter.addAll(results.map { it.display })
                if (results.isEmpty() && !text.isNullOrEmpty() && !PlaceSearch.isLoaded) {
                    resultsAdapter.add("فهرس الأماكن غير متوفر")
                }
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            results.getOrNull(position)?.let { goTo(it) }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun goTo(place: PlacePoint) {
        val currentMap = map ?: return
        val component = currentMap.locationComponent
        if (component.isLocationComponentActivated) component.cameraMode = CameraMode.NONE
        currentMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(place.latitude, place.longitude), SEARCH_ZOOM)
        )
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        TrackerHub.add(tracker)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        tracker.onGpsState(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        tracker.onTrackingState(TrackerHub.trackingActive)
        renderAlerts(TrackerHub.alerts)
        TrackerHub.lastLocation?.let { showLocation(it) }
        AlertsRepository.sync()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        TrackerHub.remove(tracker)
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
