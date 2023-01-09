/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater.controller

import android.content.Context
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.text.TextUtils
import android.util.Log
import androidx.preference.PreferenceManager
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateStatus
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile
import kotlin.math.roundToInt

internal class ABUpdateInstaller private constructor(
    context: Context,
    private val mUpdaterController: UpdaterController
) {
    private val applicationContext = context.applicationContext
    private var downloadId: String? = null
    private val updateEngine = UpdateEngine()
    private var bound = false
    private var finalizing = false
    private var progress = 0

    private val updateEngineCallback: UpdateEngineCallback = object : UpdateEngineCallback() {
        override fun onStatusUpdate(status: Int, percent: Float) {
            val update = mUpdaterController.getActualUpdate(downloadId)
            if (update == null) {
                // We read the id from a preference, the update could no longer exist
                installationDone(status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT)
                return
            }

            when (status) {
                UpdateEngine.UpdateStatusConstants.DOWNLOADING,
                UpdateEngine.UpdateStatusConstants.FINALIZING -> {
                    if (update.status !== UpdateStatus.INSTALLING) {
                        update.status = UpdateStatus.INSTALLING
                        mUpdaterController.notifyUpdateChange(downloadId)
                    }
                    progress = (percent * 100).roundToInt()
                    update.installProgress = progress
                    finalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING
                    update.finalizing = finalizing
                    mUpdaterController.notifyInstallProgress(downloadId)
                }
                UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT -> {
                    installationDone(true)
                    update.installProgress = 0
                    update.status = UpdateStatus.INSTALLED
                    mUpdaterController.notifyUpdateChange(downloadId)
                }
                UpdateEngine.UpdateStatusConstants.IDLE -> {

                    // The service was restarted because we thought we were installing an
                    // update, but we aren't, so clear everything.
                    installationDone(false)
                }
            }
        }

        override fun onPayloadApplicationComplete(errorCode: Int) {
            if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                installationDone(false)
                val update = mUpdaterController.getActualUpdate(downloadId)!!
                update.installProgress = 0
                update.status = UpdateStatus.INSTALLATION_FAILED
                mUpdaterController.notifyUpdateChange(downloadId)
            }
        }
    }

    fun install(downloadId: String) {
        if (isInstallingUpdate(applicationContext)) {
            Log.e(TAG, "Already installing an update")
            return
        }

        this.downloadId = downloadId

        val update = mUpdaterController.getActualUpdate(downloadId)!!
        val file = update.file
        if (file?.exists() != true) {
            Log.e(TAG, "The given update doesn't exist")
            update.status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(downloadId)
            return
        }

        val offset: Long
        var headerKeyValuePairs: Array<String?>
        try {
            val zipFile = ZipFile(file)
            offset = Utils.getZipEntryOffset(zipFile, Constants.AB_PAYLOAD_BIN_PATH)
            val payloadPropEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH)
            zipFile.getInputStream(payloadPropEntry).use { `is` ->
                InputStreamReader(`is`).use { isr ->
                    BufferedReader(isr).use { br ->
                        val lines: MutableList<String> = ArrayList()
                        var line: String
                        while (br.readLine().also { line = it } != null) {
                            lines.add(line)
                        }
                        headerKeyValuePairs = lines.toTypedArray()
                    }
                }
            }
            zipFile.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not prepare $file", e)
            update.status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(this.downloadId)
            return
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not prepare $file", e)
            update.status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(this.downloadId)
            return
        }

        if (!bound) {
            bound = updateEngine.bind(updateEngineCallback)
            if (!bound) {
                Log.e(TAG, "Could not bind")
                update.status = UpdateStatus.INSTALLATION_FAILED
                mUpdaterController.notifyUpdateChange(downloadId)
                return
            }
        }

        val enableABPerfMode = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Constants.PREF_AB_PERF_MODE, false)
        updateEngine.setPerformanceMode(enableABPerfMode)

        val zipFileUri = "file://" + file.absolutePath
        updateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs)

        update.status = UpdateStatus.INSTALLING
        mUpdaterController.notifyUpdateChange(this.downloadId)

        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .putString(PREF_INSTALLING_AB_ID, this.downloadId)
            .apply()
    }

    fun reconnect() {
        if (!isInstallingUpdate(applicationContext)) {
            Log.e(TAG, "reconnect: Not installing any update")
            return
        }

        if (bound) {
            return
        }

        downloadId = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getString(PREF_INSTALLING_AB_ID, null)

        // We will get a status notification as soon as we are connected
        bound = updateEngine.bind(updateEngineCallback)
        if (!bound) {
            Log.e(TAG, "Could not bind")
        }
    }

    private fun installationDone(needsReboot: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val id = if (needsReboot) {
            prefs.getString(PREF_INSTALLING_AB_ID, null)
        } else {
            null
        }

        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .putString(Constants.PREF_NEEDS_REBOOT_ID, id)
            .remove(PREF_INSTALLING_AB_ID)
            .apply()
    }

    fun cancel() {
        if (!isInstallingUpdate(applicationContext)) {
            Log.e(TAG, "cancel: Not installing any update")
            return
        }

        if (!bound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }

        updateEngine.cancel()
        installationDone(false)

        val update = mUpdaterController.getActualUpdate(downloadId)!!
        update.status = UpdateStatus.INSTALLATION_CANCELLED
        mUpdaterController.notifyUpdateChange(downloadId)
    }

    fun setPerformanceMode(enable: Boolean) = updateEngine.setPerformanceMode(enable)

    fun suspend() {
        if (!isInstallingUpdate(applicationContext)) {
            Log.e(TAG, "cancel: Not installing any update")
            return
        }

        if (!bound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }

        updateEngine.suspend()

        val update = mUpdaterController.getActualUpdate(downloadId)!!
        update.status = UpdateStatus.INSTALLATION_SUSPENDED
        mUpdaterController.notifyUpdateChange(downloadId)

        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .putString(PREF_INSTALLING_SUSPENDED_AB_ID, downloadId)
            .apply()
    }

    fun resume() {
        if (!isInstallingUpdateSuspended(applicationContext)) {
            Log.e(TAG, "cancel: No update is suspended")
            return
        }

        if (!bound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }

        updateEngine.resume()

        val update = mUpdaterController.getActualUpdate(downloadId)!!
        update.status = UpdateStatus.INSTALLING
        mUpdaterController.notifyUpdateChange(downloadId)
        update.installProgress = progress
        update.finalizing = finalizing
        mUpdaterController.notifyInstallProgress(downloadId)

        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
            .apply()
    }

    companion object {
        private const val TAG = "ABUpdateInstaller"
        private const val PREF_INSTALLING_AB_ID = "installing_ab_id"
        private const val PREF_INSTALLING_SUSPENDED_AB_ID = "installing_suspended_ab_id"

        private var sInstance: ABUpdateInstaller? = null

        @JvmStatic
        @Synchronized
        fun isInstallingUpdate(context: Context): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)

            return pref.getString(PREF_INSTALLING_AB_ID, null) != null ||
                    pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null
        }

        @JvmStatic
        @Synchronized
        fun isInstallingUpdate(context: Context, downloadId: String): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)

            return downloadId == pref.getString(PREF_INSTALLING_AB_ID, null) ||
                    TextUtils.equals(
                        pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null),
                        downloadId
                    )
        }

        @JvmStatic
        @Synchronized
        fun isInstallingUpdateSuspended(context: Context): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)

            return pref.getString(PREF_INSTALLING_SUSPENDED_AB_ID, null) != null
        }

        @JvmStatic
        @Synchronized
        fun isWaitingForReboot(context: Context, downloadId: String?): Boolean {
            val waitingId = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null)

            return TextUtils.equals(waitingId, downloadId)
        }

        @JvmStatic
        @Synchronized
        fun getInstance(
            context: Context,
            updaterController: UpdaterController
        ): ABUpdateInstaller? {
            if (sInstance == null) {
                sInstance = ABUpdateInstaller(context, updaterController)
            }
            return sInstance
        }
    }
}
