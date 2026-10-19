package com.dztrafic.carte

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val MIN_SEND_INTERVAL_MS = 35_000L
private const val TEXT_SENT = "✅ تم إرسال التبليغ"
private const val TEXT_CONFIRMED = "✅ تم تأكيد التبليغ"
private const val TEXT_QUEUED = "📥 تم حفظ التبليغ وسيُرسل عند توفر الاتصال"
private const val TEXT_NOT_CONFIGURED = "⚠️ عنوان الخادم غير مضبوط"
private const val TEXT_ERROR = "❌ حدث خطأ"

object AlertsRepository {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var store: AlertStore? = null
    private var api: AlertsApi? = null

    @Volatile
    private var lastSendMs = 0L

    fun init(context: Context) {
        if (store != null) return
        val newStore = AlertStore(context.applicationContext)
        store = newStore
        api = BuildConfig.ALERTS_URL.takeIf { it.isNotBlank() }?.let { AlertsApi(it) }
        TrackerHub.setAlerts(newStore.loadAlerts())
    }

    val configured: Boolean
        get() = api != null

    fun sync() {
        val client = api ?: return
        val storage = store ?: return
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                try {
                    flushOnePending(client, storage)
                } catch (ignored: IOException) {
                }
                val alerts = client.fetchAlerts()
                storage.saveAlerts(alerts)
                main.post { TrackerHub.setAlerts(alerts) }
            } catch (ignored: IOException) {
            } finally {
                busy.set(false)
            }
        }
    }

    fun submitReport(type: String, latitude: Double, longitude: Double) {
        val storage = store ?: return
        if (api == null) {
            TrackerHub.notifyMessage(TEXT_NOT_CONFIGURED)
            return
        }
        storage.addPending(PendingReport(type, latitude, longitude, System.currentTimeMillis()))
        TrackerHub.notifyMessage(TEXT_QUEUED)
        sync()
    }

    fun confirm(alertId: Long) {
        val client = api ?: return
        val storage = store ?: return
        executor.execute {
            val text = try {
                val result = client.confirm(alertId, storage.deviceId())
                if (result.success) TEXT_CONFIRMED else result.message.ifEmpty { TEXT_ERROR }
            } catch (ignored: IOException) {
                null
            }
            main.post {
                text?.let { TrackerHub.notifyMessage(it) }
                sync()
            }
        }
    }

    private fun flushOnePending(client: AlertsApi, storage: AlertStore) {
        val report = storage.firstPending() ?: return
        if (System.currentTimeMillis() - lastSendMs < MIN_SEND_INTERVAL_MS) return
        val result = client.sendReport(report, storage.deviceId())
        lastSendMs = System.currentTimeMillis()
        storage.removeFirstPending()
        val text = when {
            result.success && result.confirmed -> TEXT_CONFIRMED
            result.success -> TEXT_SENT
            else -> result.message.ifEmpty { TEXT_ERROR }
        }
        main.post { TrackerHub.notifyMessage(text) }
    }
}
