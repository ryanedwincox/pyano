// Concern: verifies truthful cloud indicators | Non-concern: live networking | IO: (worker states and receipts) -> assertions
package com.pyano

import androidx.work.WorkInfo
import com.pyano.sync.RecordingCloudState
import com.pyano.sync.recordingCloudState
import com.pyano.sync.recordingUploadError
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCloudStateTest {
    @Test fun retainedFailureOnlyAppearsForCurrentFailedRecording() {
        val error = "audio checksum did not match the declared asset"
        assertEquals(error, recordingUploadError(RecordingCloudState.FAILED, error))
        assertEquals(null, recordingUploadError(RecordingCloudState.SYNCED, error))
        assertEquals(null, recordingUploadError(RecordingCloudState.PENDING, error))
        assertEquals(null, recordingUploadError(null, error))
        assertEquals(RecordingCloudState.SYNCED, recordingCloudState(listOf(WorkInfo.State.FAILED), true))
    }
    @Test fun pendingRunningFailureAndDurableReceiptStayDistinct() {
        assertEquals(RecordingCloudState.PENDING, recordingCloudState(emptyList(), false))
        assertEquals(RecordingCloudState.PENDING, recordingCloudState(listOf(WorkInfo.State.ENQUEUED), false))
        assertEquals(RecordingCloudState.UPLOADING, recordingCloudState(listOf(WorkInfo.State.RUNNING), false))
        assertEquals(RecordingCloudState.FAILED, recordingCloudState(listOf(WorkInfo.State.FAILED), false))
        assertEquals(RecordingCloudState.SYNCED, recordingCloudState(listOf(WorkInfo.State.SUCCEEDED), false))
        assertEquals(RecordingCloudState.SYNCED, recordingCloudState(emptyList(), true))
        assertEquals(RecordingCloudState.PENDING, recordingCloudState(listOf(WorkInfo.State.ENQUEUED), true))
    }
    @Test fun historicalReceiptIsReadonlyAndDistinctFromVerifiedSyncedState() {
        assertEquals(RecordingCloudState.HISTORICAL_RECEIPT, recordingCloudState(emptyList(), false, true))
        assertEquals(RecordingCloudState.SYNCED, recordingCloudState(emptyList(), true, true))
        assertEquals(RecordingCloudState.UPLOADING, recordingCloudState(listOf(WorkInfo.State.RUNNING), false, true))
        assertEquals(null, recordingUploadError(RecordingCloudState.HISTORICAL_RECEIPT, "No verified current upload"))
    }

}
