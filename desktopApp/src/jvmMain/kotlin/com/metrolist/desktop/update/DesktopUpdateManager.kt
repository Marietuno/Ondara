/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.update

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.update_not_published_stable
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.update.NIGHTLY_TAG
import com.metrolist.shared.update.ReleaseVersion
import com.metrolist.shared.update.checkNightly
import com.metrolist.shared.update.followsNightlyUpdates
import com.metrolist.shared.utils.OsUtil
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object DesktopUpdateManager {
    private const val API_BASE = "https://api.github.com/repos/Marietuno/Ondara"
    internal const val RELEASES_URL = "$API_BASE/releases?per_page=50"
    private const val REGISTRY_PATH = "Software\\Metrolist\\Desktop"
    private const val MAX_PACKAGE_BYTES = 1024L * 1024 * 1024

    private val client = sharedOkHttpClient
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableDownloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress = mutableDownloadProgress.asStateFlow()

    @Volatile private var activeDownload: Call? = null

    data class RecoveryRelease(
        val version: String,
        val url: String,
        val inApp: Boolean,
    )

    suspend fun checkForRecovery(): Result<RecoveryRelease?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val releasesJson = getReleaseJson(RELEASES_URL)
                // A crash offers the nightly even on the stable channel, since it may already carry the fix.
                (findUpdate(releasesJson, BuildConfig.APP_VERSION) ?: newNightly(parseReleases(releasesJson), confirm = false))
                    ?.let {
                        RecoveryRelease(
                            it.version,
                            it.url,
                            it.canInstall(installationRoot() != null),
                        )
                    }
            }
        }

    suspend fun check(): Result<List<GitHubDesktopRelease>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Settings.shared.lastUpdateCheckAttemptTimestamp = System.currentTimeMillis()
                val releases = parseReleases(getReleaseJson(RELEASES_URL))
                currentCoroutineContext().ensureActive()
                Settings.shared.cachedUpdateReleasesJson = json.encodeToString(releases)
                Settings.shared.lastUpdateCheckTimestamp = System.currentTimeMillis()
                releases
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun cachedReleases(): List<GitHubDesktopRelease> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Settings.shared.updateCacheSource != "desktop-github-releases-ondara-v1") {
                    Settings.shared.cachedUpdateReleasesJson = ""
                    Settings.shared.lastUpdateCheckTimestamp = 0L
                    Settings.shared.lastUpdateCheckAttemptTimestamp = 0L
                    Settings.shared.updateCacheSource = "desktop-github-releases-ondara-v1"
                }
                parseReleases(Settings.shared.cachedUpdateReleasesJson)
            }.getOrDefault(emptyList())
        }

    internal fun parseReleases(releasesJson: String): List<GitHubDesktopRelease> =
        json
            .decodeFromString<List<GitHubDesktopRelease>>(releasesJson)
            .filter { !it.draft && (ReleaseVersion.parse(it.version) != null || it.isNightly) }
            .sortedByDescending { ReleaseVersion.parse(it.version) }

    /** The nightly when it differs from the installed package and this platform has a build. */
    internal fun newNightly(
        releases: List<GitHubDesktopRelease>,
        confirm: Boolean,
        currentVersion: String = BuildConfig.APP_VERSION,
    ): GitHubDesktopRelease? {
        val nightly = releases.firstOrNull { it.isNightly } ?: return null
        val asset = nightly.nightlyAsset ?: return null
        val result = checkNightly(currentVersion, nightly.targetCommit, asset.digest, Settings.shared.installedNightlyDigest)
        if (confirm) Settings.shared.installedNightlyDigest = result.installedDigest
        return nightly.takeIf { result.available }
    }

    internal fun availableUpdate(releases: List<GitHubDesktopRelease>): GitHubDesktopRelease? =
        releases.firstOrNull { isNewerDesktopVersion(it.version, BuildConfig.APP_VERSION) && it.hasPlatformPackage }
            ?: newNightly(releases, confirm = true)
                ?.takeIf { followsNightlyUpdates(Settings.shared.updateChannel, BuildConfig.APP_VERSION) }

    internal fun findUpdate(
        releasesJson: String,
        currentVersion: String,
        includePrereleases: Boolean = true,
    ): GitHubDesktopRelease? =
        parseReleases(releasesJson)
            .firstOrNull { (includePrereleases || !it.prerelease) && isNewerDesktopVersion(it.version, currentVersion) && it.hasPlatformPackage }

    suspend fun canInstall(release: GitHubDesktopRelease): Boolean =
        withContext(Dispatchers.IO) { release.canInstall(installationRoot() != null) }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    suspend fun downloadAndLaunch(
        version: String,
        onLaunched: suspend () -> Unit = {},
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutableDownloadProgress.value = null
            runCatching {
                val root = checkNotNull(installationRoot()) { "Ondara installation was not found" }.root
                val release = getRelease("$API_BASE/releases/tags/v$version")
                val errorMsg = getString(Res.string.update_not_published_stable)
                check(release.tagName == "v$version" && release.canInstall(registeredInstallation = true)) { errorMsg }
                val asset = checkNotNull(release.installer) { "Release v$version does not contain $PORTABLE_PACKAGE" }
                check(asset.state == "uploaded") { "The update package is not ready" }
                check(asset.size in 1..MAX_PACKAGE_BYTES) { "The update package has an invalid size" }
                val expectedDigest = parseGitHubSHA256(asset.digest)

                val updateDirectory = portableUpdateDirectory()
                updateDirectory.toFile().deleteRecursively()
                Files.createDirectories(updateDirectory)
                val archive = updateDirectory.resolve("package.zip")
                val staging = updateDirectory.resolve("staging")
                download(asset, archive, expectedDigest)
                currentCoroutineContext().ensureActive()
                extractPortablePackage(archive, staging)
                Files.deleteIfExists(archive)
                check(Files.isRegularFile(staging.resolve(PORTABLE_LAUNCHER))) { "The update package is incomplete" }

                val script = updateDirectory.resolve("apply-update.ps1")
                Files.writeString(script, portableUpdateScript(ProcessHandle.current().pid(), staging, root))
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-WindowStyle",
                        "Hidden",
                        "-File",
                        script.toString(),
                    ).directory(updateDirectory.toFile()).start()
                    onLaunched()
                }
                Unit
            }.onFailure { if (it is CancellationException) throw it }
                .also { activeDownload = null }
        }

    private fun getRelease(url: String): GitHubDesktopRelease = json.decodeFromString(getReleaseJson(url))

    private fun getReleaseJson(url: String): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "Ondara-Desktop/${BuildConfig.APP_VERSION}")
                .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            checkNotNull(response.body).string()
        }
    }

    private suspend fun download(
        asset: GitHubDesktopAsset,
        destination: Path,
        expectedDigest: ByteArray,
    ) {
        val request =
            Request
                .Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", "Ondara-Desktop/${BuildConfig.APP_VERSION}")
                .build()
        val call = client.newCall(request)
        activeDownload = call
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Manager download returned ${response.code}" }
                val body = checkNotNull(response.body)
                check(body.contentLength() < 0 || body.contentLength() == asset.size) { "Manager download size mismatch" }
                body.byteStream().buffered().use { input ->
                    Files.newOutputStream(destination).buffered().use { output ->
                        copyVerifiedUpdate(input, output, asset.size, expectedDigest) { mutableDownloadProgress.value = it }
                    }
                }
            }
        } finally {
            activeDownload = null
        }
    }

    // Ondara ships as a portable folder; it updates itself when it runs from that folder and can write to it.
    private fun installationRoot(): DesktopInstallation? =
        runCatching {
            if (!OsUtil.isWindows) return null
            val command =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null) ?: return null
            val launcher = Path.of(command)
            if (!launcher.fileName.toString().equals(PORTABLE_LAUNCHER, ignoreCase = true)) return null
            val root = launcher.parent
            check(Files.isRegularFile(root.resolve("install-manifest.json"))) { "Ondara folder is incomplete" }
            check(Files.isWritable(root)) { "Ondara folder is read-only" }
            DesktopInstallation(root, BuildConfig.APP_VERSION)
        }.getOrNull()

    private fun portableUpdateDirectory(): Path =
        Path.of(System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: System.getProperty("java.io.tmpdir"), "Ondara", "update")

    private fun extractPortablePackage(
        archive: Path,
        destination: Path,
    ) {
        val target = destination.toAbsolutePath().normalize()
        Files.createDirectories(target)
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val output = target.resolve(entry.name).normalize()
                check(output.startsWith(target)) { "The update package contains an invalid path" }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    zip.getInputStream(entry).use { input -> Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
    }

    // Waits for Ondara to exit, copies the new files over the folder without deleting anything else, and restarts it.
    private fun portableUpdateScript(
        pid: Long,
        staging: Path,
        root: Path,
    ): String {
        fun quoted(path: Path) = "'" + path.toString().replace("'", "''") + "'"
        return """
            |${'$'}ErrorActionPreference = 'SilentlyContinue'
            |while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 500 }
            |Start-Sleep -Seconds 1
            |robocopy ${quoted(staging)} ${quoted(root)} /E /R:10 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
            |Start-Process -FilePath ${quoted(root.resolve(PORTABLE_LAUNCHER))}
            |Remove-Item -Recurse -Force ${quoted(staging)}
            """.trimMargin()
    }
}

