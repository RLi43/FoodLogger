package ch.foodlogger.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import ch.foodlogger.core.AppRelease
import ch.foodlogger.core.Releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from the GitHub release that CI publishes for every push to main.
 * Android verifies that the downloaded APK is signed with the same key as the installed app.
 */
class AppUpdater(private val context: Context) {

    /** The latest release if it is newer than this build; null when up to date or unreachable. */
    suspend fun newerRelease(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val text = open(Releases.LATEST_URL).use { it.bufferedReader().readText() }
            Releases.parseLatest(text)?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }.getOrNull()
    }

    /**
     * Downloads the APK into a PackageInstaller session and commits it. Android shows its
     * confirmation dialog (see [InstallResultReceiver]) unless FoodLogger installed itself before.
     */
    suspend fun install(release: AppRelease) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                open(release.apkUrl).use { input ->
                    session.openWrite("foodlogger.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallResultReceiver::class.java), flags,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "FoodLogger-Android/${BuildConfig.VERSION_NAME}")
        if (responseCode != HttpURLConnection.HTTP_OK) error("GitHub answered HTTP $responseCode")
        inputStream
    }
}

/** Receives the PackageInstaller result: shows the system confirmation or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            // On success Android restarts the app with the new version; nothing to do.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update failed: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }
}
