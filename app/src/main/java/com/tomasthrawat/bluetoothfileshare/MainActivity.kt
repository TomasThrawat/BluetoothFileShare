package com.tomasthrawat.bluetoothfileshare

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)
    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var selectedFile: TextView
    private var fileUri: Uri? = null
    private val peers = linkedMapOf<String, Peer>()
    private val deviceId = UUID.randomUUID().toString().substring(0, 8)
    private val discoveryPort = 45454
    private val transferPort = 45455
    private var serverSocket: ServerSocket? = null
    private data class Peer(val name: String, val address: InetAddress)

    private val selectFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                fileUri = result.data?.data
                fileUri?.let {
                    try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    selectedFile.text = getName(it)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        deviceList = findViewById(R.id.deviceList)
        selectedFile = findViewById(R.id.selectedFile)

        findViewById<Button>(R.id.selectFileButton).setOnClickListener {
            selectFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        }
        findViewById<MaterialButton>(R.id.searchButton).setOnClickListener { searchForPhones() }
        findViewById<Button>(R.id.receiveButton).setOnClickListener { startTransferServer() }

        status.text = "Wi-Fi file sharing starting..."
        startTransferServer()
        startDiscoveryListener()
        startDiscoveryBeacon()
    }

    private fun searchForPhones() {
        peers.clear()
        refreshPeers()
        status.text = "Searching for phones on the same Wi-Fi..."
        executor.execute {
            repeat(5) {
                sendDiscoveryBeacon()
                Thread.sleep(600)
            }
            runOnUiThread {
                status.text = if (peers.isEmpty()) {
                    "No phones found. Make sure both phones use the same Wi-Fi."
                } else {
                    "Phones found: " + peers.size + ". Tap one to send."
                }
            }
        }
    }

    private fun startDiscoveryListener() {
        executor.execute {
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(discoveryPort))
                    socket.soTimeout = 1500
                    val buffer = ByteArray(2048)
                    while (running.get()) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val parts = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).split("|", limit = 3)
                            if (parts.size == 3 && parts[0] == "WIFI_FILE_SHARE" && parts[1] != deviceId) {
                                peers[parts[1]] = Peer(parts[2].ifBlank { "Android phone" }, packet.address)
                                runOnUiThread {
                                    refreshPeers()
                                    status.text = "Phones found: " + peers.size + ". Tap one to send."
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: IOException) {
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Wi-Fi discovery failed: " + (e.message ?: "network error") }
            }
        }
    }

    private fun startDiscoveryBeacon() {
        executor.execute {
            repeat(12) {
                if (!running.get()) return@execute
                sendDiscoveryBeacon()
                Thread.sleep(2500)
            }
        }
    }

    private fun sendDiscoveryBeacon() {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val name = Build.MODEL.ifBlank { "Android phone" }
                val message = ("WIFI_FILE_SHARE|" + deviceId + "|" + name).toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), discoveryPort))
            }
        } catch (_: Exception) {}
    }

    private fun refreshPeers() {
        deviceList.removeAllViews()
        peers.values.sortedBy { it.name.lowercase() }.forEach { peer ->
            val button = MaterialButton(this).apply {
                text = "Send to " + peer.name + "\n" + peer.address.hostAddress
                isAllCaps = false
                setOnClickListener { sendSelectedFile(peer) }
            }
            deviceList.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
        }
    }

    private fun sendSelectedFile(peer: Peer) {
        val uri = fileUri ?: run { status.text = "Select a file first."; return }
        status.text = "Connecting to " + peer.name + "..."
        executor.execute {
            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    tcpNoDelay = true
                    sendBufferSize = 4 * 1024 * 1024
                    receiveBufferSize = 4 * 1024 * 1024
                    connect(InetSocketAddress(peer.address, transferPort), 3000)
                }
                DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 4 * 1024 * 1024)).use { output ->
                    val nameBytes = getName(uri).ifBlank { "file" }.toByteArray(StandardCharsets.UTF_8)
                    val size = getFileSize(uri)
                    output.writeInt(nameBytes.size)
                    output.write(nameBytes)
                    output.writeLong(size)
                    contentResolver.openInputStream(uri)?.use { raw ->
                        BufferedInputStream(raw, 4 * 1024 * 1024).use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            var sent = 0L
                            var lastUi = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                sent += count
                                val now = System.currentTimeMillis()
                                if (now - lastUi >= 250) {
                                    lastUi = now
                                    runOnUiThread {
                                        status.text = if (size > 0) {
                                            "Sending " + String.format("%.1f / %.1f MB", sent / 1048576.0, size / 1048576.0)
                                        } else {
                                            "Sending " + String.format("%.1f MB", sent / 1048576.0)
                                        }
                                    }
                                }
                            }
                        }
                    } ?: throw IOException("Cannot open selected file")
                    output.flush()
                }
                runOnUiThread {
                    status.text = "File sent successfully over Wi-Fi."
                    Toast.makeText(this, "File sent", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Send failed: " + (e.message ?: "Wi-Fi connection error") }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startTransferServer() {
        if (serverSocket != null) {
            status.text = "Ready to receive files over Wi-Fi."
            return
        }
        executor.execute {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = 4 * 1024 * 1024
                    bind(InetSocketAddress(transferPort))
                }
                serverSocket = server
                runOnUiThread { status.text = "Ready to receive files over Wi-Fi." }
                while (running.get()) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    executor.execute { receiveFile(socket) }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Receive server failed: " + (e.message ?: "port unavailable") }
            }
        }
    }

    private fun receiveFile(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 4 * 1024 * 1024
            socket.receiveBufferSize = 4 * 1024 * 1024
            DataInputStream(BufferedInputStream(socket.getInputStream(), 4 * 1024 * 1024)).use { input ->
                val nameLength = input.readInt()
                if (nameLength !in 1..255) throw IOException("Invalid file name")
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val name = String(nameBytes, StandardCharsets.UTF_8).replace("/", "_").replace("\\", "_").ifBlank { "received_file" }
                val size = input.readLong()
                if (size < 0) throw IOException("Invalid file size")
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WiFiFileShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Cannot create output file")
                try {
                    contentResolver.openOutputStream(outputUri)?.let { raw ->
                        BufferedOutputStream(raw, 4 * 1024 * 1024).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var remaining = size
                            var received = 0L
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw EOFException("Transfer ended early")
                                output.write(buffer, 0, count)
                                remaining -= count
                                received += count
                                if (received % (8L * 1024 * 1024) < count) {
                                    runOnUiThread {
                                        status.text = String.format("Receiving %.1f / %.1f MB", received / 1048576.0, size / 1048576.0)
                                    }
                                }
                            }
                            output.flush()
                        }
                    } ?: throw IOException("Cannot open output file")
                    contentResolver.update(outputUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    runOnUiThread {
                        status.text = "Received " + name + " in Downloads/WiFiFileShare."
                        Toast.makeText(this, "File received", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    contentResolver.delete(outputUri, null, null)
                    throw e
                }
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "Receive failed: " + (e.message ?: "Wi-Fi transfer error") }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "file"
        } ?: "file"

    private fun getFileSize(uri: Uri): Long = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { if (it.length >= 0) it.length else -1L } ?: -1L
    } catch (_: Exception) { -1L }

    override fun onDestroy() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}