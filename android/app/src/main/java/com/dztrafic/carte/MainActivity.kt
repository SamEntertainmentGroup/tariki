package com.dztrafic.carte

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

private const val MAP_FILE = "algeria-map.mbtiles"
private const val STYLE_FILE = "style.json"
private const val LOCATION_REQUEST = 1
private const val START_LATITUDE = 36.75
private const val START_LONGITUDE = 3.06
private const val START_ZOOM = 11.0
private const val TRACKING_ZOOM = 15.0
private const val COPY_BUFFER_BYTES = 1 shl 16

private enum class LabelLanguage(val buttonText: String, val expression: String) {
    ARABIC("AR", """["coalesce",["get","name_ar"],["get","name"],["get","name_fr"]]"""),
    FRENCH("FR", """["coalesce",["get","name_fr"],["get","name"],["get","name_ar"]]"""),
}

class MainActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var languageButton: Button
    private lateinit var modeButton: Button
    private var map: MapLibreMap? = null
    private var mapUrl = ""
    private var language = LabelLanguage.ARABIC
    private var courseUp = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { message -> showStatus("Erreur carte : $message") }
        setContentView(buildLayout())
        requestLocationPermission()
        prepareMapInBackground()
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(8), dp(8), dp(8), 0) })

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

        val recenterButton = Button(this).apply {
            text = "◎"
            setOnClickListener { applyTracking() }
        }
        modeButton = Button(this).apply {
            text = modeText()
            setOnClickListener {
                courseUp = !courseUp
                text = modeText()
                applyTracking()
            }
        }
        languageButton = Button(this).apply {
            text = language.buttonText
            setOnClickListener {
                language = if (language == LabelLanguage.ARABIC) LabelLanguage.FRENCH else LabelLanguage.ARABIC
                text = language.buttonText
                reloadStyle()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(languageButton)
            addView(modeButton)
            addView(recenterButton)
        }
        root.addView(buttons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(8), dp(36)) })
        return root
    }

    private fun modeText() = if (courseUp) "Cap" else "Nord"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == LOCATION_REQUEST && granted) {
            map?.style?.let { enableLocation(it) }
        }
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
        readyMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(START_LATITUDE, START_LONGITUDE))
            .zoom(START_ZOOM)
            .build()
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
            enableLocation(style)
        }
    }

    private fun enableLocation(style: Style) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
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

    override fun onStart() {
        super.onStart()
        mapView.onStart()
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
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
