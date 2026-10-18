package dev.wristbridge.watch.health

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import dev.wristbridge.watch.WristBridgeApp
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object HealthCapture {
    const val STEPS = "health_steps_enabled"
    const val HEART = "health_heart_enabled"
    private const val STATUS = "health_status"
    private const val JOB = 7001
    private val io = Executors.newSingleThreadExecutor()
    fun prefs(context: Context) = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)
    fun permission(heart: Boolean): String = if (!heart) Manifest.permission.ACTIVITY_RECOGNITION
        else if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
    fun backgroundPermission(): String = if (Build.VERSION.SDK_INT >= 36)
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" else Manifest.permission.BODY_SENSORS_BACKGROUND
    fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun status(context: Context): String = prefs(context).getString(STATUS, "Sin activar").orEmpty()
    fun schedule(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB, ComponentName(context, HealthRegistrationJob::class.java))
                .setMinimumLatency(0).setOverrideDeadline(10_000).build(),
        )
    }

    /** Invoked by the job, isolated from the notification/BLE service and its permissions. */
    fun register(context: Context, done: (Boolean) -> Unit) {
        io.execute {
            try {
                val prefs = prefs(context)
                val client = HealthServices.getClient(context).passiveMonitoringClient
                val supported = client.getCapabilitiesAsync().get(60, TimeUnit.SECONDS).supportedDataTypesPassiveMonitoring
                val types = buildSet {
                    if (prefs.getBoolean(STEPS, false) && granted(context, permission(false)) && DataType.STEPS_DAILY in supported) add(DataType.STEPS_DAILY)
                    if (prefs.getBoolean(HEART, false) && granted(context, permission(true)) &&
                        granted(context, backgroundPermission()) && DataType.HEART_RATE_BPM in supported) add(DataType.HEART_RATE_BPM)
                }
                if (types.isEmpty()) client.clearPassiveListenerServiceAsync().get(60, TimeUnit.SECONDS)
                else client.setPassiveListenerServiceAsync(
                    HealthDataService::class.java, PassiveListenerConfig.builder().setDataTypes(types).build(),
                ).get(60, TimeUnit.SECONDS)
                val states = listOf(
                    if (!prefs.getBoolean(STEPS, false)) "Pasos: desactivados"
                    else if (!granted(context, permission(false))) "Pasos: sin permiso"
                    else if (DataType.STEPS_DAILY !in supported) "Pasos: no disponibles" else "Pasos: registro activo",
                    if (!prefs.getBoolean(HEART, false)) "Pulso: desactivado"
                    else if (!granted(context, permission(true)) || !granted(context, backgroundPermission())) "Pulso: falta permiso"
                    else if (DataType.HEART_RATE_BPM !in supported) "Pulso: no disponible" else "Pulso: registro activo",
                )
                prefs.edit { putString(STATUS, states.joinToString("\n")) }
                done(false)
            } catch (_: Exception) {
                prefs(context).edit { putString(STATUS, "No se pudo registrar salud; vuelve a intentar") }
                done(true)
            }
        }
    }

    fun erase(context: Context) {
        prefs(context).edit { putBoolean(STEPS, false); putBoolean(HEART, false) }
        io.execute { HealthStore.get(context).erase() }
        schedule(context)
    }

    fun receive(context: Context, points: DataPointContainer) {
        // Commit before returning the bound-service callback: values must not sit in an ephemeral executor.
        // This is independent of registration futures (which may take seconds).
        try {
            val store = HealthStore.get(context)
            synchronized(store) {
                val prefs = prefs(context)
                val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
                val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
                val batch = buildList {
                    if (prefs.getBoolean(STEPS, false) && granted(context, permission(false))) {
                        points.getData(DataType.STEPS_DAILY).forEach {
                            add(HealthStore.Point(HealthStore.STEPS_DAILY, it.getStartInstant(boot).toEpochMilli(),
                                it.getEndInstant(boot).toEpochMilli(), it.value.toDouble(),
                                "$bootCount:${it.startDurationFromBoot}:${it.endDurationFromBoot}"))
                        }
                    }
                    if (prefs.getBoolean(HEART, false) && granted(context, permission(true)) && granted(context, backgroundPermission())) {
                        points.getData(DataType.HEART_RATE_BPM).forEach {
                            val at = it.getTimeInstant(boot).toEpochMilli()
                            add(HealthStore.Point(HealthStore.HEART_RATE, at, at, it.value, "$bootCount:${it.timeDurationFromBoot}"))
                        }
                    }
                }
                if (batch.isNotEmpty()) store.addBatch(batch)
            }
        } catch (_: Exception) {
            prefs(context).edit { putString(STATUS, "No se pudieron guardar datos de salud") }
        }
    }

}

class HealthDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) = HealthCapture.receive(this, dataPoints)
    override fun onPermissionLost() {
        HealthCapture.prefs(this).edit { putString("health_status", "Permiso de salud retirado") }
        HealthCapture.schedule(this)
    }
}

/** JobScheduler supplies a lifecycle after boot; long Health Services registration never holds a receiver. */
class HealthRegistrationJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        HealthCapture.register(applicationContext) { retry -> jobFinished(params, retry) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
