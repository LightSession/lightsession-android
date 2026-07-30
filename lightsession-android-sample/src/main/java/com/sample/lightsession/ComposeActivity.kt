package com.sample.lightsession

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/**
 * A Compose screen that is tracked without asking to be.
 *
 * Nothing here calls the SDK. `ScreenMapperIntegration` picks the Activity up from
 * `onActivityResumed` and, seeing no NavController, records it as one screen named
 * `ComposeActivity`. That is the case worth showing: an app whose navigation is one Activity per
 * screen needs no integration code at all.
 *
 * When several Compose screens live inside one Activity, the Activity name is no longer the screen
 * — see [ComposeWithNavigationActivity], which wraps its NavController to report each destination.
 */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeScreen(onBackPressed = { finish() })
        }
    }
}

@Composable
fun ComposeScreen(onBackPressed: () -> Unit) {
    var targetColor by remember { mutableStateOf(generateRandomColor()) }

    // Tweened rather than stepped, so the recorder has to deal with a screen that is mid-change
    // when it samples — which is the interesting case for a replay.
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "color animation"
    )

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            targetColor = generateRandomColor()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🚀 Jetpack Compose!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Esta tela foi criada com Jetpack Compose",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(48.dp))

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(
                            color = animatedColor,
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Compose\nAnimation",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = onBackPressed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Voltar para XML View",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // On to the NavHost variant, which closes the flow: XML views, then a Compose
                // Activity, then several Compose screens inside one. `ComposeWithNavigationActivity`
                // had nothing that opened it, so the case it demonstrates was never reached — and
                // the `context` this uses was sitting unused for the navigation nobody wired.
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, ComposeWithNavigationActivity::class.java)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Compose com NavHost",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "💡 Navegação entre tecnologias diferentes!",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun generateRandomColor(): Color {
    return Color(
        red = Random.nextInt(256),
        green = Random.nextInt(256),
        blue = Random.nextInt(256)
    )
}
