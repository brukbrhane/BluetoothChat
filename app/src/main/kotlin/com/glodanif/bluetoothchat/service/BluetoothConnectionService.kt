package com.glodanif.bluetoothchat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.glodanif.bluetoothchat.R
import com.glodanif.bluetoothchat.Storage
import com.glodanif.bluetoothchat.activity.ConversationsActivity
import com.glodanif.bluetoothchat.database.ChatDatabase
import com.glodanif.bluetoothchat.entity.ChatMessage
import com.glodanif.bluetoothchat.entity.Conversation
import com.glodanif.bluetoothchat.model.OnConnectionListener
import com.glodanif.bluetoothchat.model.OnMessageListener
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.thread

class BluetoothConnectionService : Service() {

    private val binder = ConnectionBinder()

    private val TAG = "TAG13"

    enum class ConnectionState { CONNECTED, CONNECTING, NOT_CONNECTED, LISTENING }
    enum class ConnectionType { INCOMING, OUTCOMING }

    private var connectionListener: OnConnectionListener? = null
    private var messageListener: OnMessageListener? = null

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val APP_NAME = "BluetoothChat"
    private val APP_UUID = UUID.fromString("220da3b2-41f5-11e7-a919-92ebcb67fe33")

    private val handler: Handler = Handler()
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    private var connectionState: ConnectionState = ConnectionState.NOT_CONNECTED

    var isBound = false

    private var currentSocket: BluetoothSocket? = null

    private val db: ChatDatabase = Storage.getInstance(this).db

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    inner class ConnectionBinder : Binder() {

        fun getService(): BluetoothConnectionService {
            return this@BluetoothConnectionService
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "CREATED")
        isRunning = true
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        prepareForAccept()
        showNotification("Ready to connect")
        return Service.START_STICKY
    }

    private fun showNotification(message: String) {

        val notificationIntent = Intent(this, ConversationsActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val stopIntent = Intent(this, BluetoothConnectionService::class.java)
        stopIntent.action = ACTION_STOP
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, 0)

        val icon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val notification = Notification.Builder(this)
                .setContentTitle("Bluetooth Chat")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(0, "STOP", stopPendingIntent)
                .build()

        startForeground(FOREGROUND_SERVICE, notification)
    }

    @Synchronized fun prepareForAccept() {

        Log.d(TAG, "start")

        cancelConnections()

        if (acceptThread == null) {
            acceptThread = AcceptThread()
            acceptThread!!.start()
        }
    }

    @Synchronized fun connect(device: BluetoothDevice) {

        Log.d(TAG, "connect to: " + device)

        if (connectionState == ConnectionState.CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        connectedThread?.cancel()
        connectedThread = null

        connectThread = ConnectThread(device)
        connectThread!!.start()
        handler.post { connectionListener?.onConnecting() }
    }

    @Synchronized fun connected(socket: BluetoothSocket, device: BluetoothDevice, type: ConnectionType) {

        Log.d(TAG, "connected")

        currentSocket = socket

        cancelConnections()

        acceptThread?.cancel()
        acceptThread = null

        connectedThread = ConnectedThread(socket)
        connectedThread!!.start()

        handler.post {
            if (type == ConnectionType.INCOMING) {
                connectionListener?.onConnectedIn(device)
            } else {
                connectionListener?.onConnectedOut(device)
            }
        }

        val conversation = Conversation(device.address, device.name)
        thread { db.conversationsDao().insert(conversation) }
    }

    @Synchronized fun stop() {

        Log.d(TAG, "stop")

        cancelConnections()

        acceptThread?.cancel()
        acceptThread = null

        connectionState = ConnectionState.NOT_CONNECTED
        handler.post { connectionListener?.onDisconnected() }
    }

    private fun cancelConnections() {
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
    }

    fun sendMessage(message: String) {

        if (connectionState == ConnectionState.CONNECTED) {
            connectedThread?.write(message)
        }
    }

    private fun onMessageSent(message: String) {

        if (currentSocket == null) return

        val sentMessage: ChatMessage = ChatMessage(
                currentSocket!!.remoteDevice.address, Date(), true, message, false)

        thread { db.messagesDao().insert(sentMessage) }
        handler.post { messageListener?.onMessageSent(sentMessage) }
    }

    private fun onMessageReceived(message: String) {

        if (currentSocket == null) return

        val receivedMessage: ChatMessage = ChatMessage(
                currentSocket!!.remoteDevice.address, Date(), false, message, false)

        thread { db.messagesDao().insert(receivedMessage) }
        handler.post { messageListener?.onMessageReceived(receivedMessage) }
    }

    private fun connectionFailed() {
        handler.post { connectionListener?.onConnectionFailed() }
        connectionState = ConnectionState.NOT_CONNECTED
        prepareForAccept()
    }

    private fun connectionLost() {
        handler.post { connectionListener?.onConnectionLost() }
        connectionState = ConnectionState.NOT_CONNECTED
        prepareForAccept()
    }

    fun isConnected(): Boolean {
        return connectionState == ConnectionState.CONNECTED
    }

    fun setConnectionListener(listener: OnConnectionListener) {
        this.connectionListener = listener
    }

    fun setMessageListener(listener: OnMessageListener) {
        this.messageListener = listener
    }

    private inner class AcceptThread : Thread() {

        private var serverSocket: BluetoothServerSocket? = null

        init {
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "Socket listen() failed", e)
                e.printStackTrace()
            }

            connectionState = ConnectionState.LISTENING
        }

