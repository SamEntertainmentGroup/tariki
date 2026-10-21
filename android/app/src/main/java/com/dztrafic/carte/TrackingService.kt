package com.dztrafic.carte

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper

private const val CHANNEL_ID = "tracking"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "com.dztrafic.carte.STOP"
private const val LOCATION_INTERVAL_MS = 1000L
private const val POLL_INTERVAL_MS = 10_000L
private const val FAR_WARNING_METERS = 200.0
private const val NEAR_WARNING_METERS = 100.0
private const val PASSED_ZONE_METERS = 30.0
private const val RESET_METERS = 250.0
private const val CONFIRM_MIN_AGE_SECONDS = 300L
private const val PREFS_NAME = "dz_settings"
private const val KEY_TRACKING = "tracking_enabled"
private const val TEXT_READY = "📡 التتبع نشط"

private class AlertProgress {
    var farWarned = false
    var nearWarned = false
    var insideZone = false
}

class TrackingService : Service(), LocationListener {

    private val handler = Handler(Looper.getMainLooper())
    private val progress = HashMap<Long, AlertProgress>()
    private val askedConfirmation = HashSet<Long>()
    private lateinit var locationManager: LocationManager

    private val poll = object : Runnable {
        override fun run() {
            AlertsRepository.sync()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        AlertsRepository.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        startLocationUpdates()
        handler.removeCallbacks(poll)
        handler.post(poll)
        TrackerHub.setTrackingActive(true)
        TrackerHub.setGpsEnabled(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        locationManager.removeUpdates(this)
        TrackerHub.setTrackingActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground() {
        createChannel()
        val notification = buildNotification(TEXT_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLocationUpdates() {
        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, this, Looper.getMainLooper(),
            )
        } catch (ignored: SecurityException) {
            stopSelf()
        } catch (ignored: IllegalArgumentException) {
            TrackerHub.setGpsEnabled(false)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "التتبع", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("DZ Carte")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "إيقاف", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onLocationChanged(location: Location) {
        TrackerHub.publishLocation(location)
        checkAlerts(location)
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) TrackerHub.setGpsEnabled(true)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) TrackerHub.setGpsEnabled(false)
    }

    @Deprecated("Required on API levels below 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    private fun checkAlerts(location: Location) {
        for (alert in TrackerHub.alerts) {
            val state = progress.getOrPut(alert.id) { AlertProgress() }
            val distance = distanceMeters(location.latitude, location.longitude, alert.latitude, alert.longitude)

            if (distance <= FAR_WARNING_METERS && !state.farWarned) {
                state.farWarned = true
                warn(alert, distance)
            }
            if (distance <= NEAR_WARNING_METERS && !state.nearWarned) {
                state.nearWarned = true
                warn(alert, distance)
            }
            if (distance <= PASSED_ZONE_METERS) {
                state.insideZone = true
            }
            if (distance > PASSED_ZONE_METERS && state.insideZone) {
                state.insideZone = false
                if (alert.ageSeconds >= CONFIRM_MIN_AGE_SECONDS && askedConfirmation.add(alert.id)) {
                    TrackerHub.requestConfirmation(alert)
                }
            }
            if (distance > RESET_METERS) {
                state.farWarned = false
                state.nearWarned = false
            }
        }
    }

    private fun warn(alert: Alert, distance: Double) {
        AlertSound.play()
        updateNotification("${AlertTypes.emoji(alert.type)} ${alert.title} · ${distance.toInt()} م")
        TrackerHub.publishProximity(alert, distance.toInt())
    }

    companion object {

        fun start(context: Context) {
            setEnabled(context, true)
            val intent = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TRACKING, true)

        private fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TRACKING, enabled).apply()
        }
    }
}
