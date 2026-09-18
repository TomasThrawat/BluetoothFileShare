package com.tomasthrawat.bluetoothfileshare

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.*
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val uuid = UUID.fromString("7f7d7a1b-8d52-4d22-9d72-2e7e4d0c2a11")
    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var selectedFile: TextView
    private var fileUri: Uri? = null
    private var adapter: BluetoothAdapter? = null

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
        findViewById<Button>(R.id.selectFileButton).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 20)
        }
        findViewById<Button>(R.id.receiveButton).setOnClickListener { startReceiver() }

        if (adapter == null) {
            status.text = "This phone does not support Bluetooth."
        } else if (!hasBtPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 10)
        } else {
            refreshDevices()
        }
    }

    private fun hasBtPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
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
        val bonded = try { adapter?.bondedDevices.orEmpty() } catch (_: SecurityException) { emptySet() }
        if (bonded.isEmpty()) {
            status.text = "Pair the other Android first."
            return
        }
        status.text = "Select a paired device to send."
        bonded.sortedBy { it.name ?: it.address }.forEach { device ->
            val b = Button(this)
            b.text = device.name ?: device.address
            b.setOnClickListener { sendSelectedFile(device) }
            deviceList.addView(b, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            fileUri = data?.data
            selectedFile.text = fileUri?.let { getName(it) } ?: "No file selected"
        }
    }

    private fun getName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "file"
    }

    private fun sendSelectedFile(device: BluetoothDevice) {
        val uri = fileUri ?: run { status.text = "Select a file first."; return }
        if (!hasBtPermission()) return
        status.text = "Connecting to ${device.name ?: device.address}..."
        executor.execute {
            try {
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                DataOutputStream(BufferedOutputStream(socket.outputStream)).use { data ->
                    val name = getName(uri).take(255).toByteArray(Charsets.UTF_8)
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size < 0) throw IOException("Cannot determine file size")
                    data.writeInt(name.size)
                    data.write(name)
                    data.writeLong(size)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            data.write(buf, 0, n)
                            sent += n
                            runOnUiThread { status.text = "Sending ${sent / 1024} KB / ${size / 1024} KB" }
                        }
                    }
                    data.flush()
                }
                socket.close()
                runOnUiThread { status.text = "File sent successfully." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Send failed: ${e.message ?: "connection error"}" }
            }
        }
    }

    private fun startReceiver() {
        if (!hasBtPermission()) return
        status.text = "Waiting for another device..."
        executor.execute {
            var server: BluetoothServerSocket? = null
            var socket: BluetoothSocket? = null
            try {
                server = adapter?.listenUsingRfcommWithServiceRecord("Bluetooth File Share", uuid)
                socket = server?.accept()
                server?.close()
                if (socket == null) throw IOException("No connection")
                val input = DataInputStream(BufferedInputStream(socket!!.inputStream))
                val nameLen = input.readInt()
                if (nameLen !in 1..255) throw IOException("Invalid file name")
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val size = input.readLong()
                if (size < 0) throw IOException("Invalid file size")

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BluetoothFileShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Cannot create output file")
                try {
                    contentResolver.openOutputStream(outUri)!!.use { out ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = size
                        var received = 0L
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("Transfer ended early")
                            out.write(buf, 0, n)
                            remaining -= n.toLong()
                            received += n
                            runOnUiThread { status.text = "Receiving ${received / 1024} KB / ${size / 1024} KB" }
                        }
                    }
                    contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(outUri, null, null)
                    throw e
                }
                socket!!.close()
                runOnUiThread { status.text = "Received $name in Downloads/BluetoothFileShare." }
            } catch (e: Exception) {
                try { server?.close() } catch (_: Exception) {}
                runOnUiThread { status.text = "Receive failed: ${e.message ?: "connection error"}" }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
