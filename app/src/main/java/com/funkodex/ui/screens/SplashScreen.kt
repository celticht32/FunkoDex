package com.funkodex.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate

// App colour palette
private val Navy    = Color(0xFF0D1B2A)
private val Sky     = Color(0xFF5DADE2)
private val Brass   = Color(0xFFB8943F)
private val OffWhite= Color(0xFFF0F4F8)
private val DimBlue = Color(0xFF3A5A7A)

// SVG source dimensions (celticht.svg viewBox = "0 0 116.99583 108.79025"
// with the path body translated by (-46.302082, -94.191666))
private const val SVG_W = 116.99583f
private const val SVG_H = 108.79025f
private const val SVG_TX = -46.302082f   // Inkscape layer translate X
private const val SVG_TY = -94.191666f   // Inkscape layer translate Y

@Composable
fun SplashScreen(onSplashComplete: () -> Unit) {
    var showLogo  by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var showSub   by remember { mutableStateOf(false) }
    var showDots  by remember { mutableStateOf(false) }
    var startExit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100); showLogo  = true
        delay(500); showTitle = true
        delay(300); showSub   = true
        delay(200); showDots  = true
        delay(1_500); startExit = true
        delay(400); onSplashComplete()
    }

    val logoAlpha by animateFloatAsState(
        targetValue   = if (showLogo) 1f else 0f,
        animationSpec = tween(600),
        label = "logoAlpha"
    )
    val logoScale by animateFloatAsState(
        targetValue   = if (showLogo) 1f else 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "logoScale"
    )
    val screenAlpha by animateFloatAsState(
        targetValue   = if (startExit) 0f else 1f,
        animationSpec = tween(400),
        label = "screenAlpha"
    )

    // Full-screen navy box — everything absolutely centered
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(Navy)
            .graphicsLayer(alpha = screenAlpha),
        contentAlignment = Alignment.Center   // ← centers the column in the screen
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()           // column only as tall as its content
                .padding(horizontal = 40.dp)
        ) {

            // ── Celtic heart knot ─────────────────────────────────────────
            // Canvas is given an EXPLICIT fixed size so it never collapses.
            // Aspect ratio of SVG: 116.99583 / 108.79025 ≈ 1.0754
            // At 160dp wide → height = 160 / 1.0754 ≈ 149dp
            Box(
                modifier         = Modifier
                    .graphicsLayer(alpha = logoAlpha, scaleX = logoScale, scaleY = logoScale),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .width(160.dp)
                        .height(149.dp)
                ) {
                    // Scale the entire SVG coordinate space to fit the canvas,
                    // maintaining aspect ratio, and center it.
                    val scaleX = size.width  / SVG_W
                    val scaleY = size.height / SVG_H
                    val s      = minOf(scaleX, scaleY)
                    val offsetX = (size.width  - SVG_W * s) / 2f
                    val offsetY = (size.height - SVG_H * s) / 2f

                    val paint = Paint().apply {
                        color       = Sky
                        style       = PaintingStyle.Stroke
                        strokeWidth = 0.75f / s   // keep visual stroke width constant
                        strokeCap   = StrokeCap.Round
                        strokeJoin  = StrokeJoin.Round
                        isAntiAlias = true
                    }

                    drawContext.canvas.save()
                    // 1. Center the scaled drawing in the canvas
                    drawContext.canvas.translate(offsetX, offsetY)
                    // 2. Scale from SVG units → dp pixels
                    drawContext.canvas.scale(s, s)
                    // 3. Apply the Inkscape layer translation so path coords land at (0,0)
                    drawContext.canvas.translate(SVG_TX, SVG_TY)

                    val path = Path()
                    buildCelticHeartPath(path)
                    drawContext.canvas.drawPath(path, paint)
                    drawContext.canvas.restore()
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Title ─────────────────────────────────────────────────────
            // Replace FontFamily.Serif with cinzelDecorativeFamily() once font file is added
            AnimatedVisibility(visible = showTitle,
                enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 4 }) {
                Text(
                    text          = "FunkoDex",
                    fontFamily    = FontFamily.Serif,
                    fontSize      = 36.sp,
                    fontWeight    = FontWeight.Bold,
                    color         = OffWhite,
                    letterSpacing = 2.sp,
                    textAlign     = TextAlign.Center,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Brass rule ────────────────────────────────────────────────
            AnimatedVisibility(visible = showTitle, enter = fadeIn(tween(600))) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.width(56.dp).height(1.dp).background(Brass))
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(4.dp).background(Brass, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(4.dp).background(Brass, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.width(56.dp).height(1.dp).background(Brass))
                }
            }

            Spacer(Modifier.height(9.dp))

            AnimatedVisibility(visible = showSub, enter = fadeIn(tween(500))) {
                Text(
                    text          = "COLLECTOR · EDITION",
                    fontSize      = 10.sp,
                    color         = Sky,
                    letterSpacing = 4.sp,
                    textAlign     = TextAlign.Center,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(5.dp))

            AnimatedVisibility(visible = showSub, enter = fadeIn(tween(620))) {
                Text(
                    text          = "Celtic Heart Steamworks",
                    fontSize      = 10.sp,
                    color         = Brass,
                    letterSpacing = 1.sp,
                    textAlign     = TextAlign.Center,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(3.dp))

            AnimatedVisibility(visible = showSub, enter = fadeIn(tween(750))) {
                Text(
                    text          = "© ${LocalDate.now().year} All rights reserved.",
                    fontSize      = 8.sp,
                    color         = DimBlue,
                    letterSpacing = 0.5.sp,
                    textAlign     = TextAlign.Center,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(36.dp))

            AnimatedVisibility(
                visible = showDots && !startExit,
                enter   = fadeIn(tween(400))
            ) {
                LoadingDots()
            }
        }
    }
}

@Composable
private fun LoadingDots() {
    val infinite = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(3) { i ->
            val a by infinite.animateFloat(
                initialValue  = 0.22f,
                targetValue   = 0.9f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 170)),
                label = "dot$i"
            )
            Box(Modifier.size(6.dp).background(Sky.copy(alpha = a), CircleShape))
        }
    }
}

