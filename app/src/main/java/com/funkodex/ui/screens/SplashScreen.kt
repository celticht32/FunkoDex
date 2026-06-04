package com.funkodex.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.R

private val SplashNavy  = Color(0xFF0B1929)
private val SplashBrass = Color(0xFF8B6914)
private val SplashSteel = Color(0xFF4A8FD4)
private val SplashCream = Color(0xFFD4B896)

@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val isReady by viewModel.isReady.collectAsState()

    LaunchedEffect(isReady) {
        if (isReady) onSplashComplete()
    }

    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = EaseOut),
        label         = "splash_alpha"
    )
    val scale by animateFloatAsState(
        targetValue   = if (visible) 1f else 0.85f,
        animationSpec = tween(durationMillis = 700, easing = EaseOutBack),
        label         = "splash_scale"
    )

    // Spinning ring behind the logo
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val arcAngle by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "arc_angle"
    )

    LaunchedEffect(Unit) { visible = true }

    Box(
        modifier         = Modifier.fillMaxSize().background(SplashNavy),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier            = Modifier
                .padding(32.dp)
                .alpha(alpha)
                .scale(scale)
        ) {

            // Logo: spinning brass ring + Celtic heart SVG
            Box(
                modifier         = Modifier.size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                // Spinning ring drawn on Canvas
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx     = size.width / 2f
                    val cy     = size.height / 2f
                    val radius = size.minDimension / 2f - 4.dp.toPx()

                    // Static outer ring
                    drawCircle(
                        color  = SplashBrass.copy(alpha = 0.25f),
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style  = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                    // Spinning steel arc
                    drawArc(
                        color      = SplashSteel.copy(alpha = 0.8f),
                        startAngle = arcAngle,
                        sweepAngle = 80f,
                        useCenter  = false,
                        style      = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.dp.toPx(),
                            cap   = androidx.compose.ui.graphics.StrokeCap.Round
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(cx - radius, cy - radius),
                        size    = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    )
                    // Brass accent arc
                    drawArc(
                        color      = SplashBrass,
                        startAngle = arcAngle + 200f,
                        sweepAngle = 40f,
                        useCenter  = false,
                        style      = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.dp.toPx(),
                            cap   = androidx.compose.ui.graphics.StrokeCap.Round
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(cx - radius, cy - radius),
                        size    = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    )
                }

                // Celtic heart SVG centred in the ring
                Image(
                    painter         = painterResource(id = R.drawable.ic_celtic_heart),
                    contentDescription = "Celtic Heart",
                    colorFilter     = ColorFilter.tint(SplashBrass),
                    modifier        = Modifier.size(110.dp),
                )
            }

            Text(
                text       = "FunkoDex",
                fontSize   = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color      = SplashCream,
                textAlign  = TextAlign.Center,
            )
            Text(
                text          = "COLLECTOR  ·  EDITION",
                fontSize      = 10.sp,
                color         = SplashSteel,
                textAlign     = TextAlign.Center,
                letterSpacing = 3.sp,
            )
        }
    }
}
