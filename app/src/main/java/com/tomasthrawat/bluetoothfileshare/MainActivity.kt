package com.tomasthrawat.bluetoothfileshare

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newCachedThreadPool()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var selectedFile: TextView
    private var fileUri: Uri? = null
    private var adapter: BluetoothAdapter? = null
    private val discoveredDevices = linkedMapOf<String, BluetoothDevice>()
    private var receiverServer: BluetoothServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        deviceList = findViewById(R.id.deviceList)
        selectedFile = findViewById(R.id.selectedFile)
        adapter = BluetoothAdapter.getDefaultAdapter()

        findViewById<Button>(R.id.pairButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.searchButton).setOnClickListener {
            startDeviceDiscovery()
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(discoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(discoveryReceiver, filter)
        }
        findViewById<Button>(R.id.selectFileButton).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 20)
        }
        findViewById<Button>(R.id.receiveButton).setOnClickListener { startReceiver() }

        if (adapter == null) {
            status.text = "This phone does not support Bluetooth."
        } else if (!hasBtPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                10
            )
        } else {
            refreshDevices()
        }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && hasBtPermission()) refreshDevices()
        else if (requestCode == 10) status.text = "Bluetooth permission is required."
    }

    override fun onResume() {
        super.onResume()
        if (::deviceList.isInitialized && hasBtPermission()) refreshDevices()
    }

    private fun refreshDevices() {
        deviceList.removeAllViews()
        val bt = adapter ?: return
        val bonded = try { bt.bondedDevices.orEmpty() } catch (_: SecurityException) { emptySet() }

        if (bonded.isEmpty()) {
            status.text = "Pair the other Android first."
            return
        }

        status.text = if (discoveredDevices.isEmpty()) {
            "Select a paired device, or search for nearby phones."
        } else {
            "Nearby phones found: ${discoveredDevices.size}. Tap a device to send."
        }

        bonded.sortedBy { it.name ?: it.address }.forEach { device ->
            addDeviceButton(device, "Paired")
        }

        discoveredDevices.values
            .filterNot { found -> bonded.any { it.address == found.address } }
            .sortedBy { it.name ?: it.address }
            .forEach { device -> addDeviceButton(device, "Nearby") }
    }

    private fun addDeviceButton(device: BluetoothDevice, label: String) {
        val button = com.google.android.material.button.MaterialButton(this)
        button.text = "$label • ${device.name ?: device.address}"
        button.setOnClickListener { sendSelectedFile(device) }
        deviceList.addView(button, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun startDeviceDiscovery() {
        if (!hasBtPermission()) {
            status.text = "Bluetooth permission is required."
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                ),
                10
            )
            return
        }

        val bt = adapter ?: return
        if (!bt.isEnabled) {
            status.text = "Turn Bluetooth on first."
            return
        }

        discoveredDevices.clear()
        refreshDevices()
        try {
            bt.cancelDiscovery()
            val started = bt.startDiscovery()
            status.text = if (started) {
                "Searching for nearby phones..."
            } else {
                "Could not start Bluetooth search."
            }
        } catch (e: SecurityException) {
            status.text = "Bluetooth permission is required."
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (!hasBtPermission()) return
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    discoveredDevices[device.address] = device
                    refreshDevices()
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    status.text = if (discoveredDevices.isEmpty()) {
                        "No nearby phones found. Make sure the other phone is discoverable."
                    } else {
                        "Search finished. Nearby phones found: ${discoveredDevices.size}."
                    }
                    refreshDevices()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            fileUri = data?.data
            if (fileUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(fileUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            selectedFile.text = fileUri?.let { getName(it) } ?: "No file selected"
        }
    }

    private fun getName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "file"
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { if (it.length >= 0) it.length else -1L } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun sendSelectedFile(device: BluetoothDevice) {
        val uri = fileUri ?: run { status.text = "Select a file first."; return }
        if (!hasBtPermission()) { status.text = "Bluetooth permission is required."; return }

        val bt = adapter ?: return
        if (!bt.isEnabled) { status.text = "Turn Bluetooth on first."; return }

        status.text = "Connecting to " + (device.name ?: device.address) + "..."

        executor.execute {
            var socket: BluetoothSocket? = null
            try {
                try { bt.cancelDiscovery() } catch (_: SecurityException) {}

                socket = device.createRfcommSocketToServiceRecord(uuid)
                try {
                    socket.connect()
                } catch (first: IOException) {
                    try { socket.close() } catch (_: Exception) {}
                    socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    socket.connect()
                }

                DataOutputStream(BufferedOutputStream(socket.outputStream, 4 * 1024 * 1024)).use { data ->
                    val name = getName(uri).ifBlank { "file" }.take(255).toByteArray(Charsets.UTF_8)
                    val size = getFileSize(uri)
                    data.writeInt(name.size)
                    data.write(name)
                    data.writeLong(size)

                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        var sent = 0L
                        var lastUiUpdate = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            data.write(buffer, 0, count)
                            sent += count
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUiUpdate < 250L) continue
                            lastUiUpdate = now
                            val progress = if (size > 0)
                                "Sending " + (sent / 1024) + " KB / " + (size / 1024) + " KB"
                            else
                                "Sending " + (sent / 1024) + " KB"
                            runOnUiThread { status.text = progress }
                        }
                    } ?: throw IOException("Cannot open selected file")
                    data.flush()
                }

                runOnUiThread {
                    status.text = "File sent successfully."
                    Toast.makeText(this, "File sent", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Send failed: " + (e.message ?: "Bluetooth connection error")
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startReceiver() {
        if (!hasBtPermission()) { status.text = "Bluetooth permission is required."; return }
        val bt = adapter
        if (bt == null || !bt.isEnabled) { status.text = "Turn Bluetooth on first."; return }

        try { receiverServer?.close() } catch (_: Exception) {}
        status.text = "Waiting for another Android to send..."

        executor.execute {
            var server: BluetoothServerSocket? = null
            var socket: BluetoothSocket? = null
            try {
                server = bt.listenUsingRfcommWithServiceRecord("Bluetooth File Share", uuid)
                receiverServer = server
                socket = server.accept()
                server.close()
                receiverServer = null

                val input = DataInputStream(BufferedInputStream(socket.inputStream, 4 * 1024 * 1024))
                val nameLength = input.readInt()
                if (nameLength !in 1..255) throw IOException("Invalid file name")

                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                    .replace("/", "_")
                    .replace("\\", "_")
                    .ifBlank { "received_file" }

                val size = input.readLong()
                if (size < -1L) throw IOException("Invalid file size")

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BluetoothFileShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Cannot create output file")

                try {
                    contentResolver.openOutputStream(outUri)?.use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var received = 0L
                        if (size >= 0) {
                            var remaining = size
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw EOFException("Transfer ended early")
                                output.write(buffer, 0, count)
                                remaining -= count
                                received += count
                                runOnUiThread {
                                    status.text = "Receiving " + (received / 1024) + " KB / " + (size / 1024) + " KB"
                                }
                            }
                        } else {
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                runOnUiThread { status.text = "Receiving " + (received / 1024) + " KB" }
                            }
                        }
                    } ?: throw IOException("Cannot open output file")

                    contentResolver.update(
                        outUri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                } catch (e: Exception) {
                    contentResolver.delete(outUri, null, null)
                    throw e
                }

                runOnUiThread {
                    status.text = "Received " + name + " in Downloads/BluetoothFileShare."
                    Toast.makeText(this, "File received", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try { server?.close() } catch (_: Exception) {}
                receiverServer = null
                runOnUiThread {
                    status.text = "Receive failed: " + (e.message ?: "Bluetooth connection error")
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                try { server?.close() } catch (_: Exception) {}
                receiverServer = null
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        try { receiverServer?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}
