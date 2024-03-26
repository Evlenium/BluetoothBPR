package com.practicum.bluetoothbpr.device.ui

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.practicum.bluetoothbpr.R
import com.practicum.bluetoothbpr.device.TextUtil
import com.practicum.bluetoothbpr.device.data.SerialService
import com.practicum.bluetoothbpr.device.domain.SerialListener

import java.util.*

class TerminalFragment : Fragment(), ServiceConnection,
    SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceAddress: String? = null
    private var service: SerialService? = null
    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var hexWatcher: TextUtil.HexWatcher? = null
    private var connected: com.practicum.bluetoothbpr.device.ui.TerminalFragment.Connected =
        com.practicum.bluetoothbpr.device.ui.TerminalFragment.Connected.False
    private var initialStart = true
    private var hexEnabled = false
    private var pendingNewline = false
    private var newline: String = TextUtil.newline_crlf

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceAddress = requireArguments().getString("device")
    }

    override fun onDestroy() {
        if (connected != com.practicum.bluetoothbpr.device.ui.TerminalFragment.Connected.False) disconnect()
        requireActivity().stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this) else requireActivity().startService(
            Intent(
                activity,
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !requireActivity().isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        requireActivity().bindService(
            Intent(getActivity(), SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            requireActivity().unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            requireActivity().runOnUiThread { connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialService.SerialBinder).getService
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            requireActivity().runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view: View = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById<TextView>(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(resources.getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        sendText = view.findViewById<TextView>(R.id.send_text)
        hexWatcher = TextUtil.HexWatcher(sendText ?: return null)
        hexWatcher?.enable(hexEnabled)
        sendText?.addTextChangedListener(hexWatcher)
        sendText?.hint = if (hexEnabled) "HEX mode" else ""
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { v: View? ->
            send(
                sendText?.text.toString()
            )
        }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        menu.findItem(R.id.hex).isChecked = hexEnabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.clear) {
            receiveText!!.text = ""
            true
        } else if (id == R.id.newline) {
            val newlineNames = resources.getStringArray(R.array.newline_names)
            val newlineValues = resources.getStringArray(R.array.newline_values)
            val pos = Arrays.asList(*newlineValues).indexOf(newline)
            val builder = AlertDialog.Builder(
                activity
            )
            builder.setTitle("Newline")
            builder.setSingleChoiceItems(newlineNames, pos) { dialog: DialogInterface, item1: Int ->
                newline = newlineValues[item1]
                dialog.dismiss()
            }
            builder.create().show()
            true
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled
            sendText!!.text = ""
            hexWatcher?.enable(hexEnabled)
            sendText!!.hint = if (hexEnabled) "HEX mode" else ""
            item.isChecked = hexEnabled
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected =
                com.practicum.bluetoothbpr.device.ui.TerminalFragment.Connected.Pending
            val socket = SerialSocket(requireActivity().applicationContext, device)
            service!!.connect(socket)
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = com.practicum.ble.TerminalFragment.Connected.False
        service!!.disconnect()
    }

    private fun send(str: String) {
        if (connected != com.practicum.ble.TerminalFragment.Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            if (hexEnabled) {
                val sb = StringBuilder()
                TextUtil.toHexString(sb, TextUtil.fromHexString(str))
                TextUtil.toHexString(sb, newline.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder(
                """
                      $msg
                      
                      """.trimIndent()
            )
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray?>?) {
        val spn = SpannableStringBuilder()
        if (datas != null) {
            for (data in datas) {
                if (data != null) {
                    if (hexEnabled) {
                        spn.append(TextUtil.toHexString(data)).append('\n')
                    } else {

                        var msg = String(data)
                        if (newline == TextUtil.newline_crlf && msg.isNotEmpty()) {
                            // don't show CR as ^M if directly before LF
                            msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                            // special handling if CR and LF come in separate fragments
                            if (pendingNewline && msg[0] == '\n') {
                                if (spn.length >= 2) {
                                    spn.delete(spn.length - 2, spn.length)
                                } else {
                                    val edt = receiveText!!.editableText
                                    if (edt != null && edt.length >= 2) edt.delete(
                                        edt.length - 2,
                                        edt.length
                                    )
                                }
                            }
                            pendingNewline = msg[msg.length - 1] == '\r'
                        }
                        spn.append(TextUtil.toCaretString(msg, newline.isNotEmpty()))
                    }
                }
            }
        }
        receiveText!!.append(spn)
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
                  $str
                  
                  """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText!!.append(spn)
    }

    /*
     * SerialListener
     */

    override fun onSerialConnect() {
        status("connected")
        connected = com.practicum.ble.TerminalFragment.Connected.True
    }

    override fun onSerialConnectError(e: Exception?) {
        status("connection failed: " + e?.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        val datas = ArrayDeque<ByteArray?>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray?>?) {
        receive(datas)
    }


    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e?.message)
        disconnect()
    }
}

