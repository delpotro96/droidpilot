package dev.droidpilot.observer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.droidpilot.core.model.InstalledApp

// The list of apps the planner may open.
//
// A goal names an app the way a person does - kakaotalk, the alarm - and the
// planner has to turn that into a package name. Guessing one produces a launch
// that silently does nothing, so the names are read from the phone and offered
// as a closed list, the same way screen elements are
class AppDirectory(context: Context) {

    private val packages = context.packageManager
    private val self = context.packageName

    // Cached because this walks every installed package and the answer does not
    // change during a run
    private val cached: List<InstalledApp> by lazy { read() }

    fun all(): List<InstalledApp> = cached

    private fun read(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        // The PackageManager.ResolveInfoFlags overload lands on API 33, and
        // this app runs from 30. The deprecated int overload is the only one
        // that covers the whole range
        val resolved = packages.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        return resolved.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                // Driving our own interface is how a run presses its own stop
                // button. The planner is given a way out instead: home
                if (packageName == self) return@mapNotNull null

                InstalledApp(
                    label = info.loadLabel(packages).toString().trim(),
                    packageName = packageName
                )
            }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
            .toList()
    }
}
