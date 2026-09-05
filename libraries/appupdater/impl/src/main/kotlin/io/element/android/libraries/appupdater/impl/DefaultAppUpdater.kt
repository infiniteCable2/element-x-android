/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.appupdater.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.appupdater.api.AppUpdateState
import io.element.android.libraries.appupdater.api.AppUpdater
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultAppUpdater(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val callFactory: Call.Factory,
    private val buildMeta: BuildMeta,
    private val coroutineDispatchers: CoroutineDispatchers,
) : AppUpdater {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    private val operationMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var resolvedUpdate: ResolvedAppUpdate? = null
    private var downloadedApk: File? = null

    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    override fun checkInBackground() {
        if (mutableState.value != AppUpdateState.Idle) return
        appCoroutineScope.launch {
            checkForUpdate()
        }
    }

    override suspend fun checkForUpdate() = operationMutex.withLock {
        mutableState.value = AppUpdateState.Checking
        try {
            val update = withContext(coroutineDispatchers.io) {
                fetchManifest().resolve(
                    currentVersionCode = buildMeta.versionCode,
                    manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
                )
            }
            resolvedUpdate = update
            downloadedApk = null
            mutableState.value = if (update == null) {
                AppUpdateState.UpToDate
            } else {
                AppUpdateState.Available(update.versionName)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Timber.w(exception, "Unable to check for an app update")
            mutableState.value = AppUpdateState.Failed
        }
    }

    override suspend fun downloadUpdate() = operationMutex.withLock {
        val update = resolvedUpdate ?: run {
            mutableState.value = AppUpdateState.Failed
            return@withLock
        }

        mutableState.value = AppUpdateState.Downloading(update.versionName)
        try {
            downloadedApk = withContext(coroutineDispatchers.io) {
                downloadAndVerify(update)
            }
            mutableState.value = AppUpdateState.ReadyToInstall(update.versionName)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Timber.w(exception, "Unable to download the app update")
            mutableState.value = AppUpdateState.Failed
        }
    }

    override fun installDownloadedUpdate() {
        val apk = downloadedApk?.takeIf(File::isFile) ?: run {
            mutableState.value = AppUpdateState.Failed
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }

            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (exception: Exception) {
            Timber.w(exception, "Unable to open the app update installer")
            mutableState.value = AppUpdateState.Failed
        }
    }

    private fun fetchManifest(): AppUpdateManifest {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_MANIFEST_URL)
            .get()
            .build()
        return callFactory.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Update manifest request failed" }
            val body = requireNotNull(response.body) { "Update manifest is empty" }
            val manifestBytes = body.byteStream().readAtMost(MAX_MANIFEST_BYTES)
            json.decodeFromString<AppUpdateManifest>(manifestBytes.toString(Charsets.UTF_8))
        }
    }

    private fun downloadAndVerify(update: ResolvedAppUpdate): File {
        val target = File(context.cacheDir, UPDATE_APK_FILE_NAME)
        val temporaryTarget = File(context.cacheDir, "$UPDATE_APK_FILE_NAME.part")
        temporaryTarget.delete()

        try {
            val request = Request.Builder()
                .url(update.apkUrl)
                .get()
                .build()
            val digest = MessageDigest.getInstance("SHA-256")
            callFactory.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Update APK request failed" }
                val body = requireNotNull(response.body) { "Update APK is empty" }
                val contentLength = body.contentLength()
                require(contentLength <= MAX_APK_BYTES || contentLength == -1L) { "Update APK is too large" }
                body.byteStream().use { input ->
                    temporaryTarget.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            totalBytes += count
                            require(totalBytes <= MAX_APK_BYTES) { "Update APK is too large" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }

            require(MessageDigest.isEqual(update.sha256.decodeHex(), digest.digest())) { "Update APK checksum does not match" }
            verifyApkIdentity(temporaryTarget, update.versionCode)
            target.delete()
            require(temporaryTarget.renameTo(target)) { "Unable to store verified update APK" }
            return target
        } catch (exception: Exception) {
            temporaryTarget.delete()
            throw exception
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkIdentity(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageManager = context.packageManager
        val archiveInfo = requireNotNull(packageManager.getPackageArchiveInfo(apk.absolutePath, flags)) {
            "Downloaded file is not an APK"
        }
        require(archiveInfo.packageName == context.packageName) { "Update APK belongs to another application" }
        require(PackageInfoCompat.getLongVersionCode(archiveInfo) == expectedVersionCode) { "Update APK version does not match" }

        val currentInfo = packageManager.getPackageInfo(context.packageName, flags)
        require(currentInfo.signerHashes() == archiveInfo.signerHashes()) { "Update APK signature does not match" }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerHashes(): Set<String> {
        val packageSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            signatures.orEmpty()
        }
        return packageSignatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        totalBytes += count
        require(totalBytes <= maxBytes) { "Response is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun String.decodeHex(): ByteArray {
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_APK_BYTES = 500L * 1024L * 1024L
private const val UPDATE_APK_FILE_NAME = "verified-app-update.apk"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
