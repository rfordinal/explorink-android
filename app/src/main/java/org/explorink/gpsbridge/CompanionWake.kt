package org.explorink.gpsbridge

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

/**
 * The X4 waking this app, and this app knowing which X4 is ours.
 *
 * Both come from one mechanism: a CompanionDeviceManager association. The rider
 * picks the device once in a system dialog, and after that the OS holds an
 * association to that exact MAC address. Three things follow from it:
 *
 * - **Presence.** `startObservingDevicePresence` makes the OS itself watch for
 *   that device's advertisement and bind [X4PresenceService] when it appears.
 *   That works with the app swiped away and no scan of our own running, because
 *   the scan is not ours.
 * - **A background service start.** An association plus
 *   REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND is a documented
 *   exemption from the Android 12 rule that a background app cannot start a
 *   foreground service. Without it the wake would land and then be refused.
 * - **The right device.** The association is one MAC, so [BleLink] can pin its
 *   scan to it. Before this the app connected to the first thing advertising the
 *   name or the service UUID, and two X4s running this firmware are
 *   indistinguishable by both -- a second device in range was a race.
 *
 * The X4 only advertises while its map or sync-map-tiles screen is open
 * (`docs/ble-map-transfer-protocol.md`), so "the rider opened the map" is what
 * the OS is watching for.
 */
object CompanionWake {

    private const val TAG = "CompanionWake"

    /** Request code for the association dialog's IntentSender. */
    const val REQ_ASSOCIATE = 42

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /**
     * MAC addresses this app is associated with. Normally zero or one: the
     * association dialog is asked for a single device.
     */
    fun associatedAddresses(context: Context): List<String> {
        val cdm = manager(context) ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.map { it.uppercase() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "associations", t)
            emptyList()
        }
    }

    /** The one X4 this phone is paired with, or null while unpaired. */
    fun pairedAddress(context: Context): String? = associatedAddresses(context).firstOrNull()

    fun isPaired(context: Context): Boolean = pairedAddress(context) != null

    /**
     * Opens the system association dialog. It scans for our service UUID and
     * shows the rider a list to pick from, so a second X4 in the room is a
     * choice made by a human once rather than a race resolved per connect.
     *
     * The result comes back to [Activity.onActivityResult] with [REQ_ASSOCIATE];
     * hand it to [onAssociationResult].
     */
    fun requestAssociation(activity: Activity, onFailure: (CharSequence?) -> Unit) {
        val cdm = manager(activity) ?: return onFailure("no companion device support")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleLink.SERVICE_UUID))
                    .build()
            )
            .build()
        // setSingleDevice(false): the picker is the point. With true the dialog
        // confirms the first match, which is exactly the coin flip this exists
        // to remove when two X4s are in range.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            // onAssociationPending (API 33+) defaults to calling this, so one
            // override covers every API level the app supports.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                try {
                    activity.startIntentSenderForResult(chooserLauncher, REQ_ASSOCIATE, null, 0, 0, 0)
                } catch (t: Throwable) {
                    Log.e(TAG, "startIntentSenderForResult", t)
                    onFailure(t.javaClass.simpleName)
                }
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
                onFailure(error)
            }
        }
        try {
            cdm.associate(request, callback, null)
        } catch (t: Throwable) {
            Log.e(TAG, "associate", t)
            onFailure(t.javaClass.simpleName)
        }
    }

    /**
     * Call from `onActivityResult` for [REQ_ASSOCIATE]. The association itself is
     * already recorded by the system at this point; all this does is start
     * observing presence for it.
     *
     * @return the paired address, or null if the rider cancelled.
     */
    fun onAssociationResult(context: Context): String? {
        val addr = pairedAddress(context) ?: return null
        startObserving(context)
        return addr
    }

    /**
     * Re-arms presence observation for every association. Idempotent, so calling
     * it on every app launch is the cheap way to survive whatever drops the
     * registration -- an association with nobody observing it wakes nothing.
     *
     * Open -- needs hardware: whether the registration survives a phone reboot on
     * its own. The documentation says the system re-binds; nothing here has been
     * measured across a reboot yet.
     */
    fun startObserving(context: Context) {
        val cdm = manager(context) ?: return
        for (addr in associatedAddresses(context)) {
            try {
                // The ObservingDevicePresenceRequest overload only exists from
                // API 36; this one covers 31..36 and is deprecated, not removed.
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(addr)
                Log.i(TAG, "observing presence of $addr")
            } catch (t: Throwable) {
                Log.w(TAG, "startObservingDevicePresence $addr", t)
            }
        }
    }

    /** Forgets the paired X4. Presence observation goes with the association. */
    fun forget(context: Context) {
        val cdm = manager(context) ?: return
        for (addr in associatedAddresses(context)) {
            try {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(addr)
            } catch (t: Throwable) {
                Log.w(TAG, "stopObservingDevicePresence $addr", t)
            }
            try {
                @Suppress("DEPRECATION")
                cdm.disassociate(addr)
            } catch (t: Throwable) {
                Log.w(TAG, "disassociate $addr", t)
            }
        }
    }
}
