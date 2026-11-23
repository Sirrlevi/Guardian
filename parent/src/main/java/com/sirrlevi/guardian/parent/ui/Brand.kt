package com.sirrlevi.guardian.parent.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable as AnimatedValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sirrlevi.guardian.parent.R
import kotlinx.coroutines.delay

/**
 * The Parent app's animated logo (the loop lives in res/raw/guardian_logo_anim.webp).
 *
 * Android's launcher can only show a static icon, so the motion plays inside the app: the intro, the sign-in
 * screen and the top bar. Falls back to a still picture on Android 8-8.1 (no animated image decoder), when the
 * person turned animations off in system settings, or if decoding fails for any reason.
 */
@Composable
fun GuardianLogo(size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable: Drawable? = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !animationsTurnedOff(context)) decodeLogo(context) else null
    }
    Box(modifier.size(size).clip(CircleShape).semantics { contentDescription = "Guardian logo" }) {
        if (drawable is Animatable) {
            AnimatedLogoView(drawable, Modifier.fillMaxSize())
        } else {
            Image(
                painter = painterResource(R.drawable.guardian_logo_static),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun animationsTurnedOff(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Throwable) {
    false
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeLogo(context: Context): Drawable? = try {
    val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, R.raw.guardian_logo_anim))
    if (decoded is AnimatedImageDrawable) decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    decoded
} catch (_: Throwable) {
    null
}

@Composable
private fun AnimatedLogoView(drawable: Drawable, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, drawable) {
        val animatable = drawable as Animatable
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!animatable.isRunning) animatable.start()
                Lifecycle.Event.ON_STOP -> animatable.stop() // no battery use while the app is in the background
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            animatable.stop()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(drawable)
                (drawable as Animatable).start()
            }
        }
    )
}

/**
 * Short branded intro shown once per cold start from the launcher: the animated logo on the brand background.
 * Tapping anywhere skips it.
 */
@Composable
fun BrandIntro(onFinished: () -> Unit, durationMillis: Long = 2300L) {
    val finish by rememberUpdatedState(onFinished)
    val pop = remember { AnimatedValue(0.86f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) {
        delay(durationMillis)
        finish()
    }
    Box(
        Modifier.fillMaxSize()
            .background(colorResource(R.color.ic_launcher_background))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(260.dp)
                        .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                        .background(Brush.radialGradient(listOf(Color(0x66B63CFF), Color.Transparent)), CircleShape)
                )
                GuardianLogo(
                    176.dp,
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }
                        .border(2.dp, Color(0x66E6B8FF), CircleShape)
                )
            }
            Spacer(Modifier.height(26.dp))
            Text("Guardian", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("PARENT", color = Color(0xFFC9A0FF), style = MaterialTheme.typography.labelLarge, letterSpacing = 4.sp)
        }
    }
}
