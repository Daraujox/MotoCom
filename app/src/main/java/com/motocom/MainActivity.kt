package com.motocom

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF8A00))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF111111)) { App() }
            }
        }
    }
}

private fun perms(): Array<String> {
    val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 31) l += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    else l += Manifest.permission.ACCESS_FINE_LOCATION
    if (Build.VERSION.SDK_INT >= 33) l += listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
    return l.toTypedArray()
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val running by VoiceService.running.collectAsState()
    val peers by VoiceService.peers.collectAsState()
    val muted by VoiceService.muted.collectAsState()
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }

    fun start() = ctx.startForegroundService(Intent(ctx, VoiceService::class.java).putExtra("room", room).putExtra("name", name))
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) start()
    }

    Column(Modifier.fillMaxSize().padding(24.dp).systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MotoCom", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        if (!running) {
            OutlinedTextField(name, { name = it }, label = { Text("Tu nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(room, { room = it }, label = { Text("Código de sala (igual para todos)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { launcher.launch(perms()) }, enabled = room.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(20.dp)
            ) { Text("ENTRAR A LA SALA", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        } else {
            Text("Sala: $room", fontSize = 18.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Text(if (peers.isEmpty()) "Buscando amigos cerca…" else "Conectados: ${peers.joinToString()}", fontSize = 16.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(200.dp).background(if (muted) Color(0xFF8B1A1A) else Color(0xFF1E8E3E), CircleShape)
                    .clickable { VoiceService.muted.value = !muted },
                contentAlignment = Alignment.Center
            ) { Text(if (muted) "MICRO\nOFF" else "MICRO\nON", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { ctx.stopService(Intent(ctx, VoiceService::class.java)) },
                modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)
            ) { Text("SALIR", fontSize = 18.sp) }
        }
    }
}
