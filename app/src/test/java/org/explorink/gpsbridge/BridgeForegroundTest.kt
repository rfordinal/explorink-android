package org.explorink.gpsbridge

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `foregroundTypeMask` is the fix for the bug where a repeated
 * `startForeground` call (a Record tap, a repeated `ACTION_WAKE`) silently
 * dropped the `location` foreground type -- `startForeground` replaces the
 * declared type set, it does not merge into it. These two cases are what the
 * mask must produce; the framework call itself (`Service.startForeground`)
 * is not unit-testable here -- that is what the code-walk/scenario in the
 * task report is for.
 */
class BridgeForegroundTest {

    @Test
    fun noLocationRunningIsConnectedDeviceOnly() {
        val mask = foregroundTypeMask(locationRunning = false)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, mask)
    }

    @Test
    fun locationRunningKeepsBothBits() {
        val mask = foregroundTypeMask(locationRunning = true)
        assertTrue(mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0)
        assertTrue(mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0)
    }
}
