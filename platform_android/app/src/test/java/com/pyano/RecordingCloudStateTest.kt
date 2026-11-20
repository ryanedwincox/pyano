// Concern: verifies truthful cloud indicators | Non-concern: live networking | IO: (worker states and receipts) -> assertions
package com.pyano

import androidx.work.WorkInfo
import com.pyano.sync.RecordingCloudState
import com.pyano.sync.recordingCloudState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCloudStateTest {
    @Test fun pendingRunningFailureAndDurableReceiptStayDistinct() {
        assertEquals(RecordingCloudState.PENDING, recordingCloudState(emptyList(), false))
        assertEquals(RecordingCloudState.PENDING, recordingCloudState(listOf(WorkInfo.State.ENQUEUED), false))
        assertEquals(RecordingCloudState.UPLOADING, recordingCloudState(listOf(WorkInfo.State.RUNNING), false))
        assertEquals(RecordingCloudState.FAILED, recordingCloudState(listOf(WorkInfo.State.FAILED), false))
        assertEquals(RecordingCloudState.SYNCED, recordingCloudState(listOf(WorkInfo.State.SUCCEEDED), false))
        assertEquals(RecordingCloudState.SYNCED, recordingCloudState(emptyList(), true))
        assertEquals(RecordingCloudState.PENDING, recordingCloudState(listOf(WorkInfo.State.ENQUEUED), true))
    }
}
