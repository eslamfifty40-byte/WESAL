package com.wesal.messenger

import com.wesal.messenger.core.util.CameraSwitchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 21: the PINNED camera pair.
 *
 * The owner's r20 verdict: flip still walked the extra rear cameras
 * («ما يجيبليش كذا كاميرا من الخلفية»). Root cause (see
 * FIXLOG_R21): r20 computed the right target name but called the
 * capturer's UNTARGETED switch overload anyway, and stamped the
 * intended target into the bookkeeping the capturer never went to.
 *
 * The rule under test replaces per-press selection entirely: at call
 * setup the call pins exactly two cameras — the front camera and THE
 * rear camera (the first back-facing entry, the main/default rear) —
 * and every flip targets the pinned camera of the opposite facing.
 * Auxiliary rear lenses (wide / tele / macro) are never eligible, so
 * no sequence of presses can ever land on one.
 */
class R21LogicTest {

    private fun cam(name: String, front: Boolean) = CameraSwitchTarget.CameraInfo(name, front)

    // The owner's device class: front + main rear + wide + tele.
    private val fourCameras = listOf(
        cam("front", true),
        cam("rearMain", false),
        cam("rearWide", false),
        cam("rearTele", false)
    )

    private val twoCameras = listOf(cam("front", true), cam("rear", false))

    @Test
    fun pinPair_multiCamera_pinsFrontAndMainRearOnly() {
        val pair = CameraSwitchTarget.pinPair(fourCameras)
        assertEquals(
            CameraSwitchTarget.PinnedPair(frontName = "front", rearName = "rearMain"),
            pair
        )
    }

    @Test
    fun pinPair_twoCameras_pinsBoth() {
        assertEquals(
            CameraSwitchTarget.PinnedPair(frontName = "front", rearName = "rear"),
            CameraSwitchTarget.pinPair(twoCameras)
        )
    }

    @Test
    fun pinPair_singleFacingOrEmpty_returnsNull() {
        assertNull(CameraSwitchTarget.pinPair(listOf(cam("front", true), cam("frontWide", true))))
        assertNull(CameraSwitchTarget.pinPair(listOf(cam("rearMain", false))))
        assertNull(CameraSwitchTarget.pinPair(emptyList()))
    }

    @Test
    fun toggle_alternatesStrictlyBetweenTheTwoPinnedCameras() {
        val pair = CameraSwitchTarget.pinPair(fourCameras)!!
        // Simulate ten presses starting on the front camera: the
        // visited sequence must be exactly rear/front alternating —
        // wide and tele must NEVER appear, from any press count.
        var currentlyFront = true
        val visited = mutableListOf<String>()
        repeat(10) {
            val target = CameraSwitchTarget.toggleTarget(pair, currentlyFront)
            visited += target
            currentlyFront = target == pair.frontName
        }
        assertEquals(
            listOf(
                "rearMain", "front", "rearMain", "front", "rearMain",
                "front", "rearMain", "front", "rearMain", "front"
            ),
            visited
        )
        assertTrue(visited.none { it == "rearWide" || it == "rearTele" })
    }

    @Test
    fun toggle_afterWatchdogRecovery_pairAndAlternationSurvive() {
        // A watchdog recovery rebuilds the capturer: it re-derives the
        // pins from a fresh enumeration of the same device and
        // rebuilds on the pinned camera for the facing the call is on.
        val pairBefore = CameraSwitchTarget.pinPair(fourCameras)!!
        // The call was on the rear camera when the switch wedged.
        var currentlyFront = false
        // Recovery: re-pin (same enumeration -> the same pair) and
        // rebuild on the pinned name for the current facing.
        val pairAfter = CameraSwitchTarget.pinPair(fourCameras)!!
        assertEquals(pairBefore, pairAfter)
        assertEquals(
            "rearMain",
            CameraSwitchTarget.nameForFacing(pairAfter, front = currentlyFront)
        )
        // And the toggle keeps alternating inside the pair afterwards.
        val next = CameraSwitchTarget.toggleTarget(pairAfter, currentlyFront)
        assertEquals("front", next)
        currentlyFront = true
        assertEquals(
            "rearMain",
            CameraSwitchTarget.toggleTarget(pairAfter, currentlyFront)
        )
    }

    @Test
    fun nameForFacing_returnsThePinnedCameraForEachFacing() {
        val pair = CameraSwitchTarget.pinPair(fourCameras)!!
        assertEquals("front", CameraSwitchTarget.nameForFacing(pair, front = true))
        assertEquals("rearMain", CameraSwitchTarget.nameForFacing(pair, front = false))
    }
}
