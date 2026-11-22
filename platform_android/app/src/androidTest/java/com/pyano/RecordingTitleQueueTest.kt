// Concern: verifies terminal title errors allow later edits | Non-concern: user recordings | IO: (isolated work chain) -> assertions
package com.pyano

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.pyano.sync.CloudUploadWorker
import com.pyano.sync.titleSyncFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecordingTitleQueueTest {
    class RejectedTitle(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            return titleSyncFailure("Device token was rejected")
        }
    }
    class LaterTitle(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }

    @Test fun editQueuedBeforeEarlierRejectionStillRunsAndRetainsDiagnostic() = runBlocking {
        started = CountDownLatch(1)
        release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousManager = androidx.work.impl.WorkManagerImpl.getInstance()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(executor).build())
        val manager = WorkManager.getInstance(context)
        try {
            val rejected = OneTimeWorkRequestBuilder<RejectedTitle>().build()
            val later = OneTimeWorkRequestBuilder<LaterTitle>().build()
            manager.enqueueUniqueWork("synthetic-title-test", ExistingWorkPolicy.APPEND_OR_REPLACE, rejected).result.get()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            manager.enqueueUniqueWork("synthetic-title-test", ExistingWorkPolicy.APPEND_OR_REPLACE, later).result.get()
            release.countDown()
            val final = withTimeout(10000) { manager.getWorkInfoByIdFlow(later.id).first { it?.state?.isFinished == true } }
            assertEquals(WorkInfo.State.SUCCEEDED, final!!.state)
            val previous = manager.getWorkInfoById(rejected.id).get()
            assertEquals(WorkInfo.State.SUCCEEDED, previous.state)
            assertTrue(previous.outputData.getString(CloudUploadWorker.KEY_ERROR)!!.contains("Device token was rejected"))
        } finally {
            release.countDown()
            try { manager.cancelAllWork().result.get() }
            finally {
                try { WorkManagerTestInitHelper.closeWorkDatabase() }
                finally { androidx.work.impl.WorkManagerImpl.setDelegate(previousManager); executor.shutdownNow() }
            }
        }
    }

    companion object {
        private var started = CountDownLatch(1)
        private var release = CountDownLatch(1)
    }
}
