package org.explorink.gpsbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FixGate decides which fix gets trusted as the phone's position, before
 * SendPolicy ever sees it. A bug here either freezes the map on a stale spot
 * or lets a bad fix teleport it.
 */
class FixGateTest {

    @Test
    fun aNetworkFixRacingALiveGpsIsImplausible() {
        // The exact shape seen in the field: a GPS fix accurate to 3.79 m,
        // then 86 m away 31 ms later from a network fix claiming 300 m of
        // its own accuracy. Only the previous fix's accuracy tempers the
        // bound, so a lenient self-reported accuracy can't excuse the jump.
        assertTrue(FixGate.isImplausibleJump(distanceM = 86.0, dtMs = 31, prevAccuracyM = 3.79))
    }

    @Test
    fun genuineRoadSpeedIsNotFlagged() {
        // 150 km/h for a full second is 41.7 m -- comfortably under the cap.
        assertFalse(FixGate.isImplausibleJump(distanceM = 41.7, dtMs = 1_000, prevAccuracyM = 3.79))
    }

    @Test
    fun aTunnelReacquisitionOverManySecondsIsNotFlagged() {
        // 500 m of real travel over 20 s of patchy fixes is 25 m/s: plausible.
        assertFalse(FixGate.isImplausibleJump(distanceM = 500.0, dtMs = 20_000, prevAccuracyM = 5.0))
    }

    @Test
    fun jitterWithinThePreviousFixesAccuracyIsNotFlagged() {
        // Parked, GPS jittering within its own 8 m accuracy circle.
        assertFalse(FixGate.isImplausibleJump(distanceM = 6.0, dtMs = 0, prevAccuracyM = 8.0))
    }

    @Test
    fun simultaneousFixesOnlyGetThePreviousAccuracyAsRoom() {
        // Zero elapsed time: nothing beyond the old fix's own accuracy circle
        // can be explained by real movement.
        assertTrue(FixGate.isImplausibleJump(distanceM = 50.0, dtMs = 0, prevAccuracyM = 3.79))
    }

    @Test
    fun confirmationMeansLandingNearerTheSuspectThanTheOld() {
        assertTrue(FixGate.jumpConfirmedBy(distanceToPendingM = 4.0, distanceToAcceptedM = 90.0))
    }

    @Test
    fun snappingBackToTheOldSpotRefutesTheJump() {
        assertFalse(FixGate.jumpConfirmedBy(distanceToPendingM = 86.0, distanceToAcceptedM = 2.0))
    }

    @Test
    fun aNetworkFixRightAfterALiveGpsFixIsIgnorable() {
        // The exact failure seen in the field: GPS answered 300 ms ago, so
        // this network fix is noise, not a second confirming vote.
        assertTrue(FixGate.isNetworkFixIgnorable(msSinceLastGpsFix = 300L))
    }

    @Test
    fun aNetworkFixLongAfterGpsWentQuietIsNotIgnorable() {
        // GPS has been silent well past the live window -- a real outage,
        // where network is the only signal left worth listening to.
        assertFalse(FixGate.isNetworkFixIgnorable(msSinceLastGpsFix = 30_000L))
    }

    @Test
    fun aNetworkFixWithNoGpsHistoryAtAllIsNotIgnorable() {
        // Session started indoors or GPS never fixed -- nothing to prefer it over.
        assertFalse(FixGate.isNetworkFixIgnorable(msSinceLastGpsFix = null))
    }
}
