package com.wesal.messenger

import com.wesal.messenger.core.util.CameraSwitchTarget
import com.wesal.messenger.core.util.ScreenShareLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 20: camera-switch TARGET selection and screen-share sizing.
 *
 * The switch-target tests pin the r19 device defect: the shipped
 * capturer's untargeted switch overload cycles to the NEXT camera id
 * in enumeration order, so on a multi-camera device (front + main
 * rear + wide rear + tele rear) rear→front needed several presses.
 * The rule under test always targets the first camera of the OPPOSITE
 * facing — a true two-state toggle in both directions.
 */
class R20LogicTest {

    private fun cam(name: String, front: Boolean) = CameraSwitchTarget.CameraInfo(name, front)

    private val twoCameras = listOf(cam("front", true), cam("rear", false))

    // The owner's device class: several rear ids behind the front one.
    private val fourCameras = listOf(
        cam("front", true),
        cam("rearMain", false),
        cam("rearWide", false),
        cam("rearTele", false)
    )

    @Test
    fun switchTarget_twoCameras_togglesBothWays() {
        assertEquals(
            "rear",
            CameraSwitchTarget.pickSwitchTarget(twoCameras, currentCameraName = "front", currentFront = true)
        )
        assertEquals(
            "front",
            CameraSwitchTarget.pickSwitchTarget(twoCameras, currentCameraName = "rear", currentFront = false)
        )
    }

    @Test
    fun switchTarget_multiCamera_fromMainRear_goesStraightToFront() {
        // The untargeted library cycle would have picked "rearWide"
        // here (and "rearTele" next) — the ~4-presses symptom.
        assertEquals(
            "front",
            CameraSwitchTarget.pickSwitchTarget(fourCameras, currentCameraName = "rearMain", currentFront = false)
        )
    }

    @Test
    fun switchTarget_multiCamera_fromAnyRear_goesToFront() {
        assertEquals(
            "front",
            CameraSwitchTarget.pickSwitchTarget(fourCameras, currentCameraName = "rearWide", currentFront = false)
        )
        assertEquals(
            "front",
            CameraSwitchTarget.pickSwitchTarget(fourCameras, currentCameraName = "rearTele", currentFront = false)
        )
    }

    @Test
    fun switchTarget_multiCamera_fromFront_goesToFirstRear() {
        assertEquals(
            "rearMain",
            CameraSwitchTarget.pickSwitchTarget(fourCameras, currentCameraName = "front", currentFront = true)
        )
    }

    @Test
    fun switchTarget_unknownCurrentName_fallsBackToFacingFlag() {
        assertEquals(
            "front",
            CameraSwitchTarget.pickSwitchTarget(fourCameras, currentCameraName = null, currentFront = false)
        )
        assertEquals(
            "rearMain",
            CameraSwitchTarget.pickSwitchTarget(fourCameras, currentCameraName = "gone", currentFront = true)
        )
    }

    @Test
    fun switchTarget_noOppositeFacing_returnsNull() {
        val frontOnly = listOf(cam("front", true), cam("frontWide", true))
        assertNull(
            CameraSwitchTarget.pickSwitchTarget(frontOnly, currentCameraName = "front", currentFront = true)
        )
        assertNull(
            CameraSwitchTarget.pickSwitchTarget(listOf(cam("front", true)), currentCameraName = "front", currentFront = true)
        )
        assertNull(
            CameraSwitchTarget.pickSwitchTarget(emptyList(), currentCameraName = null, currentFront = true)
        )
    }

    @Test
    fun initialCamera_prefersRequestedFacing_thenAny() {
        assertEquals("front", CameraSwitchTarget.pickInitialCamera(fourCameras, preferFront = true))
        assertEquals("rearMain", CameraSwitchTarget.pickInitialCamera(fourCameras, preferFront = false))
        val rearOnly = listOf(cam("rearMain", false))
        assertEquals("rearMain", CameraSwitchTarget.pickInitialCamera(rearOnly, preferFront = true))
        assertNull(CameraSwitchTarget.pickInitialCamera(emptyList(), preferFront = true))
    }

    // ---- ScreenShareLogic ----

    @Test
    fun screenShare_sizeUnderCap_isUnchanged() {
        assertEquals(720 to 1280, ScreenShareLogic.targetSize(720, 1280))
        assertEquals(1080 to 1920, ScreenShareLogic.targetSize(1080, 1920))
    }

    @Test
    fun screenShare_sizeOverCap_scalesProportionallyToCap() {
        // 1080x2400 panel: long edge 2400 -> capped at 1920 (x0.8).
        assertEquals(864 to 1920, ScreenShareLogic.targetSize(1080, 2400))
    }

    @Test
    fun screenShare_oddDimensions_areFlooredToEven() {
        assertEquals(1078 to 1918, ScreenShareLogic.targetSize(1079, 1919))
    }

    @Test
    fun screenShare_invalidDisplay_fallsBackTo720p() {
        assertEquals(720 to 1280, ScreenShareLogic.targetSize(0, 0))
        assertEquals(720 to 1280, ScreenShareLogic.targetSize(-5, 100))
    }
}