        override fun run() {

            Log.d(TAG, "BEGIN acceptThread" + this)

            var socket: BluetoothSocket?

            while (connectionState != ConnectionState.CONNECTED) {
                try {
                    socket = serverSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket accept() failed")
                    break
                }

                if (socket != null) {
                    when (connectionState) {
                        ConnectionState.LISTENING, ConnectionState.CONNECTING -> {
                            Log.e(TAG, "AcceptThread")
                            connected(socket, socket.remoteDevice, ConnectionType.INCOMING)
                        }
                        ConnectionState.NOT_CONNECTED, ConnectionState.CONNECTED -> try {
                            socket.close()
                        } catch (e: IOException) {
                            Log.e(TAG, "Could not close unwanted socket", e)
                        }
                    }
                }
            }

            Log.i(TAG, "END acceptThread")
        }

        fun cancel() {
            Log.d(TAG, "Socket cancel " + this)
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Socket close() of server failed", e)
                e.printStackTrace()
            }
        }
    }

    private inner class ConnectThread(bluetoothDevice: BluetoothDevice) : Thread() {

        private var socket: BluetoothSocket? = null
        private val device = bluetoothDevice

        init {
            try {
                socket = device.createRfcommSocketToServiceRecord(APP_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "Socket create() failed", e)
                e.printStackTrace()
            }
            connectionState = ConnectionState.CONNECTING
        }

        override fun run() {

            Log.i(TAG, "BEGIN connectThread")

            try {
                socket?.connect()
            } catch (connectException: IOException) {
                connectException.printStackTrace()
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                    Log.e(TAG, "unable to close() socket during connection failure", closeException)
                }
                connectionFailed()
                return
            }

            synchronized(this@BluetoothConnectionService) {
                connectThread = null
            }

            if (socket != null) {
                Log.e(TAG, "ConnectThread")
                connected(socket!!, device, ConnectionType.OUTCOMING)
            }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {

        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null

        init {
            Log.d(TAG, "create ConnectedThread, connected:${socket.isConnected}")

            try {
                inputStream = socket.inputStream
                outputStream = socket.outputStream
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "sockets not created", e)
            }

            showNotification("Connected to ${socket.remoteDevice.name}")
            connectionState = ConnectionState.CONNECTED
        }

        override fun run() {
            Log.i(TAG, "BEGIN connectedThread")
            val buffer = ByteArray(1024)
            var bytes: Int?

            while (connectionState == ConnectionState.CONNECTED) {
                try {
                    bytes = inputStream?.read(buffer)

                    if (bytes != null) {
                        handler.post { onMessageReceived(String(buffer, 0, bytes!!)) }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "disconnected", e)
                    connectionLost()
                    break
                }
            }
        }

        fun write(message: String) {
            try {
                outputStream?.write(message.toByteArray(Charsets.UTF_8))
                onMessageSent(message)
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }
        }

        fun cancel() {
            try {
                socket.close()
                currentSocket = null
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.e(TAG, "DESTROYED")
    }

    companion object {

        var isRunning = false

        var FOREGROUND_SERVICE = 101
        var ACTION_STOP = "action.stop"

        fun start(context: Context) {
            val intent = Intent(context, BluetoothConnectionService::class.java)
            context.startService(intent)
        }

        fun bind(context: Context, connection: ServiceConnection) {
            val intent = Intent(context, BluetoothConnectionService::class.java)
            context.bindService(intent, connection, AppCompatActivity.BIND_ABOVE_CLIENT)
        }
    }
}