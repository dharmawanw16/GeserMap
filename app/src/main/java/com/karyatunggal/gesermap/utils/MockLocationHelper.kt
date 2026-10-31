package com.karyatunggal.gesermap.utils

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object MockLocationHelper {

    /**
     * Checks if the device is rooted.
     */
    fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.destroy()
            line != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Automatically grants mock location permission via root (su) so the app
     * is bypassed from needing manual selection in Developer Options.
     */
    fun grantMockLocationViaRoot(context: Context): Boolean {
        if (!isDeviceRooted()) return false
        val pkg = context.packageName
        val commands = listOf(
            "appops set $pkg MOCK_LOCATION allow",
            "cmd appops set $pkg android:mock_location allow",
            "appops set $pkg android:mock_location allow"
        )
        var success = false
        for (cmd in commands) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    success = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return success
    }

    /**
     * Checks if mock location is enabled (either via Developer Options or auto-granted via Root).
     */
    fun isMockLocationEnabled(context: Context): Boolean {
        // If device is rooted, attempt to automatically grant mock location via root
        if (isDeviceRooted()) {
            grantMockLocationViaRoot(context)
        }

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                appOps?.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps?.noteOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            testMockProviderPermission(context)
        }
    }

    @SuppressLint("WrongConstant")
    private fun testMockProviderPermission(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val testProvider = "mock_test_provider"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    testProvider,
                    false, false, false, false, false, false, false,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    testProvider,
                    false, false, false, false, false, false, false,
                    1,
                    1
                )
            }
            locationManager.removeTestProvider(testProvider)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Opens Developer Settings screen for the user to select mock location app.
     */
    fun openDeveloperSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
