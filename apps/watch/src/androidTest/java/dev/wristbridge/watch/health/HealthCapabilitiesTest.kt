package dev.wristbridge.watch.health

import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthCapabilitiesTest {
    @Test fun queryPassiveCapabilitiesWithoutReadingSensorData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val caps = HealthServices.getClient(context).passiveMonitoringClient
            .getCapabilitiesAsync().get(30, TimeUnit.SECONDS)
        assertNotNull(caps)
        val supported = caps.supportedDataTypesPassiveMonitoring
        println("HealthCapabilities: dailySteps=${DataType.STEPS_DAILY in supported}, heartRate=${DataType.HEART_RATE_BPM in supported}")
    }
}
