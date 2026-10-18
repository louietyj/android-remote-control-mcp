package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    NOT_GRANTED,
    GRANTED,
    ;

    /**
     * NOT_RUNNING counts as available: the tool list is frozen at server start, which may precede
     * Shizuku starting after boot; the call itself errors clearly if Shizuku is still down.
     */
    val isToolAvailable: Boolean get() = this == GRANTED || this == NOT_RUNNING
}

/** Thin wrapper over the Shizuku API: status checks, the permission dialog, and opening the app. */
object ShizukuAccess {
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val MIN_SUPPORTED_VERSION = 11
    private const val PERMISSION_REQUEST_CODE = 0x5348

    fun status(context: Context): ShizukuStatus {
        if (!isBinderAlive()) {
            return if (isManagerInstalled(context)) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        }
        return if (isPermissionGranted()) ShizukuStatus.GRANTED else ShizukuStatus.NOT_GRANTED
    }

    /** Binder present and recent enough to support UserService. Sui (root) has no manager app. */
    fun isBinderAlive(): Boolean =
        Shizuku.pingBinder() &&
            try {
                Shizuku.getVersion() >= MIN_SUPPORTED_VERSION
            } catch (_: IllegalStateException) {
                false
            }

    fun isPermissionGranted(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: IllegalStateException) {
            false
        }

    /**
     * Shows Shizuku's permission dialog. [onResult] gets `false` immediately if Shizuku is down or
     * the user previously chose "deny and don't ask again" (only the Shizuku app can undo that).
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isBinderAlive() || Shizuku.shouldShowRequestPermissionRationale()) {
            onResult(false)
            return
        }
        val listener =
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(
                    requestCode: Int,
                    grantResult: Int,
                ) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    /** Opens the Shizuku manager app (e.g. to start the service), if installed. */
    fun openManagerApp(context: Context) {
        val intent =
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return
        context.startActivity(intent)
    }

    private fun isManagerInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
