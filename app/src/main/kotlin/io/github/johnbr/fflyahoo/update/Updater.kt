package io.github.johnbr.fflyahoo.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.johnbr.fflyahoo.BuildConfig
import io.github.johnbr.fflyahoo.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [fraction] is null while the size is unknown. */
    data class Downloading(val release: Release, val fraction: Float?) : UpdateState
    /** Handed to Android's installer, which asks the user to confirm. */
    data class Installing(val release: Release) : UpdateState
    /** [release] is set when the check worked and the download or install did not, so it can be retried. */
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Self-update from the GitHub releases page: asks GitHub for the latest release, and when it is newer than
 * this build, downloads its APK, checks it against GitHub's SHA-256, and hands it to Android's package
 * installer. Android shows its own confirmation, and only accepts an APK signed with the same key.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).build()
    private val dir get() = File(context.cacheDir, "update")
    private var lastCheck = 0L
    private var job: Job? = null

    /** The automatic check, on opening the app: at most every few hours, and quietly. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck < CHECK_EVERY_MS) return
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate) return
        check(quiet = true)
    }

    /** [quiet]: a failed check leaves no message, so a phone offline at launch shows nothing. */
    fun check(quiet: Boolean = false) {
        if (job?.isActive == true) return
        lastCheck = System.currentTimeMillis()
        job = scope.launch {
            if (!quiet) _state.value = UpdateState.Checking
            _state.value = try {
                val release = withContext(Dispatchers.IO) { latest() }
                dir.deleteRecursively() // a finished or abandoned download
                if (release != null && Releases.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't check GitHub for updates.", null)
            }
        }
    }

    /** Download [release] and start installing it. The caller has made sure the app may install packages. */
    fun install(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, null)
            try {
                val apk = withContext(Dispatchers.IO) { download(release) }
                withContext(Dispatchers.IO) { commit(apk) }
                _state.value = UpdateState.Installing(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "The update failed.", release)
            }
        }
    }

    /** The installer's answer, from [InstallReceiver]. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = when (status) {
            // Installed: this process is about to be replaced.
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            // Cancelled at the confirmation: back to offering it.
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Android refused the update (${message ?: "signature mismatch"}). Install it from the release page instead.", release)
            else -> UpdateState.Failed("The update didn't install${message?.let { ": $it" } ?: "."}", release)
        }
    }

    private fun latest(): Release? {
        val request = Request.Builder().url(Releases.LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FFL-Live-Android/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).execute().use { response ->
            // 404: no release published yet.
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from GitHub")
            Releases.parse(response.body.string())
        }
    }

    private fun download(release: Release): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "update.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "FFL-Live-Android/${BuildConfig.VERSION_NAME}").build()
        val sha = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.size.takeIf { it > 0 }
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
        }
        val actual = sha.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && actual != release.sha256) {
            file.delete()
            throw IOException("The download was corrupted (checksum mismatch). Try again.")
        }
        return file
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // A debug build is its own package (.debug), so there it installs the release app alongside.
            if (!BuildConfig.DEBUG) setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds its status extras. The intent is explicit, to our own receiver.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private companion object {
        const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}

/** Where Android's package installer reports on an update: asks for confirmation, or says how it went. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            return
        }
        context.app.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
