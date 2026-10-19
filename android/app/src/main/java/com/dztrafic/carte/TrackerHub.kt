package com.dztrafic.carte

import android.location.Location
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

private const val MAX_ACCURACY_METERS = 30f
private const val MIN_STEP_METERS = 5f
private const val MAX_STEP_METERS = 500f

interface TrackerListener {
    fun onLocation(location: Location) {}
    fun onAlerts(alerts: List<Alert>) {}
    fun onProximity(alert: Alert, distanceMeters: Int) {}
    fun onConfirmationRequest(alert: Alert) {}
    fun onGpsState(enabled: Boolean) {}
    fun onTrackingState(active: Boolean) {}
    fun onMessage(text: String) {}
}

object TrackerHub {

    private val listeners = CopyOnWriteArrayList<TrackerListener>()
    private var previous: Location? = null

    var alerts: List<Alert> = emptyList()
        private set
    var lastLocation: Location? = null
        private set
    var gpsEnabled = true
        private set
    var trackingActive = false
        private set
    var tripDistanceMeters = 0.0
        private set
    var tripStartMs = SystemClock.elapsedRealtime()
        private set

    fun add(listener: TrackerListener) {
        listeners.add(listener)
    }

    fun remove(listener: TrackerListener) {
        listeners.remove(listener)
    }

    fun startTrip() {
        tripDistanceMeters = 0.0
        tripStartMs = SystemClock.elapsedRealtime()
        previous = null
    }

    fun publishLocation(location: Location) {
        previous?.let { last ->
            val step = last.distanceTo(location)
            if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_METERS && step in MIN_STEP_METERS..MAX_STEP_METERS) {
                tripDistanceMeters += step
            }
        }
        previous = location
        lastLocation = location
        listeners.forEach { it.onLocation(location) }
    }

    fun setAlerts(newAlerts: List<Alert>) {
        alerts = newAlerts
        listeners.forEach { it.onAlerts(newAlerts) }
    }

    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled = enabled
        listeners.forEach { it.onGpsState(enabled) }
    }

    fun setTrackingActive(active: Boolean) {
        trackingActive = active
        listeners.forEach { it.onTrackingState(active) }
    }

    fun publishProximity(alert: Alert, distanceMeters: Int) {
        listeners.forEach { it.onProximity(alert, distanceMeters) }
    }

    fun requestConfirmation(alert: Alert) {
        listeners.forEach { it.onConfirmationRequest(alert) }
    }

    fun notifyMessage(text: String) {
        listeners.forEach { it.onMessage(text) }
    }
}
