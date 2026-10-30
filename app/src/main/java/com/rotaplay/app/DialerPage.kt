package com.rotaplay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#")
)

/** Uygulama içi tuş takımı: numarayı burada yaz, ara. */
@Composable
fun DialerPage() {
    val ctx = LocalContext.current
    var number by rememberSaveable { mutableStateOf("") }

    fun call() {
        open(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && number.isNotBlank()) call()
    }

    fun placeCall() {
        if (number.isBlank()) return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) call() else permLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Sol: numara ve arama düğmeleri
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(CardColor)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (number.isEmpty()) "Numara gir" else number,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (number.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(CardColor)
                        .clickable { if (number.isNotEmpty()) number = number.dropLast(1) },
                    contentAlignment = Alignment.Center
                ) { Text("⌫", fontSize = 28.sp) }
                Box(
                    Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF22C55E))
                        .clickable { placeCall() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Phone, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Text("  Ara", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        // Sağ: tuş takımı
        Column(
            Modifier.weight(1.1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KEYS.forEach { row ->
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { k ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(CardColor)
                                .clickable { if (number.length < 20) number += k },
                            contentAlignment = Alignment.Center
                        ) { Text(k, fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
