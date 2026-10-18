package dev.wristbridge.watch.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthStoreTest {
    private fun isolated(block: (HealthStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-health-test-${System.nanoTime()}.db"
        val store = HealthStore(context, name)
        try { block(store) } finally {
            store.close()
            for (suffix in listOf("", "-journal", "-wal", "-shm"))
                File(context.noBackupFilesDir, name + suffix).delete()
        }
    }

    @Test fun duplicateBatchAndOutOfOrderSamplesDoNotInflateCountOrReplaceLatest() = isolated { store ->
        val now = System.currentTimeMillis()
        repeat(2) { store.add(HealthStore.HEART_RATE, now, now, 72.0) }
        store.add(HealthStore.HEART_RATE, now - 1000, now - 1000, 60.0)
        store.add(HealthStore.STEPS_DAILY, now - 5000, now, 123.0)
        val result = store.summary()
        assertEquals(3L, result.pending)
        assertEquals(72.0, result.heartRate!!, 0.001)
        assertEquals(123L, result.steps)
        // A daily snapshot remains 123, never added to the next total as an interval delta.
        store.add(HealthStore.STEPS_DAILY, now - 5000, now + 1, 130.0)
        assertEquals(130L, store.summary().steps)
    }

    @Test fun duplicateSensorIdentitySurvivesWallClockAdjustment() = isolated { store ->
        val now = System.currentTimeMillis()
        store.add(HealthStore.HEART_RATE, now, now, 72.0, "boot-3:PT10S")
        store.add(HealthStore.HEART_RATE, now + 1000, now + 1000, 72.0, "boot-3:PT10S")
        assertEquals(1L, store.summary().pending)
        store.add(HealthStore.HEART_RATE, now + 1000, now + 1000, 72.0, "boot-4:PT10S")
        assertEquals(2L, store.summary().pending)
    }

    @Test fun retentionAndEraseAreVisibleAndInvalidValuesAreIgnored() = isolated { store ->
        val now = System.currentTimeMillis()
        val old = now - 8L * 24 * 60 * 60 * 1000
        store.add(HealthStore.HEART_RATE, old, old, 70.0)
        store.add(HealthStore.HEART_RATE, now, now, Double.NaN)
        store.add(HealthStore.HEART_RATE, now, now, -1.0)
        store.add(HealthStore.HEART_RATE, now, now, 75.0)
        assertEquals(1L, store.summary().pending)
        assertEquals(1L, store.summary().dropped)
        store.erase()
        assertEquals(HealthSummary(), store.summary())
    }
}
