package com.remotecontrollan.host

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.remotecontrollan.model.InstalledAppItem
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppManager(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    suspend fun getInstalledApps(): List<InstalledAppItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<InstalledAppItem>()
        try {
            val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info in installed) {
                // Filter launchable user applications
                val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)
                if (launchIntent != null) {
                    val appName = packageManager.getApplicationLabel(info).toString()
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val pInfo = try {
                        packageManager.getPackageInfo(info.packageName, 0)
                    } catch (e: Exception) {
                        null
                    }
                    val version = pInfo?.versionName ?: "1.0"

                    list.add(
                        InstalledAppItem(
                            appName = appName,
                            packageName = info.packageName,
                            versionName = version,
                            isSystemApp = isSystem
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e("AppManager", "Error querying installed apps: ${e.message}", e)
        }
        return@withContext list.sortedBy { it.appName.lowercase() }
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLogger.i("AppManager", "Launched app: $packageName")
                true
            } else {
                AppLogger.w("AppManager", "No launcher intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            AppLogger.e("AppManager", "Failed to launch $packageName: ${e.message}", e)
            false
        }
    }

    suspend fun stopApp(packageName: String): Boolean {
        // If rooted, use am force-stop
        return if (RootEngine.checkRootStatus() == com.remotecontrollan.model.RootStatus.DETECTED) {
            RootEngine.stopPackage(packageName)
        } else {
            AppLogger.w("AppManager", "Stopping background packages without root requires system permissions")
            false
        }
    }
}