/**
 * Builds the Celtic heart knot Path from celticht.svg.
 *
 * The SVG has:
 *   viewBox="0 0 116.99583 108.79025"
 *   <g transform="translate(-46.302082,-94.191666)">
 *     <path d="M 129.317,174.858 ..." />
 *
 * We apply the translate in the Canvas save/restore block (see above),
 * so this function writes coordinates exactly as they appear in the SVG source.
 */
private fun buildCelticHeartPath(p: Path) {
    p.reset()

    // ── Outer heart and triple border ──────────────────────────────────────
    p.moveTo(129.31658f, 174.85792f)
    p.cubicTo(143.64722f,160.50388f, 153.09923f,150.83516f, 153.83447f,149.77793f)
    p.cubicTo(156.32978f,146.18976f, 158.20601f,141.59817f, 158.97045f,137.20895f)
    p.cubicTo(159.59601f,133.61714f, 159.33797f,127.43715f, 158.42524f,124.1514f)
    p.cubicTo(156.65279f,117.77077f, 153.39886f,112.52892f, 148.7396f,108.54857f)
    p.cubicTo(137.9959f,99.37032f, 122.79853f,98.3310f, 111.2423f,105.9842f)
    p.cubicTo(109.67979f,107.01898f, 107.38388f,109.02734f, 104.32958f,112.03112f)
    p.lineTo(99.75639f, 116.52867f)
    p.lineTo(101.27729f,118.0656f)
    p.cubicTo(102.11378f,118.91091f, 102.91375f,119.60253f, 103.055f,119.60253f)
    p.cubicTo(103.19625f,119.60253f, 105.28447f,117.66126f, 107.69547f,115.28861f)
    p.cubicTo(110.10648f,112.91595f, 112.771f,110.52269f, 113.61661f,109.97025f)
    p.cubicTo(115.82434f,108.52795f, 119.15954f,107.02519f, 121.6082f,106.36944f)
    p.cubicTo(124.34841f,105.63561f, 129.43572f,105.37677f, 132.2515f,105.82791f)
    p.cubicTo(143.53348f,107.6355f, 151.93198f,115.74337f, 154.33413f,127.14638f)
    p.cubicTo(154.51814f,128.01988f, 154.66434f,130.28303f, 154.65903f,132.17561f)
    p.cubicTo(154.64813f,136.05484f, 154.09881f,138.85714f, 152.68226f,142.25986f)
    p.cubicTo(150.84032f,146.6844f, 150.67552f,146.86647f, 127.60802f,169.96158f)
    p.cubicTo(115.72053f,181.8633f, 105.90562f,191.57104f, 105.79711f,191.53434f)
    p.cubicTo(105.28095f,191.35979f, 63.28688f,149.16344f, 62.13344f,147.66035f)
    p.cubicTo(60.35002f,145.33633f, 58.61687f,141.69137f, 57.78237f,138.50943f)
    p.cubicTo(57.21358f,136.34096f, 57.08907f,135.25644f, 57.06948f,132.30796f)
    p.cubicTo(57.05638f,130.32858f, 57.19574f,128.0261f, 57.38191f,127.14638f)
    p.cubicTo(59.79981f,115.72709f, 68.1777f,107.6365f, 79.45561f,105.82963f)
    p.cubicTo(82.25036f,105.38187f, 87.12148f,105.62084f, 89.9772f,106.3458f)
    p.cubicTo(92.91486f,107.09156f, 97.58021f,109.42349f, 99.87243f,111.29183f)
    p.lineTo(101.69443f,112.77689f)
    p.lineTo(103.31349f,111.14309f)
    p.lineTo(104.93255f,109.50928f)
    p.lineTo(103.87377f,108.57323f)
    p.cubicTo(99.95831f,105.11166f, 94.89654f,102.61693f, 89.39383f,101.43671f)
    p.cubicTo(86.35861f,100.78572f, 80.33906f,100.79086f, 77.27177f,101.4463f)
    p.cubicTo(65.69245f,103.92354f, 56.41551f,112.59449f, 53.30644f,123.84617f)
    p.cubicTo(52.45687f,126.92075f, 52.14387f,132.58216f, 52.63197f,136.04556f)
    p.cubicTo(53.2948f,140.74784f, 55.03989f,145.4313f, 57.582f,149.33003f)
    p.cubicTo(58.52764f,150.78029f, 64.40097f,156.82153f, 82.27107f,174.7248f)
    p.cubicTo(95.16762f,187.6453f, 105.78139f,198.21661f, 105.85721f,198.21661f)
    p.cubicTo(105.93301f,198.21661f, 116.48948f,187.70485f, 129.31629f,174.85915f)
    p.close()

    // ── Second (middle) heart border ────────────────────────────────────────
    p.moveTo(125.87403f,164.27006f)
    p.lineTo(145.74223f,144.35165f)
    p.lineTo(146.95979f,141.95915f)
    p.cubicTo(149.77503f,136.42721f, 150.23747f,130.73241f, 148.32744f,125.1167f)
    p.cubicTo(146.12231f,118.63336f, 141.02265f,113.77162f, 134.1699f,111.61971f)
    p.cubicTo(132.44814f,111.07904f, 131.67215f,111.00098f, 128.09328f,111.00842f)
    p.cubicTo(124.30097f,111.01642f, 123.83024f,111.07272f, 121.87292f,111.75388f)
    p.cubicTo(119.37045f,112.62474f, 117.02348f,113.8902f, 115.11932f,115.39536f)
    p.lineTo(113.74155f,116.48443f)
    p.lineTo(115.28433f,118.04348f)
    p.cubicTo(116.13286f,118.90095f, 116.9199f,119.60253f, 117.03331f,119.60253f)
    p.cubicTo(117.14671f,119.60253f, 117.81572f,119.18856f, 118.51999f,118.68259f)
    p.cubicTo(125.29983f,113.81176f, 134.12867f,114.51366f, 140.01197f,120.39121f)
    p.cubicTo(143.47058f,123.84645f, 144.93539f,127.74157f, 144.69248f,132.83735f)
    p.cubicTo(144.545f,135.93137f, 143.89474f,138.11899f, 142.37927f,140.61951f)
    p.cubicTo(141.7985f,141.57777f, 135.59722f,147.96593f, 123.6845f,159.87768f)
    p.lineTo(105.85878f,177.70193f)
    p.lineTo(88.03306f,159.87768f)
    p.cubicTo(76.12034f,147.96593f, 69.91906f,141.57777f, 69.33829f,140.61951f)
    p.cubicTo(68.86032f,139.83086f, 68.16524f,138.38259f, 67.79367f,137.40113f)
    p.cubicTo(67.20089f,135.83537f, 67.1158f,135.19493f, 67.09942f,132.17561f)
    p.cubicTo(67.07692f,128.03136f, 67.64491f,125.92246f, 69.59193f,122.93993f)
    p.cubicTo(74.18734f,115.90047f, 83.14911f,113.49288f, 90.76958f,117.25053f)
    p.cubicTo(92.78032f,118.24202f, 93.47723f,118.81186f, 97.32781f,122.61085f)
    p.lineTo(101.63643f,126.86175f)
    p.lineTo(103.28349f,125.2065f)
    p.lineTo(104.93055f,123.55126f)
    p.lineTo(100.56306f,119.24214f)
    p.cubicTo(95.6767f,114.42109f, 94.01154f,113.26933f, 89.93565f,111.89134f)
    p.cubicTo(87.82752f,111.17862f, 86.97006f,111.03668f, 84.15188f,110.93394f)
    p.cubicTo(81.68656f,110.84424f, 80.37156f,110.9228f, 78.99142f,111.24053f)
    p.cubicTo(70.09463f,113.29725f, 63.74129f,120.17561f, 62.42532f,129.17559f)
    p.cubicTo(61.80719f,133.40301f, 62.59028f,137.69818f, 64.7558f,141.95796f)
    p.lineTo(65.97207f,144.35046f)
    p.lineTo(85.84318f,164.26887f)
    p.cubicTo(96.77229f,175.22399f, 105.77973f,184.18728f, 105.85972f,184.18728f)
    p.cubicTo(105.93971f,184.18728f, 114.94584f,175.22399f, 125.87346f,164.26887f)
    p.close()

    // ── Inner knotwork detail ───────────────────────────────────────────────
    p.moveTo(101.847f,162.473f); p.cubicTo(102.618f,162.123f, 104.517f,160.456f, 107.477f,157.528f)
    p.lineTo(111.942f,153.111f); p.lineTo(110.292f,151.446f); p.lineTo(108.642f,149.781f)
    p.lineTo(104.398f,153.994f); p.cubicTo(101.774f,156.599f, 99.874f,158.277f, 99.421f,158.390f)
    p.cubicTo(98.532f,158.612f, 97.235f,158.119f, 96.702f,157.359f)
    p.cubicTo(96.044f,156.419f, 96.214f,154.941f, 97.097f,153.936f)
    p.lineTo(97.878f,153.046f); p.lineTo(96.209f,151.393f); p.lineTo(94.54f,149.739f)
    p.lineTo(93.456f,150.971f); p.cubicTo(90.263f,154.598f, 91.310f,160.204f, 95.578f,162.331f)
    p.cubicTo(97.345f,163.211f, 100.082f,163.273f, 101.845f,162.473f); p.close()

    p.moveTo(115.538f,162.620f); p.cubicTo(119.073f,161.143f, 120.930f,157.088f, 119.737f,153.454f)
    p.cubicTo(119.322f,152.192f, 118.740f,151.494f, 114.682f,147.396f)
    p.lineTo(110.095f,142.764f); p.lineTo(108.440f,144.414f); p.lineTo(106.784f,146.065f)
    p.lineTo(111.079f,150.388f); p.cubicTo(115.178f,154.514f, 115.374f,154.759f, 115.374f,155.759f)
    p.cubicTo(115.374f,157.016f, 114.881f,157.795f, 113.801f,158.242f)
    p.cubicTo(112.805f,158.655f, 111.950f,158.451f, 110.873f,157.545f)
    p.lineTo(110.036f,156.841f); p.lineTo(108.404f,158.489f); p.lineTo(106.771f,160.136f)
    p.lineTo(107.830f,161.078f); p.cubicTo(109.422f,162.494f, 110.726f,163.000f, 112.802f,163.007f)
    p.cubicTo(113.804f,163.010f, 115.012f,162.839f, 115.538f,162.620f); p.close()

    p.moveTo(103.334f,153.216f); p.lineTo(104.909f,151.624f)
    p.lineTo(100.481f,147.195f); p.lineTo(96.053f,142.767f)
    p.lineTo(94.400f,144.420f); p.lineTo(92.748f,146.072f)
    p.lineTo(97.110f,150.440f); p.cubicTo(99.509f,152.842f, 101.536f,154.807f, 101.615f,154.807f)
    p.cubicTo(101.694f,154.807f, 102.467f,154.091f, 103.334f,153.216f); p.close()

    p.moveTo(87.467f,148.541f); p.cubicTo(88.559f,148.170f, 89.516f,147.364f, 93.345f,143.593f)
    p.lineTo(97.919f,139.087f); p.lineTo(96.266f,137.419f); p.lineTo(94.613f,135.752f)
    p.lineTo(90.369f,139.965f); p.cubicTo(87.745f,142.570f, 85.845f,144.248f, 85.392f,144.361f)
    p.cubicTo(84.504f,144.583f, 83.206f,144.090f, 82.673f,143.330f)
    p.cubicTo(81.996f,142.362f, 82.180f,140.782f, 83.079f,139.855f)
    p.lineTo(83.870f,139.038f); p.lineTo(82.192f,137.374f); p.lineTo(80.513f,135.711f)
    p.lineTo(79.435f,136.936f); p.cubicTo(77.353f,139.301f, 76.954f,142.204f, 78.332f,144.962f)
    p.cubicTo(80.040f,148.379f, 83.710f,149.817f, 87.467f,148.541f); p.close()

    p.moveTo(129.843f,148.472f); p.cubicTo(131.215f,147.849f, 132.835f,146.254f, 133.498f,144.872f)
    p.cubicTo(134.219f,143.370f, 134.327f,141.135f, 133.762f,139.415f)
    p.cubicTo(133.352f,138.166f, 132.756f,137.453f, 128.711f,133.367f)
    p.lineTo(124.124f,128.735f); p.lineTo(122.465f,130.389f); p.lineTo(120.806f,132.043f)
    p.lineTo(125.104f,136.148f); p.cubicTo(129.262f,140.311f, 129.403f,140.488f, 129.403f,141.538f)
    p.cubicTo(129.403f,144.218f, 126.837f,145.090f, 124.695f,143.138f)
    p.lineTo(124.091f,142.587f); p.lineTo(122.446f,144.248f); p.lineTo(120.800f,145.908f)
    p.lineTo(121.991f,146.948f); p.cubicTo(122.646f,147.521f, 123.659f,148.158f, 124.241f,148.366f)
    p.cubicTo(125.799f,148.920f, 128.515f,148.875f, 129.843f,148.372f); p.close()

    p.moveTo(107.862f,152.131f); p.lineTo(109.437f,150.539f)
    p.lineTo(105.009f,146.111f); p.lineTo(100.581f,141.683f)
    p.lineTo(98.928f,143.335f); p.lineTo(97.276f,144.988f)
    p.lineTo(101.637f,149.355f); p.cubicTo(104.036f,151.758f, 106.063f,153.723f, 106.142f,153.723f)
    p.cubicTo(106.221f,153.723f, 106.995f,153.007f, 107.862f,152.131f); p.close()

    p.moveTo(92.041f,147.456f); p.lineTo(93.616f,145.864f)
    p.lineTo(89.188f,141.436f); p.lineTo(84.760f,137.008f)
    p.lineTo(83.108f,138.661f); p.lineTo(81.455f,140.313f)
    p.lineTo(85.817f,144.681f); p.cubicTo(88.216f,147.083f, 90.243f,149.048f, 90.322f,149.048f)
    p.cubicTo(90.401f,149.048f, 91.175f,148.332f, 92.041f,147.456f); p.close()

    p.moveTo(121.890f,147.517f); p.lineTo(123.465f,145.925f)
    p.lineTo(119.037f,141.497f); p.lineTo(114.609f,137.069f)
    p.lineTo(112.957f,138.721f); p.lineTo(111.305f,140.374f)
    p.lineTo(115.666f,144.741f); p.cubicTo(118.065f,147.143f, 120.092f,149.109f, 120.171f,149.109f)
    p.cubicTo(120.250f,149.109f, 121.024f,148.393f, 121.890f,147.517f); p.close()

    p.moveTo(78.013f,143.339f); p.lineTo(79.584f,141.752f)
    p.lineTo(75.291f,137.431f); p.lineTo(70.999f,133.111f)
    p.lineTo(70.999f,131.895f); p.cubicTo(70.999f,130.863f, 71.122f,130.576f, 71.814f,129.994f)
    p.cubicTo(72.963f,129.027f, 74.159f,129.078f, 75.377f,130.147f)
    p.lineTo(76.332f,130.985f); p.lineTo(77.957f,129.360f); p.lineTo(79.582f,127.735f)
    p.lineTo(78.798f,126.909f); p.cubicTo(75.863f,123.819f, 70.779f,123.964f, 67.994f,127.218f)
    p.cubicTo(66.199f,129.315f, 65.744f,132.541f, 66.897f,135.000f)
    p.cubicTo(67.330f,135.923f, 75.845f,144.926f, 76.285f,144.926f)
    p.cubicTo(76.372f,144.926f, 77.149f,144.212f, 78.013f,143.339f); p.close()
}
