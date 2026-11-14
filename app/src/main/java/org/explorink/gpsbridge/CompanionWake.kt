package org.explorink.gpsbridge

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
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

    /**
     * Shares [BridgeService]'s preference file rather than opening a second
     * one: both halves are "what this phone remembers about its reader", and a
     * file per caller is how a settings store ends up with four of them.
     */
    private const val PREFS = "bridge"

    /** `name_<MAC>` -> the last name that MAC actually advertised. See [rememberName]. */
    private const val PREF_NAME_PREFIX = "device_name_"

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
    fun requestAssociation(activity: Activity, report: (CharSequence?) -> Unit) {
        val cdm = manager(activity) ?: return report("no companion device support")
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
        // `report`, never `onFailure`, inside this object: a bare onFailure(...)
        // call here resolves to the override below, not to the caller's lambda,
        // and recurses until the stack goes.
        val callback = object : CompanionDeviceManager.Callback() {
            // onAssociationPending (API 33+) defaults to calling this, so one
            // override covers every API level the app supports.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                try {
                    activity.startIntentSenderForResult(chooserLauncher, REQ_ASSOCIATE, null, 0, 0, 0)
                } catch (t: Throwable) {
                    Log.e(TAG, "startIntentSenderForResult", t)
                    report(t.javaClass.simpleName)
                }
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
                report(error)
            }
        }
        try {
            cdm.associate(request, callback, null)
        } catch (t: Throwable) {
            Log.e(TAG, "associate", t)
            report(t.javaClass.simpleName)
        }
    }

    /**
     * Call from `onActivityResult` for [REQ_ASSOCIATE]. The association itself is
     * already recorded by the system at this point; all this does is start
     * observing presence for it.
     *
     * **The address comes out of the picker's own result [Intent] first, and
     * only then out of [pairedAddress]** (T-162). Re-reading `cdm.associations`
     * at the instant the result arrives is not reliable: measured on a Galaxy
     * S10 (SM-G973F, Android 12) on 2026-11-13, the list had not caught up yet
     * and the lookup returned null for a pairing that had plainly succeeded --
     * the device plate was already drawing `paired with B8:1F:3F:D4:89:BD`
     * underneath a toast saying it had failed. The rider who believes the toast
     * pairs a second time. The returned Intent is the system's own statement
     * about what was just picked, so it cannot lag behind itself.
     *
     * @return the paired address, or null if the rider cancelled.
     */
    fun onAssociationResult(context: Context, data: Intent? = null): String? {
        val addr = addressFromResult(data) ?: pairedAddress(context) ?: return null
        // Observe this address directly as well as re-arming from the list: on
        // the same phone the list can still be empty here, and
        // [startObserving] walking an empty list would observe nothing at all
        // -- a silent half-pairing that wakes for no device.
        observe(context, addr)
        startObserving(context)
        return addr
    }

    /**
     * The MAC the companion picker says the rider chose, read out of the result
     * [Intent] it hands back.
     *
     * Two shapes, because the extra changed: API 33+ returns an `AssociationInfo`
     * under `EXTRA_ASSOCIATION`, and 31/32 return the matched device under the
     * deprecated `EXTRA_DEVICE` -- a [ScanResult] for a
     * `BluetoothLeDeviceFilter` (what [requestAssociation] builds), a
     * [BluetoothDevice] for the classic filter. All three are read, so this
     * keeps working if the filter ever changes.
     */
    fun addressFromResult(data: Intent?): String? {
        val intent = data ?: return null
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val info = intent.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_ASSOCIATION,
                    AssociationInfo::class.java,
                )
                info?.deviceMacAddress?.toString()?.uppercase()?.let { return it }
            }
            @Suppress("DEPRECATION")
            when (val picked = intent.getParcelableExtra<android.os.Parcelable>(
                CompanionDeviceManager.EXTRA_DEVICE
            )) {
                is ScanResult -> return picked.device?.address?.uppercase()
                is BluetoothDevice -> return picked.address?.uppercase()
                else -> Unit
            }
        } catch (t: Throwable) {
            // Never fatal: the caller falls back to reading the association
            // list, which is what this code used to do on its own.
            Log.w(TAG, "address out of the association result", t)
        }
        return null
    }

    /** Starts presence observation for one address. Safe to call twice. */
    private fun observe(context: Context, addr: String) {
        val cdm = manager(context) ?: return
        try {
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(addr)
            Log.i(TAG, "observing presence of $addr")
        } catch (t: Throwable) {
            Log.w(TAG, "startObservingDevicePresence $addr", t)
        }
    }

    // --- the paired reader's name (T-163) -----------------------------------

    /**
     * Remembers what a MAC actually advertised, so Home can name the reader it
     * is paired with while the link is down.
     *
     * Before this the fallback was `BleLink.DEVICE_NAME`, one model's constant:
     * a phone paired to an X4 Pro had its plate dialog headed `XteinkX4Map`
     * over a line reading `paired with B8:1F:3F:D4:89:BD`, which is an X4 Pro
     * (S10, 2026-11-13). Naming the wrong model is worse than naming none, and
     * for a rider with two readers it is the only thing on that dialog that
     * says which one they are holding.
     */
    fun rememberName(context: Context, addr: String?, name: String?) {
        if (addr == null || name.isNullOrBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_NAME_PREFIX + addr.uppercase(), name)
                .apply()
        }
    }

    /** The remembered name for [addr], or null if this phone never saw one. */
    fun rememberedName(context: Context, addr: String?): String? {
        if (addr == null) return null
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_NAME_PREFIX + addr.uppercase(), null)
        }.getOrNull()?.ifBlank { null }
    }

    /** Drops the remembered name for [addr]. Part of forgetting the device. */
    private fun forgetName(context: Context, addr: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(PREF_NAME_PREFIX + addr.uppercase())
                .apply()
        }
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
            forgetName(context, addr)
        }
    }
}
