package org.explorink.gpsbridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `Activity` and `Service` class in the sources is declared in a manifest.
 *
 * **Why this exists.** A merge conflict in `AndroidManifest.xml` was resolved by hand
 * and quietly dropped two declarations, `WalletSyncActivity` and `WalletCodeActivity`.
 * Nothing noticed: Kotlin compiles, 542 tests pass, the APK installs, the wallet list
 * draws. The failure only appears when a **rider presses the button** --
 * `ActivityNotFoundException` kills the app -- and it reached a phone before it was
 * found, 2026-08-19.
 *
 * A missing declaration is invisible to every check that does not launch the screen,
 * which is exactly the shape of bug that a cheap structural test catches for free. It
 * reads the real manifests off disk, so it also covers the debug-only entry points.
 *
 * **One caveat, measured while proving these assertions can fail:** the manifests and
 * the sources are read with `File`, so Gradle does not know they are inputs and will
 * report `testDebugUnitTest` up to date after an edit that only touches them. Editing a
 * manifest and seeing a green build therefore proves nothing on its own -- use
 * `./gradlew test --rerun-tasks` (or edit a Kotlin file) when the manifest is what
 * changed.
 */
class ManifestDeclaresEveryActivityTest {

    /** The Gradle unit test's working directory is the module dir (`android/app`). */
    private val src = File("src")

    private fun manifest(variant: String): String {
        val f = File(src, "$variant/AndroidManifest.xml")
        assertTrue("no manifest at ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** `class Foo : Activity()` / `: Service()`, per source set, fully qualified. */
    private fun componentsIn(variant: String): List<String> {
        val root = File(src, "$variant/java")
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1) ?: return@forEach
            // Only real component subclasses. `: Activity()` covers this codebase --
            // there is no AppCompatActivity and no Fragment anywhere in it.
            Regex("^class\\s+(\\w+)\\s*:\\s*(Activity|Service)\\(\\)", RegexOption.MULTILINE)
                .findAll(text).forEach { m -> out.add("$pkg.${m.groupValues[1]}") }
        }
        return out
    }

    private fun declared(text: String, fqcn: String, appPackage: String): Boolean {
        // A manifest names a component either fully qualified or relative to the
        // application package, and both forms are in use here.
        val relative = fqcn.removePrefix(appPackage)
        return text.contains("android:name=\"$fqcn\"") ||
            text.contains("android:name=\"$relative\"")
    }

    @Test
    fun every_main_activity_and_service_is_declared() {
        val main = manifest("main")
        val missing = componentsIn("main")
            .filterNot { declared(main, it, "org.explorink.gpsbridge") }
        assertTrue("not declared in src/main/AndroidManifest.xml: $missing", missing.isEmpty())
    }

    @Test
    fun every_debug_only_activity_is_declared_in_the_debug_manifest() {
        // Debug entry points are merged from src/debug, so a debug class may appear in
        // either manifest. Missing from both is the bug.
        val both = manifest("main") + manifest("debug")
        val missing = componentsIn("debug")
            .filterNot { declared(both, it, "org.explorink.gpsbridge") }
        assertTrue("not declared in any manifest: $missing", missing.isEmpty())
    }

    @Test
    fun the_two_that_were_lost_are_named_here_on_purpose() {
        // A regression pin, not a duplicate of the scan above: these two are the ones a
        // hand-resolved merge conflict deleted, and naming them makes the failure
        // message say so the next time it happens.
        val main = manifest("main")
        for (c in listOf("WalletSyncActivity", "WalletCodeActivity", "WalletActivity",
                "WalletItemActivity", "PinsActivity", "MainActivity")) {
            assertTrue("$c is missing from src/main/AndroidManifest.xml", main.contains(c))
        }
    }

    @Test
    fun no_conflict_marker_survives_in_any_manifest_or_layout() {
        // Same class of accident, cheaper to catch: a marker left in an XML file makes
        // the resource compiler fail, but a marker inside an XML comment does not.
        val roots = listOf(File(src, "main"), File(src, "debug"))
        val bad = ArrayList<String>()
        for (r in roots) {
            if (!r.isDirectory) continue
            r.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { f ->
                val t = f.readText()
                if (t.contains("<<<<<<<") || t.contains(">>>>>>>")) bad.add(f.path)
            }
        }
        assertTrue("conflict markers left in: $bad", bad.isEmpty())
    }
}
