package org.explorink.gpsbridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * About: app identity, what it does, the in-app privacy disclosure, the
 * permission list it rests on, open-source credits and the two public
 * links (site, mail). Reached from [MainActivity]'s "More" overflow.
 *
 * Google Play expects a privacy disclosure reachable in-app without digging
 * (Settings/Account/About are its own examples) for any app requesting
 * sensitive permissions -- this one asks for background location. The text
 * here is grounded in two checked facts rather than boilerplate: the
 * permission list is [AndroidManifest.xml] read line by line, and the "no
 * analytics, no telemetry, no crash reports" claim was verified by grepping
 * this module for any upload/telemetry code and finding none (Outline
 * "Obrazovky", "GDPR a diagnostika: nič neodchádza z telefónu"). There is no
 * public privacy-policy URL for this app yet -- the app is sideloaded via
 * GitHub Releases only, not on the Play Store
 * (`docs/android-release-plan.md`, "Play Store is a later question") -- so
 * this screen states the facts directly instead of linking a page that does
 * not exist.
 */
class AboutActivity : Activity() {

    private data class Permission(val name: String, val reason: String)

    private val permissions = listOf(
        Permission("Bluetooth", "Finds and stays connected to your device."),
        Permission(
            "Location (while using the app)",
            "Sends your GPS position to the device.",
        ),
        Permission(
            "Location (background)",
            "Only for reconnecting automatically when the device comes into range. The app works without it.",
        ),
        Permission(
            "Notifications",
            "Shows connection status while position sending runs in the background.",
        ),
        Permission(
            "Internet",
            "Fetches map tiles over HTTPS from the project's own content delivery network.",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<ImageButton>(R.id.btnAboutBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tvAboutVersion).text =
            "ExplorInk GPS ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val list = findViewById<LinearLayout>(R.id.llAboutPermissions)
        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()
        permissions.forEachIndexed { index, permission ->
            val name = TextView(this).apply {
                text = permission.name
                setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.ui_ink))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val reason = TextView(this).apply {
                text = permission.reason
                setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.ui_ash))
                textSize = 13f
                setPadding(0, dp(2), 0, 0)
            }
            list.addView(name)
            list.addView(reason)
            if (index != permissions.lastIndex) {
                list.addView(
                    android.view.View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(12),
                        )
                    },
                )
            }
        }

        findViewById<Button>(R.id.btnAboutLicenses).setOnClickListener {
            openUrl("https://github.com/rfordinal/explorink-android/blob/master/THIRD_PARTY_LICENSES.md")
        }
        findViewById<Button>(R.id.btnAboutWebsite).setOnClickListener {
            openUrl("https://explorink.com")
        }
        findViewById<Button>(R.id.btnAboutContact).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:info@explorink.com") })
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app to open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
