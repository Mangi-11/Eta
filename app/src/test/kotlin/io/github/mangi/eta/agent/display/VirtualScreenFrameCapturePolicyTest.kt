package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenFrameCapturePolicyTest {
    @Test
    fun hiddenDisplayDoesNotEncodeWithoutARequest() {
        val policy = VirtualScreenFrameCapturePolicy()
        assertNull(policy.beginCapture(1_000))
        assertNull(policy.beginCapture(100_000))
        assertEquals(0L, policy.encodedFrames)
    }

    @Test
    fun visibleViewerIsCappedAtTenFramesPerSecond() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.setViewerVisible(true, 1_000)
        val first = requireNotNull(policy.beginCapture(1_000))
        assertTrue(policy.completeCapture(first, 1_010))
        assertEquals(1L, policy.delayUntilCapture(1_099))
        assertNull(policy.beginCapture(1_099))
        assertNotNull(policy.beginCapture(1_100))
    }

    @Test
    fun fastSurfaceFramesDoNotExceedTenEncodingsWithinASecond() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.setViewerVisible(true, 1_000)
        for (time in 1_000L until 2_000L) {
            policy.beginCapture(time)?.let { policy.completeCapture(it, time) }
        }
        assertEquals(10L, policy.encodedFrames)
    }

    @Test
    fun oneBackgroundObservationEncodesOnceThenStopsImmediately() {
        val policy = VirtualScreenFrameCapturePolicy()
        val request = policy.requestFrame(1_000, 1_000)
        val capture = requireNotNull(policy.beginCapture(1_000))
        assertEquals(request, capture.requestId)
        assertTrue(policy.completeCapture(capture, 1_010))
        assertFalse(policy.hasPendingRequest(1_020))
        assertNull(policy.beginCapture(1_100))
        assertNull(policy.beginCapture(1_500))
        assertEquals(1L, policy.encodedFrames)
    }

    @Test
    fun requestArrivingDuringAnEncodingNeedsItsOwnAcknowledgedFrame() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.setViewerVisible(true, 1_000)
        val earlier = requireNotNull(policy.beginCapture(1_000))
        val request = policy.requestFrame(1_010, 1_000)
        assertTrue(policy.completeCapture(earlier, 1_020))
        assertTrue(policy.hasPendingRequest(1_020))
        val fresh = requireNotNull(policy.beginCapture(1_100))
        assertEquals(request, fresh.requestId)
        assertTrue(policy.completeCapture(fresh, 1_110))
        assertFalse(policy.hasPendingRequest(1_111))
    }

    @Test
    fun leavingViewerRejectsAnInFlightFrameAndDoesNotResetRateLimit() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.setViewerVisible(true, 1_000)
        val capture = requireNotNull(policy.beginCapture(1_000))
        policy.setViewerVisible(false, 1_005)
        assertFalse(policy.completeCapture(capture, 1_010))
        assertNull(policy.beginCapture(1_100))
        policy.setViewerVisible(true, 1_020)
        assertNull(policy.beginCapture(1_020))
        assertNotNull(policy.beginCapture(1_100))
    }

    @Test
    fun viewerHeartbeatExpiryStopsEncodingAfterAnAppCrash() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.setViewerVisible(true, 1_000)
        assertTrue(policy.isViewerVisible(2_499))
        assertFalse(policy.isViewerVisible(2_500))
        assertNull(policy.beginCapture(2_500))
    }

    @Test
    fun heartbeatsExtendPreviewWithoutInvalidatingRunningCapture() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.setViewerVisible(true, 1_000)
        val capture = requireNotNull(policy.beginCapture(1_000))
        policy.setViewerVisible(true, 1_050)
        assertTrue(policy.completeCapture(capture, 1_070))
        assertTrue(policy.isViewerVisible(2_549))
        assertFalse(policy.isViewerVisible(2_550))
    }

    @Test
    fun expiredObservationDoesNotPublishOrRestartCapture() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.requestFrame(1_000, 100)
        val capture = requireNotNull(policy.beginCapture(1_000))
        assertFalse(policy.completeCapture(capture, 1_100))
        assertNull(policy.beginCapture(1_100))
        assertEquals(0L, policy.encodedFrames)
    }

    @Test
    fun invalidationRejectsPreviousAppFrameButNewRequestCanCapture() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.requestFrame(1_000, 1_000)
        val old = requireNotNull(policy.beginCapture(1_000))
        policy.invalidate()
        val request = policy.requestFrame(1_010, 1_000)
        assertFalse(policy.completeCapture(old, 1_030))
        val fresh = requireNotNull(policy.beginCapture(1_100))
        assertEquals(request, fresh.requestId)
        assertTrue(policy.completeCapture(fresh, 1_110))
        assertEquals(1L, policy.encodedFrames)
    }

    @Test
    fun backgroundRequestDoesNotEnableContinuousViewerMode() {
        val policy = VirtualScreenFrameCapturePolicy()
        policy.requestFrame(1_000, 1_000)
        assertFalse(policy.isViewerVisible(1_001))
        assertTrue(policy.hasPendingRequest(1_001))
        policy.completeCapture(requireNotNull(policy.beginCapture(1_001)), 1_002)
        assertNull(policy.delayUntilCapture(1_102))
    }
}
