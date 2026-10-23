package com.dztrafic.carte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
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
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import kotlin.math.roundToInt

private const val MAP_FILE = "algeria-map.mbtiles"
private const val STYLE_FILE = "style.json"
private const val ALERT_SOURCE = "alerts"
private const val ALERT_LAYER = "alerts"
private const val ALERT_CLUSTER_LAYER = "alerts-cluster"
private const val ROUTE_SOURCE = "route"
private const val MIXED_ICON = "mixed"
private const val GROUP_RADIUS_METERS = 50.0
private const val RECENTER_DELAY_MS = 10_000L
private const val MIN_BEARING_SPEED = 1.5f
private const val CAMERA_ANIM_MS = 900
private const val CLUSTER_TAP_ZOOM_STEP = 2.0
private const val PERMISSION_REQUEST = 1
private const val START_LATITUDE = 36.1911
private const val START_LONGITUDE = 5.4137
private const val START_ZOOM = 12.0
private const val MIN_ZOOM = 5.0
private const val TRACKING_ZOOM = 15.0
private const val SEARCH_ZOOM = 13.0
private const val COPY_BUFFER_BYTES = 1 shl 16
private const val MAX_GPS_AGE_MS = 300_000L
private const val VOYANT_MS = 5_000L
private const val CONFIRM_MS = 10_000L
private const val POPUP_AUTO_CLOSE_MS = 30_000L
private const val STATUS_TICK_MS = 2_000L
private const val SEARCH_STALE_MS = 20_000L
private const val COMMENT_MAX_LENGTH = 140
private const val ALERT_ICON_DP = 48
private const val TAP_RADIUS_DP = 30
private const val ORANGE = 0xFFFF6D00.toInt()

private enum class LabelLanguage(val buttonText: String, val expression: String) {
    ARABIC("AR", """["coalesce",["get","name_ar"],["get","name"],["get","name_fr"]]"""),
    FRENCH("FR", """["coalesce",["get","name_fr"],["get","name"],["get","name_ar"]]"""),
}

private enum class SystemState { OK, NO_GPS, NO_NOTIFICATIONS, NO_BATTERY, SEARCHING }

private class AlertGroup(val latitude: Double, val longitude: Double) {
    val counts = LinkedHashMap<String, Int>()
    val comments = LinkedHashSet<String>()
    var total = 0

