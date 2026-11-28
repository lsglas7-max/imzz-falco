package id.imzyy.xl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** IMZYY monochrome palette: black / white only. */
object Ink {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val Raised = Color(0xFF141414)
    val Line = Color(0xFF262626)
    val Text = Color(0xFFF5F5F5)
    val Sub = Color(0xFF8C8C8C)
    val White = Color(0xFFFFFFFF)
}

@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "press",
    )
    Box(
        modifier = modifier
            .scale(s)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        content = content,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x0DFFFFFF))))
            .drawWithCache {
                val gloss = Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x00FFFFFF)), startY = 0f, endY = size.height * 0.42f)
                onDrawBehind { drawRect(gloss, size = Size(size.width, size.height * 0.42f)) }
            }
            .border(1.dp, Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x14FFFFFF), Color(0x40FFFFFF))), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Pressable(onClick = onClick, modifier = modifier, enabled = enabled) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    if (filled) Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD4D4D4)))
                    else Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x0DFFFFFF))),
                )
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(listOf(Color(if (filled) 0x66FFFFFF else 0x24FFFFFF), Color(0x00FFFFFF)), startY = 0f, endY = size.height * 0.5f),
                        size = Size(size.width, size.height * 0.5f),
                    )
                }
                .border(1.dp, Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x1FFFFFFF), Color(0x66FFFFFF))), shape)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (filled) Ink.Black else Ink.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ink.White else Color(0x1AFFFFFF), tween(ImzyyFramePacing.durationMs(220)), label = "chipbg")
    val fg by animateColorAsState(if (selected) Ink.Black else Ink.Sub, tween(ImzyyFramePacing.durationMs(220)), label = "chipfg")
    val shape = RoundedCornerShape(50)
    Pressable(onClick = onClick) {
        Box(
            Modifier
                .clip(shape)
                .background(bg)
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)), startY = 0f, endY = size.height * 0.55f),
                        size = Size(size.width, size.height * 0.55f),
                    )
                }
                .border(1.dp, Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x14FFFFFF), Color(0x4DFFFFFF))), shape)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 8.dp),
        ) {
            Text(text, color = fg, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun Reveal(index: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 4) * 40L)
        a.animateTo(1f, tween(ImzyyFramePacing.durationMs(320), easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = a.value
            translationY = (1f - a.value) * 22.dp.toPx()
        },
    ) { content() }
}

@Composable
fun InkProgress(fraction: Float) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(ImzyyFramePacing.durationMs(250)), label = "progress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Ink.Line),
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Ink.White))
    }
}

@Composable
fun InkField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.Raised)
            .border(1.dp, Ink.Line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Ink.Sub, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink.Text, fontSize = 14.sp, fontFamily = ImzyyFonts.family),
            cursorBrush = SolidColor(Ink.White),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun InkPasswordField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.Raised)
            .border(1.dp, Ink.Line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Ink.Sub, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = TextStyle(color = Ink.Text, fontSize = 14.sp, fontFamily = ImzyyFonts.family),
            cursorBrush = SolidColor(Ink.White),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Slow drifting grid + a soft scan band. Pure Canvas, no bitmaps, very cheap. */
@Composable
fun AnimatedBackdrop(modifier: Modifier = Modifier) {
    // Static, cached backdrop: drawn once per size instead of every frame (keeps the hub smooth on mid-range phones).
    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val step = 56.dp.toPx()
                val glow = Brush.radialGradient(
                    colors = listOf(Color(0x1FFFFFFF), Color.Transparent),
                    center = Offset(size.width * 0.25f, size.height * 0.12f),
                    radius = size.width * 0.9f,
                )
                onDrawBehind {
                    var x = -step
                    while (x < size.width + step) {
                        drawLine(Color(0x10FFFFFF), Offset(x, 0f), Offset(x - size.height * 0.25f, size.height), 1f)
                        x += step
                    }
                    drawRect(glow)
                    for (i in 0 until 12) {
                        val px = ((i * 9301f + 49297f) % 233280f) / 233280f
                        val py = ((i * 4973f + 1217f) % 233280f) / 233280f
                        drawCircle(Color(0x2EFFFFFF), (1f + (i % 3)) * density, Offset(px * size.width, py * size.height))
                    }
                }
            },
    )
}

@Composable
fun InkSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    var w by remember { mutableStateOf(1f) }
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    fun at(x: Float) = min + (max - min) * (x / w).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(min, max) { detectTapGestures { off -> onChange(at(off.x)) } }
            .pointerInput(min, max) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onChange(at(change.position.x))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Line))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.White))
        Box(
            Modifier
                .offset { IntOffset((frac * w).toInt() - 10.dp.roundToPx(), 0) }
                .size(20.dp)
                .clip(CircleShape)
                .background(Ink.White),
        )
    }
}

@Composable
fun ShimmerTitle(text: String, sizeSp: Int = 36) {
    // One crisp sweep on entry, then a solid white title (no endless animation).
    var done by remember { mutableStateOf(false) }
    val x = remember { Animatable(-300f) }
    LaunchedEffect(Unit) {
        delay(200)
        x.animateTo(1000f, tween(ImzyyFramePacing.durationMs(1000), easing = FastOutSlowInEasing))
        done = true
    }
    val base = TextStyle(fontSize = sizeSp.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
    if (done) {
        Text(text, style = base.copy(color = Ink.White))
    } else {
        val brush = Brush.linearGradient(
            colors = listOf(Color(0xFFBDBDBD), Color(0xFFFFFFFF), Color(0xFFBDBDBD)),
            start = Offset(x.value, 0f),
            end = Offset(x.value + 360f, 0f),
        )
        Text(text, style = base.copy(brush = brush))
    }
}

@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(onClick = onClick, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.16f),
                        topLeft = Offset(-5f, -5f),
                        size = Size(size.width + 10f, size.height + 10f),
                        cornerRadius = CornerRadius(21.dp.toPx(), 21.dp.toPx()),
                    )
                }
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD9D9D9))))
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = Ink.Black, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = 3.sp)
        }
    }
}