internal const val PORTABLE_PACKAGE = "Ondara-Desktop-Windows.zip"
internal const val PORTABLE_LAUNCHER = "Ondara.exe"

@Serializable
private data class DesktopInstallMarker(
    val schema: Int,
    val appId: String,
    val version: String,
)

@Serializable
data class GitHubDesktopRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubDesktopAsset>,
    @SerialName("target_commitish") val targetCommit: String? = null,
) {
    val version: String get() = tagName.removePrefix("v")
    val isNightly: Boolean get() = tagName == NIGHTLY_TAG

    /** Nightlies share one tag, so their package digest tells them apart. */
    val updateKey: String get() = if (isNightly) "$NIGHTLY_TAG:${nightlyAsset?.digest}" else version

    // Nightly assets keep fixed names so their download links never change.
    val nightlyAsset: GitHubDesktopAsset?
        get() =
            assets.firstOrNull { asset ->
                asset.state == "uploaded" &&
                    when {
                        OsUtil.isWindows -> asset.name.endsWith("-windows.zip")
                        OsUtil.isMac -> asset.name.contains("-macos", ignoreCase = true)
                        else -> asset.name.endsWith("-linux-${desktopArchitecture()}.AppImage")
                    }
            }
    val installer: GitHubDesktopAsset?
        get() = assets.singleOrNull { it.name == PORTABLE_PACKAGE }

    // Some releases only carry the Android APK; Windows must not offer those as desktop updates.
    val hasPlatformPackage: Boolean
        get() = !OsUtil.isWindows || installer != null
    val url: String get() =
        nightlyAsset?.takeIf { isNightly }?.downloadUrl
            ?: "https://github.com/Marietuno/Ondara/releases/tag/$tagName"

    internal fun canInstall(registeredInstallation: Boolean): Boolean =
        registeredInstallation && !draft && !prerelease && ReleaseVersion.parse(version)?.prerelease?.isEmpty() == true
}

