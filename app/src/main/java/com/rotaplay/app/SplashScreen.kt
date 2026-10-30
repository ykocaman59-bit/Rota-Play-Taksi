package com.rotaplay.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Bg = Color(0xFF0B1220)
val CardColor = Color(0xFF141C2E)
val Cyan = Color(0xFF22D3EE)
val Blue = Color(0xFF3B82F6)

/**
 * Açılış animasyonu:
 * 1) Rota halkası çizilir  2) Navigasyon oku yaylanarak belirir  3) İsim + slogan gelir
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val ring = remember { Animatable(0f) }
    val arrow = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        ring.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        arrow.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
        text.animateTo(1f, tween(600))
        delay(700)
        onFinished()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val c = center
            val r = size.minDimension / 2f * 0.9f
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(r * 2, r * 2)

            // Soluk zemin halkası
            drawArc(
                color = Cyan.copy(alpha = 0.15f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = 12f)
            )
            // Çizilen ana halka
            drawArc(
                brush = Brush.sweepGradient(listOf(Cyan, Blue, Cyan), center = c),
                startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = 12f, cap = StrokeCap.Round)
            )
            // Navigasyon oku
            val s = r * 0.55f * arrow.value
            val p = Path().apply {
                moveTo(c.x, c.y - s)
                lineTo(c.x + s * 0.8f, c.y + s)
                lineTo(c.x, c.y + s * 0.45f)
                lineTo(c.x - s * 0.8f, c.y + s)
                close()
            }
            drawPath(
                p,
                brush = Brush.linearGradient(
                    listOf(Cyan, Blue),
                    start = Offset(c.x, c.y - s),
                    end = Offset(c.x, c.y + s)
                )
            )
        }

        Spacer(Modifier.height(24.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = text.value
                translationY = (1f - text.value) * 24f
            }
        ) {
            Text(
                "RotaPlay",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                "Yolun ritmi seninle",
                color = Cyan,
                fontSize = 16.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
