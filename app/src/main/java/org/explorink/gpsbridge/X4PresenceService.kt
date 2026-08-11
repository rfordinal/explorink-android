package org.explorink.gpsbridge

import android.companion.CompanionDeviceService
import android.content.Intent
import android.util.Log

/**
 * The wake. The OS binds this when the associated X4 starts advertising, which
 * happens when the rider opens the map screen on the device
 * (`MapActivity::onEnter` -> `BlePositionServer::begin`). All this does is start
 * [BridgeService] and get out of the way -- the bridge does the scanning,
 * connecting and sending, exactly as if the rider had opened the app.
 *
 * Bound by the system, not started by us, so the process is created for it even
 * after the app was swiped from recents. A force-stop from Settings still blocks
 * everything until the app is opened by hand; that is OS policy and there is no
 * way around it.
 */
class X4PresenceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "X4Presence"
    }

    /**
     * The String overload, not the AssociationInfo one: on API 33+ the
     * AssociationInfo default implementation calls this for any association that
     * is not self-managed, and ours is not. One override, every API level from 31.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "X4 appeared: $address")
        try {
            startForegroundService(
                Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_WAKE)
            )
        } catch (t: Throwable) {
            // The companion association plus
            // REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND is what
            // makes this legal from the background. If it still throws, the wake
            // is not available on this phone and the rider has to open the app --
            // say so in the log rather than fail silently.
            Log.e(TAG, "startForegroundService refused", t)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        // Nothing. The device stops advertising when the rider leaves the map
        // screen, which is not a reason to stop sending: they may come straight
        // back, and BridgeService already handles a dropped link. Stopping is the
        // rider's call, from the app's Stop button.
        Log.i(TAG, "X4 disappeared: $address")
    }
}