@Serializable
data class GitHubDesktopAsset(
    val name: String,
    val state: String,
    val size: Long,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)

internal fun parseGitHubSHA256(value: String?): ByteArray {
    val digest = value?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
    require(digest != null && digest.length == 64 && digest.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "The GitHub asset is missing a valid SHA-256 digest"
    }
    return digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal fun isNewerDesktopVersion(
    candidate: String,
    current: String,
): Boolean {
    val candidateVersion = ReleaseVersion.parse(candidate) ?: return false
    val currentVersion = ReleaseVersion.parse(current) ?: return false
    return candidateVersion > currentVersion
}

private data class DesktopInstallation(
    val root: Path,
    val version: String,
)

internal fun isRunningRegisteredDesktop(
    root: Path,
    command: String?,
): Boolean = command != null && runCatching { Files.isSameFile(root.resolve("app/Metrolist.exe"), Path.of(command)) }.getOrDefault(false)

internal suspend fun copyVerifiedUpdate(
    input: InputStream,
    output: OutputStream,
    expectedSize: Long,
    expectedDigest: ByteArray,
    onProgress: (Int) -> Unit,
) {
    require(expectedSize > 0)
    val digest = MessageDigest.getInstance("SHA-256")
    var downloaded = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    onProgress(0)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        check(downloaded + count <= expectedSize) { "Manager download exceeded its expected size" }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        downloaded += count
        onProgress(((downloaded * 100) / expectedSize).toInt())
    }
    currentCoroutineContext().ensureActive()
    check(downloaded == expectedSize) { "Manager download was incomplete" }
    check(MessageDigest.isEqual(digest.digest(), expectedDigest)) { "Manager SHA-256 verification failed" }
}

internal fun desktopUpdateCheckIsDue(
    lastAttempt: Long,
    now: Long,
): Boolean = lastAttempt !in 1..now || now - lastAttempt >= 60 * 60 * 1_000L

private fun desktopArchitecture(): String =
    when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