    fun add(alert: Alert) {
        val type = if (alert.type in AlertTypes.all) alert.type else AlertTypes.DANGER
        val weight = alert.confirmations.coerceAtLeast(1)
        counts[type] = (counts[type] ?: 0) + weight
        total += weight
        alert.comments.forEach { if (it.isNotBlank() && comments.size < 5) comments.add(it) }
    }
}

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var speedText: TextView
    private lateinit var confirmTitle: TextView
    private lateinit var gpsBanner: TextView
    private lateinit var voyant: TextView
    private lateinit var confirmBox: LinearLayout
    private lateinit var languageButton: Button
    private lateinit var menuButton: Button
    private lateinit var extraButtons: LinearLayout
    private var map: MapLibreMap? = null
    private var mapUrl = ""
    private var language = LabelLanguage.ARABIC
    private var pendingConfirmation: Alert? = null
    private var followLocation = true
    private var positioned = false
    private var mapBearing = 0.0
    private var systemState = SystemState.OK
    private var routingGraph: RoutingGraph? = null
    private var routePlaces: List<RoutePlace> = emptyList()
    private var routeFrom: RoutePlace? = null
    private var routeTo: RoutePlace? = null
    private var routeShown = false
    private var routeBusy = false
    private lateinit var routeCard: LinearLayout
    private lateinit var routeCardTitle: TextView
    private lateinit var routeCardInfo: TextView
    private var askedLocation = false
    private var askedNotifications = false

    private val hideVoyant = Runnable { voyant.visibility = View.GONE }
    private val hideConfirmation = Runnable { closeConfirmation() }
    private val recenter = Runnable { resumeFollowing() }
    private val statusTick = object : Runnable {
        override fun run() {
            refreshGpsBanner()
            handler.postDelayed(this, STATUS_TICK_MS)
        }
    }

    private val tracker = object : TrackerListener {
        override fun onLocation(location: Location) = showLocation(location)

        override fun onAlerts(alerts: List<Alert>) = renderAlerts(alerts)

        override fun onProximity(alert: Alert, distanceMeters: Int) = showVoyant()

        override fun onConfirmationRequest(alert: Alert) = showConfirmation(alert)

        override fun onGpsState(enabled: Boolean) {
            refreshGpsBanner()
        }

        override fun onMessage(text: String) {
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        AlertsRepository.init(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { message -> showStatus("Erreur carte : $message") }
        setContentView(buildLayout())
        requestPermissionsIfNeeded()
        prepareMapInBackground()
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
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setTextColor(Color.rgb(25, 54, 46))
        background = rounded(Color.WHITE, 16, Color.rgb(225, 233, 229))
        elevation = dp(5).toFloat()
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun isDeviceGpsEnabled(): Boolean = runCatching {
        (getSystemService(LOCATION_SERVICE) as LocationManager)
            .isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)

    private fun notificationsEnabled(): Boolean = runCatching {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }.getOrDefault(true)

    private fun batteryOk(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    private fun locationOk() = hasFineLocation() && isDeviceGpsEnabled()

    private fun notifOk() = notificationsEnabled()

    /** Les trois autorisations exigées : localisation (GPS), notifications et optimisation batterie. */
    private fun allOk() = locationOk() && notifOk() && batteryOk()

    private fun hasFreshFix(): Boolean {
        val location = TrackerHub.lastLocation ?: return false
        return System.currentTimeMillis() - location.time <= SEARCH_STALE_MS
    }

    /** Message d'état en haut : rouge si le GPS ou les notifications manquent (obligatoires), bleu pendant la recherche du signal. */
    private fun refreshGpsBanner() {
        if (!::gpsBanner.isInitialized) return
        val arabic = language == LabelLanguage.ARABIC
        systemState = when {
            !locationOk() -> SystemState.NO_GPS
            !notifOk() -> SystemState.NO_NOTIFICATIONS
            !batteryOk() -> SystemState.NO_BATTERY
            allOk() && !hasFreshFix() -> SystemState.SEARCHING
            else -> SystemState.OK
        }
        val red = Color.rgb(183, 43, 39)
        val blue = Color.rgb(0, 108, 255)
        val (text, color) = when (systemState) {
            SystemState.NO_GPS -> Pair(
                if (arabic) "فعّل نظام تحديد الموقع (GPS) 📡" else "📡 Activez la localisation (GPS)", red,
            )
            SystemState.NO_NOTIFICATIONS -> Pair(
                if (arabic) "فعّل الإشعارات 🔔" else "🔔 Activez les notifications", red,
            )
            SystemState.NO_BATTERY -> Pair(
                if (arabic) "عطّل تحسين البطارية 🔋" else "🔋 Désactivez l'optimisation batterie", red,
            )
            SystemState.SEARCHING -> Pair(
                if (arabic) "جارٍ البحث عن إشارة GPS... 📡" else "📡 Recherche du signal GPS…", blue,
            )
            SystemState.OK -> {
                gpsBanner.visibility = View.GONE
                return
            }
        }
        gpsBanner.text = text
        gpsBanner.background = rounded(color, 16)
        gpsBanner.visibility = View.VISIBLE
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun fixNotifications() {
        val needsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntime && (!askedNotifications ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        } else {
            openAppSettings()
        }
    }

    private fun fixBattery() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        if (opened.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun handleStatusTap() {
        when (systemState) {
            SystemState.NO_GPS -> {
                if (hasFineLocation()) {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else if (!askedLocation || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    requestPermissionsIfNeeded()
                } else {
                    openAppSettings()
                }
            }
            SystemState.NO_NOTIFICATIONS -> fixNotifications()
            SystemState.NO_BATTERY -> fixBattery()
            else -> Unit
        }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(238, 243, 239)) }
        root.addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        speedText = TextView(this).apply {
            text = "0"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val unitText = TextView(this).apply {
            text = "km/h"
            textSize = 11f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val speedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(speedText)
            addView(unitText)
            elevation = dp(5).toFloat()
        }
        root.addView(speedPanel, FrameLayout.LayoutParams(
            dp(84), dp(84), Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(12), dp(12), 0, 0) })

        voyant = TextView(this).apply {
            text = "!"
            textSize = 44f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(210, 52, 48))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            contentDescription = "تحذير قريب"
        }
        root.addView(voyant, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(12), dp(12), 0) })

        gpsBanner = TextView(this).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(10), dp(6), dp(10), dp(6))
            elevation = dp(5).toFloat()
            visibility = View.GONE
            // Le texte rétrécit pour tenir sur une seule ligne, sans passer sous le compteur de vitesse.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            } else {
                textSize = 12f
            }
            setOnClickListener { handleStatusTap() }
        }
        root.addView(gpsBanner, FrameLayout.LayoutParams(
            resources.displayMetrics.widthPixels - dp(12 + 84 + 8 + 12), dp(46),
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, dp(12), dp(12), 0) })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(Color.rgb(28, 43, 37), 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation = dp(8).toFloat()
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        confirmBox = buildConfirmBox()
        root.addView(confirmBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(dp(12), dp(110), dp(12), 0) })

        val reportButton = Button(this).apply {
            text = "+"
            textSize = 38f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, dp(3))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ORANGE)
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(10).toFloat()
            contentDescription = "الإبلاغ عن خطر"
            setOnClickListener { onReportClicked() }
        }
        root.addView(reportButton, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(0, 0, 0, dp(12)) })

        languageButton = smallButton(language.buttonText) {
            language = if (language == LabelLanguage.ARABIC) LabelLanguage.FRENCH else LabelLanguage.ARABIC
            languageButton.text = language.buttonText
            refreshGpsBanner()
            confirmTitle.text = if (language == LabelLanguage.ARABIC) "هل التبليغ صحيح؟" else "Ce signalement est-il exact ?"
            // Update label expressions in-place: changing language must not reload the map
            // or move the camera away from the driver's current position.
            updatePlaceLabels()
        }.apply { contentDescription = "Changer la langue sans déplacer la carte" }
        languageButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(1), Color.rgb(210, 220, 214))
        }
        languageButton.elevation = dp(5).toFloat()
        languageButton.setPadding(dp(2), dp(2), dp(2), dp(2))
        val routeButton = smallButton("🛣️") { openRoutePlanner() }.apply {
            textSize = 22f
            contentDescription = "Itinéraire entre villes"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(210, 220, 214))
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        extraButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(routeButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(8) })
            addView(languageButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        root.addView(extraButtons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(16), dp(96)) })

        routeCardTitle = TextView(this).apply {
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        }
        routeCardInfo = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(26, 115, 232))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val routeClose = TextView(this).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(104, 116, 109))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            setOnClickListener { clearRoute() }
        }
        routeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 16, Color.rgb(226, 233, 228))
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(8), dp(2), dp(8))
            visibility = View.GONE
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(routeCardTitle)
                    addView(routeCardInfo)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(routeClose)
        }
        root.addView(routeCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { setMargins(dp(14), 0, dp(14), dp(104)) })

        menuButton = smallButton("☰") {
            extraButtons.visibility = if (extraButtons.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply {
            textSize = 24f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(210, 220, 214))
            }
            contentDescription = "Ouvrir les commandes"
        }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(14), dp(20)) })

        val locateButton = ImageView(this).apply {
            setImageDrawable(TargetDrawable(Color.rgb(25, 54, 46)))
            setPadding(dp(15), dp(15), dp(15), dp(15))
            contentDescription = "Recentrer sur ma position"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(210, 220, 214))
            }
            elevation = dp(5).toFloat()
            isClickable = true
            setOnClickListener { recenterAndZoom() }
        }
        root.addView(locateButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(14), 0, 0, dp(20)) })
        return root
    }

    private fun answerButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(color, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildConfirmBox(): LinearLayout {
        confirmTitle = TextView(this).apply {
            text = if (language == LabelLanguage.ARABIC) "هل التبليغ صحيح؟" else "Ce signalement est-il exact ?"
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val yes = answerButton("👍 نعم", Color.rgb(22, 125, 79)) {
            pendingConfirmation?.let { AlertsRepository.confirm(it.id) }
            closeConfirmation()
        }
        val no = answerButton("👎 لا", Color.rgb(184, 52, 47)) { closeConfirmation() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(yes, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(6), dp(20), dp(6), 0) })
            addView(no, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(6), dp(20), dp(6), 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(260)
            background = rounded(Color.WHITE, 20, Color.rgb(226, 233, 228))
            setPadding(dp(20), dp(22), dp(20), dp(28))
            elevation = dp(10).toFloat()
            visibility = View.GONE
            addView(confirmTitle)
            addView(row)
        }
    }

    private fun updatePlaceLabels() {
        val style = map?.style ?: return
        val expression = if (language == LabelLanguage.ARABIC) {
            Expression.coalesce(Expression.get("name_ar"), Expression.get("name"), Expression.get("name_fr"))
        } else {
            Expression.coalesce(Expression.get("name_fr"), Expression.get("name"), Expression.get("name_ar"))
        }
        listOf("places-city", "places-town", "places-village", "places-suburb", "places-hamlet")
            .forEach { id -> style.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.textField(expression)) }
    }

    private fun smoothBearing(location: Location): Double {
        if (location.hasBearing() && location.hasSpeed() && location.speed >= MIN_BEARING_SPEED) {
            val delta = ((location.bearing - mapBearing + 540.0) % 360.0) - 180.0
            mapBearing = (mapBearing + delta * 0.4 + 360.0) % 360.0
        }
        return mapBearing
    }

    /** Centre la carte sur la position et la tourne pour que le sens de la route soit vers le haut. */
    private fun followCamera(location: Location, resetZoom: Boolean): Boolean {
        val currentMap = map ?: return false
        if (currentMap.style == null) return false
        val zoom = if (resetZoom) TRACKING_ZOOM else currentMap.cameraPosition.zoom
        val position = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(zoom)
            .bearing(smoothBearing(location))
            .tilt(0.0)
            .build()
        currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(position), CAMERA_ANIM_MS)
        return true
    }

    /** L'utilisateur a bougé la carte : on arrête de la suivre, puis on se recentre seul après un moment. */
    private fun pauseFollowing() {
        followLocation = false
        handler.removeCallbacks(recenter)
        handler.postDelayed(recenter, RECENTER_DELAY_MS)
    }

    private fun resumeFollowing() {
        handler.removeCallbacks(recenter)
        if (routeShown) return
        followLocation = true
        val location = TrackerHub.lastLocation ?: return
        if (System.currentTimeMillis() - location.time <= MAX_GPS_AGE_MS) {
            followCamera(location, resetZoom = true)
        }
    }

    private fun recenterAndZoom() {
        val location = TrackerHub.lastLocation
        if (location == null || System.currentTimeMillis() - location.time > MAX_GPS_AGE_MS) {
            Toast.makeText(
                this,
                if (language == LabelLanguage.ARABIC) "انتظر إشارة GPS" else "En attente de la position GPS",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        handler.removeCallbacks(recenter)
        followLocation = true
        followCamera(location, resetZoom = true)
    }

    // ---------------------------------------------------------------------------------------------
    // Itinéraires hors ligne entre villes
    // ---------------------------------------------------------------------------------------------

    private fun isArabic() = language == LabelLanguage.ARABIC

    private fun dialogCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 24)
        setPadding(dp(22), dp(20), dp(22), dp(18))
    }

    private fun showCard(content: LinearLayout, resize: Boolean = false): AlertDialog {
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).create()
        if (resize) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    private fun openRoutePlanner() {
        extraButtons.visibility = View.GONE
        if (!RoutingStore.isInstalled(this)) {
            showRoutingDownloadDialog()
            return
        }
        if (routingGraph != null) {
            showPlannerDialog()
            return
        }
        Toast.makeText(this, if (isArabic()) "جارٍ التحميل…" else "Chargement…", Toast.LENGTH_SHORT).show()
        Thread {
            val outcome = runCatching {
                val graph = RoutingGraph(RoutingStore.file(applicationContext))
                graph to graph.loadPlaces()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val pair = outcome.getOrNull()
                if (pair == null) {
                    RoutingStore.delete(this)
                    Toast.makeText(
                        this,
                        if (isArabic()) "ملف المسارات تالف، حمّله من جديد" else "Fichier d'itinéraires illisible : téléchargez-le à nouveau",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    routingGraph = pair.first
                    routePlaces = pair.second
                    showPlannerDialog()
                }
            }
        }.start()
    }

    private fun showRoutingDownloadDialog() {
        val arabic = isArabic()
        AlertDialog.Builder(this)
            .setTitle(if (arabic) "تحميل المسارات" else "Télécharger les itinéraires")
            .setMessage(
                if (arabic) {
                    "لحساب المسارات بين مدن الجزائر بدون إنترنت، يجب تحميل ملف المسارات مرة واحدة فقط. الملف كبير، يُفضَّل استعمال شبكة Wi-Fi."
                } else {
                    "Pour calculer des itinéraires entre villes d'Algérie sans internet, il faut télécharger le fichier des routes une seule fois. Il est volumineux : privilégiez le Wi-Fi."
                },
            )
            .setPositiveButton(if (arabic) "تحميل" else "Télécharger") { _, _ -> startRoutingDownload() }
            .setNegativeButton(if (arabic) "إلغاء" else "Annuler", null)
            .show()
    }

    private fun startRoutingDownload() {
        val arabic = isArabic()
        val cancelled = AtomicBoolean(false)
        val progressText = TextView(this).apply {
            text = if (arabic) "جارٍ التحميل…" else "Téléchargement…"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(progressText)
            addView(progressBar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (arabic) "تحميل المسارات" else "Téléchargement des itinéraires")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(if (arabic) "إلغاء" else "Annuler") { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()
        Thread {
            var lastUpdate = 0L
            val outcome = runCatching {
                RoutingStore.download(
                    applicationContext,
                    { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            lastUpdate = now
                            runOnUiThread {
                                val doneMb = done / 1_000_000.0
                                if (total > 0) {
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = (done * 100 / total).toInt()
                                    progressText.text = String.format(Locale.US, "%.0f / %.0f MB", doneMb, total / 1_000_000.0)
                                } else {
                                    progressText.text = String.format(Locale.US, "%.0f MB", doneMb)
                                }
                            }
                        }
                    },
                    { cancelled.get() },
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                val error = outcome.exceptionOrNull()
                when {
                    error == null -> {
                        Toast.makeText(this, if (arabic) "تم تحميل المسارات" else "Itinéraires téléchargés", Toast.LENGTH_SHORT).show()
                        openRoutePlanner()
                    }
                    error is DownloadCancelled -> Unit
                    else -> Toast.makeText(
                        this,
                        (if (arabic) "تعذّر التحميل: " else "Téléchargement impossible : ") + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun placeLine(place: RoutePlace, arabic: Boolean): String {
        val main = place.label(arabic)
        val other = place.label(!arabic)
        return if (other.isNotEmpty() && other != main) "$main  ·  $other" else main
    }

    private fun pickerBox(): TextView = TextView(this).apply {
        textSize = 17f
        setTextColor(Color.rgb(24, 53, 42))
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.rgb(246, 248, 247), 14, Color.rgb(210, 220, 214))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true
    }

    private fun captionText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(104, 116, 109))
        setPadding(dp(4), dp(16), dp(4), dp(6))
    }

    private fun showPlannerDialog() {
        val arabic = isArabic()
        lateinit var dialog: AlertDialog
        val content = dialogCard()
        content.addView(TextView(this).apply {
            text = if (arabic) "🛣️ المسار بين المدن" else "🛣️ Itinéraire entre villes"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        })
        content.addView(captionText(if (arabic) "من" else "Départ"))
        val fromBox = pickerBox()
        content.addView(fromBox)
        content.addView(captionText(if (arabic) "إلى" else "Arrivée"))
        val toBox = pickerBox()
        content.addView(toBox)

        fun refresh() {
            fromBox.text = routeFrom?.let { placeLine(it, arabic) } ?: (if (arabic) "📍 موقعي الحالي" else "📍 Ma position")
            toBox.text = routeTo?.let { placeLine(it, arabic) } ?: (if (arabic) "اختر مدينة أو بلدية…" else "Choisissez une ville ou commune…")
        }
        refresh()
        fromBox.setOnClickListener { showPlacePicker(true) { routeFrom = it; refresh() } }
        toBox.setOnClickListener { showPlacePicker(false) { routeTo = it; refresh() } }

        val compute = Button(this).apply {
            text = if (arabic) "احسب المسار" else "Calculer l'itinéraire"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(26, 115, 232), 16)
            setOnClickListener {
                val destination = routeTo
                if (destination == null) {
                    Toast.makeText(this@MainActivity, if (arabic) "اختر وجهة" else "Choisissez une arrivée", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    computeRoute(routeFrom, destination)
                }
            }
        }
        content.addView(compute, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(24) })
        content.addView(TextView(this).apply {
            text = if (arabic) "حذف ملف المسارات" else "Supprimer le fichier d'itinéraires"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(140, 152, 146))
            setPadding(0, dp(18), 0, dp(4))
            isClickable = true
            setOnClickListener { confirmDeleteRouting(dialog) }
        })
        dialog = showCard(content)
    }

    private fun showPlacePicker(allowMyPosition: Boolean, onPicked: (RoutePlace?) -> Unit) {
        val arabic = isArabic()
        lateinit var dialog: AlertDialog
        var shown: List<RoutePlace?> = emptyList()
        val resultsAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>())

        fun refresh(query: String) {
            val results = searchPlaces(routePlaces, query, 40)
            shown = if (allowMyPosition && query.isBlank()) listOf<RoutePlace?>(null) + results else results
            resultsAdapter.clear()
            resultsAdapter.addAll(shown.map { place ->
                if (place == null) (if (arabic) "📍 موقعي الحالي" else "📍 Ma position") else placeLine(place, arabic)
            })
        }

        val search = EditText(this).apply {
            hint = if (arabic) "ابحث عن مدينة أو بلدية" else "Rechercher une ville ou commune"
            textSize = 17f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(Color.rgb(246, 248, 247), 14, Color.rgb(210, 220, 214))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(editable: Editable?) {
                    refresh(editable?.toString().orEmpty())
                }
            })
        }
        val list = ListView(this).apply {
            adapter = resultsAdapter
            setOnItemClickListener { _, _, position, _ ->
                val choice = shown.getOrNull(position)
                dialog.dismiss()
                onPicked(choice)
            }
        }
        val content = dialogCard()
        content.addView(search)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(12) })
        refresh("")
        dialog = showCard(content, resize = true)
    }

    private fun confirmDeleteRouting(parent: AlertDialog) {
        val arabic = isArabic()
        AlertDialog.Builder(this)
            .setMessage(if (arabic) "حذف ملف المسارات من الهاتف؟ يمكنك تحميله من جديد لاحقاً." else "Supprimer le fichier d'itinéraires du téléphone ? Vous pourrez le télécharger à nouveau.")
            .setPositiveButton(if (arabic) "حذف" else "Supprimer") { _, _ ->
                parent.dismiss()
                clearRoute()
                routingGraph?.close()
                routingGraph = null
                routePlaces = emptyList()
                routeFrom = null
                routeTo = null
                RoutingStore.delete(this)
            }
            .setNegativeButton(if (arabic) "إلغاء" else "Annuler", null)
            .show()
    }

    private fun computeRoute(from: RoutePlace?, to: RoutePlace) {
        if (routeBusy) return
        val graph = routingGraph ?: return
        val arabic = isArabic()
        val origin: Pair<Double, Double> = if (from != null) {
            Pair(from.latitude, from.longitude)
        } else {
            val location = TrackerHub.lastLocation
            if (location == null || System.currentTimeMillis() - location.time > MAX_GPS_AGE_MS) {
                Toast.makeText(this, if (arabic) "في انتظار إشارة GPS…" else "En attente de la position GPS…", Toast.LENGTH_SHORT).show()
                return
            }
            Pair(location.latitude, location.longitude)
        }
        routeBusy = true
        Toast.makeText(this, if (arabic) "جارٍ حساب المسار…" else "Calcul de l'itinéraire…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                graph.route(origin.first, origin.second, to.latitude, to.longitude) { false }
            }.getOrNull()
            runOnUiThread {
                routeBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    Toast.makeText(
                        this,
                        if (arabic) "لم يتم العثور على مسار بين هذين المكانين" else "Aucun itinéraire trouvé entre ces deux endroits",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    showRoute(from, to, result)
                }
            }
        }.start()
    }

    private fun formatRoute(result: RouteResult, arabic: Boolean): String {
        val km = result.distanceMeters / 1000.0
        val distance = if (km < 10) String.format(Locale.US, "%.1f", km) else km.roundToInt().toString()
        val minutes = (result.seconds / 60.0).roundToInt().coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return if (arabic) {
            val time = if (hours > 0) "$hours س $rest د" else "$rest د"
            "$distance كم  ·  $time"
        } else {
            val time = if (hours > 0) "$hours h $rest min" else "$rest min"
            "$distance km  ·  $time"
        }
    }

    private fun showRoute(from: RoutePlace?, to: RoutePlace, result: RouteResult) {
        val arabic = isArabic()
        val builder = StringBuilder(result.latitudes.size * 24 + 128)
        builder.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        for (i in result.latitudes.indices) {
            if (i > 0) builder.append(',')
            builder.append('[').append(result.longitudes[i]).append(',').append(result.latitudes[i]).append(']')
        }
        builder.append("]}}")
        map?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(builder.toString())

        val fromName = from?.label(arabic) ?: (if (arabic) "موقعي" else "Ma position")
        routeCardTitle.text = if (arabic) "من $fromName إلى ${to.label(true)}" else "$fromName → ${to.label(false)}"
        routeCardInfo.text = formatRoute(result, arabic)
        routeCard.visibility = View.VISIBLE
        routeShown = true

        // On cadre tout le trajet ; le recentrage automatique est suspendu tant que le tracé est affiché.
        pauseFollowing()
        runCatching {
            var north = -90.0
            var south = 90.0
            var east = -180.0
            var west = 180.0
            for (i in result.latitudes.indices) {
                north = maxOf(north, result.latitudes[i])
                south = minOf(south, result.latitudes[i])
                east = maxOf(east, result.longitudes[i])
                west = minOf(west, result.longitudes[i])
            }
            val bounds = LatLngBounds.from(north, east, south, west)
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(70)))
        }
    }

    private fun clearRoute() {
        map?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        routeCard.visibility = View.GONE
        if (routeShown) {
            routeShown = false
            resumeFollowing()
        }
    }

    private fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasFineLocation()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            askedLocation = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
            askedNotifications = true
        }
        if (missing.isEmpty()) onLocationReady() else requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasFineLocation()) onLocationReady()
        refreshGpsBanner()
    }

    private fun onLocationReady() {
        map?.style?.let { enableLocation(it) }
        // GPS tracking starts by default; reporting and speed depend on location updates.
        TrackingService.start(this)
        refreshGpsBanner()
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

        // APK allégé (sans carte) : on garde la carte déjà installée sur le téléphone lors de la mise à jour.
        val bundled = assets.list("")?.contains(MAP_FILE) == true
        if (!bundled) {
            check(target.exists() && target.length() > 0) { "installez d'abord l'APK avec carte" }
            marker.writeText(stamp)
            return target
        }

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
        // Les crédits restent accessibles dans le menu, sans encombrer la carte.
        readyMap.uiSettings.setAttributionEnabled(false)
        readyMap.uiSettings.setLogoEnabled(false)
        // Pas de boussole : le bouton cible en bas à gauche remet la carte dans le sens de la route.
        readyMap.uiSettings.setCompassEnabled(false)
        readyMap.setMinZoomPreference(MIN_ZOOM)
        readyMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(START_LATITUDE, START_LONGITUDE))
            .zoom(START_ZOOM)
            .build()
        readyMap.addOnMapClickListener { showAlertInfo(it) }
        readyMap.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) pauseFollowing()
        }
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
            style.addImage(MIXED_ICON, alertIcon("❗"))
            renderAlerts(TrackerHub.alerts)
            enableLocation(style)
            TrackerHub.lastLocation?.let { showLocation(it) }
        }
    }

    private fun alertIcon(emoji: String): Bitmap {
        val size = dp(ALERT_ICON_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val whiteRing = size * 0.07f
        val orangeRing = size * 0.11f
        fun disk(radius: Float, fill: Int) =
            canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        disk(center, Color.argb(70, 0, 0, 0))
        disk(center - dp(1), Color.WHITE)
        disk(center - dp(1) - whiteRing, ORANGE)
        disk(center - dp(1) - whiteRing - orangeRing, Color.WHITE)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.38f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(emoji, center, center - (glyph.descent() + glyph.ascent()) / 2, glyph)
        return bitmap
    }

    private fun renderAlerts(alerts: List<Alert>) {
        // Les signalements à moins de 50 m sont regroupés sur une seule icône.
        val groups = ArrayList<AlertGroup>()
        alerts.forEach { alert ->
            val group = groups.firstOrNull {
                distanceMeters(it.latitude, it.longitude, alert.latitude, alert.longitude) < GROUP_RADIUS_METERS
            } ?: AlertGroup(alert.latitude, alert.longitude).also { groups.add(it) }
            group.add(alert)
        }
        val features = JSONArray()
        groups.forEach { group ->
            val properties = JSONObject()
                .put("icon", if (group.counts.size == 1) group.counts.keys.first() else MIXED_ICON)
                .put("total", group.total)
                .put("comments", group.comments.joinToString("\n"))
            AlertTypes.all.forEach { properties.put(it, group.counts[it] ?: 0) }
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(group.longitude).put(group.latitude)))
                    .put("properties", properties),
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
        val features = currentMap.queryRenderedFeatures(area, ALERT_LAYER, ALERT_CLUSTER_LAYER)
        if (features.isEmpty()) return false
        val single = features.firstOrNull { !it.hasProperty("point_count") }
        if (single != null) {
            val counts = AlertTypes.all.associateWith { single.getNumberProperty(it)?.toInt() ?: 0 }
            val total = single.getNumberProperty("total")?.toInt() ?: counts.values.sum()
            val comments = single.getStringProperty("comments").orEmpty().split("\n").filter { it.isNotBlank() }
            showGroupPopup(total, counts, comments)
            return true
        }
        // Un cluster : on zoome pour séparer les icônes.
        pauseFollowing()
        currentMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(latLng, currentMap.cameraPosition.zoom + CLUSTER_TAP_ZOOM_STEP),
        )
        return true
    }

    private fun showGroupPopup(total: Int, counts: Map<String, Int>, comments: List<String>) {
        val arabic = language == LabelLanguage.ARABIC
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24)
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        content.addView(TextView(this).apply {
            text = if (arabic) "⚠️ عدد التبليغات ($total)" else "⚠️ Nombre de signalements ($total)"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        })
        AlertTypes.all.forEach { type ->
            val count = counts[type] ?: 0
            if (count > 0) {
                content.addView(TextView(this).apply {
                    text = "${AlertTypes.emoji(type)}  ${alertLabel(type)}  ($count)"
                    textSize = 18f
                    setTextColor(Color.rgb(60, 76, 68))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                })
            }
        }
        comments.take(5).forEach { comment ->
            content.addView(TextView(this).apply {
                text = "💬  $comment"
                textSize = 15f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
                setTextColor(Color.rgb(88, 102, 95))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }
        val close = Button(this).apply {
            text = if (arabic) "إغلاق" else "Fermer"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(104, 116, 109), 16)
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(20)
        })
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this).setView(scroll).create()
        // Se ferme automatiquement après 30 secondes.
        val autoClose = Runnable { if (dialog.isShowing) dialog.dismiss() }
        dialog.setOnDismissListener { handler.removeCallbacks(autoClose) }
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        handler.postDelayed(autoClose, POPUP_AUTO_CLOSE_MS)
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
        component.renderMode = RenderMode.NORMAL
        component.cameraMode = CameraMode.NONE
    }

    private fun showLocation(location: Location) {
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt().coerceAtLeast(0) else 0
        speedText.text = kmh.toString()
        if (followLocation && System.currentTimeMillis() - location.time <= MAX_GPS_AGE_MS) {
            // Premier point GPS : on se place sur la position avec le zoom de conduite.
            if (followCamera(location, resetZoom = !positioned)) positioned = true
        }
        refreshGpsBanner()
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
            Toast.makeText(
                this,
                if (language == LabelLanguage.ARABIC) "📡 فعّل نظام تحديد الموقع" else "📡 Activez la localisation GPS",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        val title = TextView(this).apply {
            text = if (language == LabelLanguage.ARABIC) "أبلغ عن خطر" else "Signaler un danger"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 64, 48))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = if (language == LabelLanguage.ARABIC) "اختر نوع البلاغ الذي تريد مشاركته مع السائقين" else "Choisissez le danger à partager avec les conducteurs"
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 107))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(14), dp(4), dp(24))
        }
        content.addView(title)
        content.addView(subtitle)

        AlertTypes.all.forEach { type ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = rounded(ORANGE, 16, Color.rgb(230, 89, 0))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val icon = TextView(this@MainActivity).apply {
                    text = AlertTypes.emoji(type)
                    textSize = 28f
                    gravity = Gravity.CENTER
                }
                val label = TextView(this@MainActivity).apply {
                    text = alertLabel(type)
                    textSize = 19f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, 0, 0)
                }
                addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    confirmReport(type, location.latitude, location.longitude)
                }
            }
            content.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
                .apply { setMargins(0, dp(0), 0, dp(36)) })
        }
        val cancel = Button(this).apply {
            text = if (language == LabelLanguage.ARABIC) "إلغاء" else "Annuler"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            background = rounded(Color.rgb(104, 116, 109), 16)
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(4)
        })
        dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun alertLabel(type: String): String = if (language == LabelLanguage.ARABIC) {
        AlertTypes.label(type)
    } else {
        when (type) {
            AlertTypes.ROUTE -> "Route endommagée"
            AlertTypes.ACCIDENT -> "Accident de la route"
            else -> "Danger sur la route"
        }
    }

    private fun confirmReport(type: String, latitude: Double, longitude: Double) {
        val arabic = language == LabelLanguage.ARABIC
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24, Color.rgb(236, 226, 216))
            setPadding(dp(22), dp(22), dp(22), dp(20))
        }
        val title = TextView(this).apply {
            text = if (arabic) "تأكيد البلاغ" else "Confirmer le signalement"
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "${AlertTypes.emoji(type)}  ${alertLabel(type)}\n\n" + if (arabic) {
                "هل تريد إرسال هذا البلاغ إلى السائقين الآخرين؟"
            } else {
                "Voulez-vous partager ce signalement avec les autres conducteurs ?"
            }
            textSize = 16f
            setTextColor(Color.rgb(60, 76, 68))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(16))
        }
        val commentField = EditText(this).apply {
            hint = if (arabic) "أضف وصفاً للخطر (اختياري)" else "Décrivez le danger (facultatif)"
            textSize = 16f
            setTextColor(Color.rgb(24, 53, 42))
            setHintTextColor(Color.rgb(140, 152, 146))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(COMMENT_MAX_LENGTH))
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = rounded(Color.rgb(246, 248, 247), 14, Color.rgb(210, 220, 214))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        content.addView(title)
        content.addView(message)
        content.addView(commentField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = Button(this).apply {
            text = if (arabic) "إلغاء" else "Annuler"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(104, 116, 109), 16)
            setOnClickListener { dialog.dismiss() }
        }
        val send = Button(this).apply {
            text = if (arabic) "إرسال البلاغ" else "Envoyer"
            isAllCaps = false
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(ORANGE, 16)
            setOnClickListener {
                dialog.dismiss()
                AlertsRepository.submitReport(type, latitude, longitude, commentField.text.toString().trim())
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(0, 0, dp(8), 0) })
        actions.addView(send, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        content.addView(actions)
        dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        TrackerHub.add(tracker)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        tracker.onGpsState(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        renderAlerts(TrackerHub.alerts)
        TrackerHub.lastLocation?.let { showLocation(it) }
        AlertsRepository.sync()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
        if (isDeviceGpsEnabled() && hasFineLocation() && !TrackerHub.trackingActive) {
            TrackingService.start(this)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
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
        routingGraph?.close()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
