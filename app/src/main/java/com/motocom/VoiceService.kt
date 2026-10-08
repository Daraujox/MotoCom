package com.motocom

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class VoiceService : Service() {
    companion object {
        val peers = MutableStateFlow<List<String>>(emptyList())
        val muted = MutableStateFlow(false)
        val running = MutableStateFlow(false)
        private const val SID = "com.motocom.voice"
        private const val RATE = 16000
    }

    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val outs = ConcurrentHashMap<String, OutputStream>()
    private val names = ConcurrentHashMap<String, String>()
    private val uid = UUID.randomUUID().toString().take(6)
    private var room = ""
    private var me = ""
    private var tag = ""
    @Volatile private var capturing = false
    @Volatile private var alive = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        room = i?.getStringExtra("room")?.trim()?.lowercase().orEmpty()
        me = i?.getStringExtra("name")?.replace("|", "").orEmpty().ifBlank { "Piloto" }
        tag = "$room|$me|$uid"
        startForegroundCompat()
        routeAudio()
        alive = true; running.value = true
        client.startAdvertising(tag, SID, connCb, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
        client.startDiscovery(SID, discCb, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("call", "Llamada", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "call").setContentTitle("MotoCom activo").setContentText("Sala: $room")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).addAction(Notification.Action.Builder(null, "Salir", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
    }

    private fun routeAudio() {
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?.let { am.setCommunicationDevice(it) }
        }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val p = info.endpointName.split("|")
            // Misma sala, y solo el de menor uid inicia la conexión (evita duplicados)
            if (p.size == 3 && p[0] == room && uid < p[2]) client.requestConnection(tag, id, connCb)
        }
        override fun onEndpointLost(id: String) {}
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            val p = info.endpointName.split("|")
            if (p.size == 3 && p[0] == room) { names[id] = p[1]; client.acceptConnection(id, payloadCb) }
            else client.rejectConnection(id)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (!r.status.isSuccess) { names.remove(id); return }
            val pipe = ParcelFileDescriptor.createPipe()
            client.sendPayload(id, Payload.fromStream(pipe[0]))
            outs[id] = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            publish(); startCapture()
        }
        override fun onDisconnected(id: String) { outs.remove(id)?.close(); names.remove(id); publish() }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            if (p.type == Payload.Type.STREAM) p.asStream()?.asInputStream()?.let { s -> thread { play(s) } }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private fun publish() { peers.value = outs.keys.mapNotNull { names[it] } }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) } catch (e: SecurityException) { capturing = false; return@thread }
            val buf = ByteArray(1280)
            rec.startRecording()
            while (alive && outs.isNotEmpty()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0 || muted.value) continue
                outs.forEach { (id, o) -> try { o.write(buf, 0, n); o.flush() } catch (e: IOException) { outs.remove(id) } }
            }
            rec.stop(); rec.release(); capturing = false
        }
    }

    private fun play(s: InputStream) {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        val buf = ByteArray(1280)
        try { while (alive) { val n = s.read(buf); if (n < 0) break; track.write(buf, 0, n) } } catch (_: IOException) {}
        track.release()
    }

    override fun onDestroy() {
        alive = false; running.value = false; peers.value = emptyList()
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        outs.values.forEach { try { it.close() } catch (_: IOException) {} }; outs.clear()
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }
}
