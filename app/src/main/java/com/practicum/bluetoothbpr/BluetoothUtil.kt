package com.practicum.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.practicum.bluetoothbpr.R
import kotlin.reflect.KFunction0

object BluetoothUtil {
    /**
     * Android 12 permission handling
     */
    private fun showRationaleDialog(fragment: Fragment, listener: DialogInterface.OnClickListener) {
        val builder = AlertDialog.Builder(fragment.activity)
        builder.setTitle(fragment.getString(R.string.bluetooth_permission_title))
        builder.setMessage(fragment.getString(R.string.bluetooth_permission_grant))
        builder.setNegativeButton("Cancel", null)
        builder.setPositiveButton("Continue", listener)
        builder.show()
    }

    private fun showSettingsDialog(fragment: Fragment) {
        val s = fragment.resources.getString(
            fragment.resources.getIdentifier(
                "@android:string/permgrouplab_nearby_devices",
                null,
                null
            )
        )
        val builder = AlertDialog.Builder(fragment.activity)
        builder.setTitle(fragment.getString(R.string.bluetooth_permission_title))
        builder.setMessage(
            String.format(
                fragment.getString(R.string.bluetooth_permission_denied),
                s
            )
        )
        builder.setNegativeButton("Cancel", null)
        builder.setPositiveButton(
            "Settings"
        ) { dialog: DialogInterface?, which: Int ->
            fragment.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + "com.practicum.ble")
                )
            )
        }
        builder.show()
    }

    /**
     * CONNECT + SCAN are granted together in same permission group, so actually no need to check/request both, but one never knows
     */
    fun hasPermissions(
        fragment: Fragment,
        requestPermissionLauncher: ActivityResultLauncher<Array<String>>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val missingPermissions =
            (fragment.requireActivity()
                .checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    ) or (fragment.requireActivity()
                .checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        val showRationale =
            (fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
                    or fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN))
        val permissions =
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        return if (missingPermissions) {
            if (showRationale) {
                showRationaleDialog(
                    fragment
                ) { dialog: DialogInterface?, which: Int ->
                    requestPermissionLauncher.launch(
                        permissions
                    )
                }
            } else {
                requestPermissionLauncher.launch(permissions)
            }
            false
        } else {
            true
        }
    }

    fun onPermissionsResult(
        fragment: Fragment,
        grants: Map<String, @JvmSuppressWildcards Boolean>,
        cb: KFunction0<Unit>,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val showRationale =
            (fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
                    or fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN))
        val granted = grants.values.stream().reduce(
            true
        ) { a: Boolean, b: Boolean -> a && b }
        if (granted) {
            cb.call()
        } else if (showRationale) {
            showRationaleDialog(
                fragment
            ) { dialog: DialogInterface?, which: Int -> cb.call() }
        } else {
            showSettingsDialog(fragment)
        }
    }

    interface PermissionGrantedCallback {
        fun call()
    }

    /*
     * more efficient caching of name than BluetoothDevice which always does RPC
     */
    internal class Device @SuppressLint("MissingPermission") constructor(var device: BluetoothDevice) :
        Comparable<Device?> {
        var name: String?

        init {
            name = device.name
        }

        override fun equals(o: Any?): Boolean {
            return if (o is Device) device == o.device else false
        }

        override fun compareTo(other: Device?): Int {
            val thisValid = name != null && !name!!.isEmpty()
            val otherValid = other?.name != null && !other.name!!.isEmpty()
            if (thisValid && otherValid) {
                val ret = name!!.compareTo(other?.name!!)
                return if (ret != 0) ret else device.address.compareTo(other.device.address)
            }
            if (thisValid) return -1
            return if (otherValid) +1 else device.address.compareTo(other!!.device.address)
        }
        /**
         * sort by name, then address. sort named devices first
         */
    }
}