package com.sample.lightsession

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
 * ComposeActivity - Exemplo de Activity usando Jetpack Compose
 *
 * Esta Activity é automaticamente trackeada pelo LightSession através do
 * ScreenMapperIntegration.handleActivityNavigation(), que é chamado no
 * lifecycle callback onActivityResumed.
 *
 * O tracking acontece automaticamente porque:
 * 1. Esta Activity não usa NavController (navegação simples por Activity)
 * 2. O ScreenMapperIntegration detecta isso e trata como ScreenType.ACTIVITY
 * 3. O nome da tela será "ComposeActivity"
 *
 * Para navegação Compose com múltiplas telas, use NavHostController.withNavigationTracking()
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
    // Estado para controlar a cor animada
    var targetColor by remember { mutableStateOf(generateRandomColor()) }

    // Animação de transição de cor
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "color animation"
    )

    // Efeito para mudar a cor periodicamente
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            targetColor = generateRandomColor()
        }
    }

    // Context para navegação
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

                // Box animada com mudança de cor
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

                // Botão de voltar com estilo Material3
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

/*
 * ============================================================================
 * EXEMPLO: Como usar withNavigationTracking para navegação Compose
 * ============================================================================
 *
 * Se você quiser adicionar navegação entre múltiplas telas Compose, use:
 *
 * import androidx.navigation.compose.NavHost
 * import androidx.navigation.compose.composable
 * import androidx.navigation.compose.rememberNavController
 * import com.lightsession.mapper.withNavigationTracking
 *
 * @Composable
 * fun ComposeNavigationExample() {
 *     val navController = rememberNavController().withNavigationTracking()
 *
 *     NavHost(navController = navController, startDestination = "home") {
 *         composable("home") {
 *             HomeScreen(onNavigate = { navController.navigate("details") })
 *         }
 *         composable("details") {
 *             DetailsScreen(onBack = { navController.popBackStack() })
 *         }
 *     }
 * }
 *
 * O .withNavigationTracking() garante que todas as navegações entre telas
 * Compose sejam automaticamente trackeadas pelo LightSession.
 * ============================================================================
 */

