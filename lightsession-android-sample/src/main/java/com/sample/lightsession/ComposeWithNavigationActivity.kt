package com.sample.lightsession

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lightsession.mapper.withNavigationTracking

/**
 * Exemplo de Activity com Navegação Compose usando NavHost
 *
 * Esta é uma implementação OPCIONAL que demonstra como usar navegação
 * entre múltiplas telas Compose com tracking automático do LightSession.
 *
 * IMPORTANTE: Para usar este exemplo, você precisa adicionar a dependência
 * de navegação Compose no build.gradle.kts:
 *
 * implementation("androidx.navigation:navigation-compose:2.7.0")
 *
 * O .withNavigationTracking() é uma extensão que:
 * 1. Registra o NavController no ScreenMapperIntegration
 * 2. Trackeia automaticamente todas as navegações entre telas Compose
 * 3. Envia os dados para o LightSession via handleComposeNavigation()
 */
class ComposeWithNavigationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeNavigationApp()
        }
    }
}

@Composable
fun ComposeNavigationApp() {
    // Aplica o withNavigationTracking() para tracking automático
    val navController = rememberNavController().withNavigationTracking()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            NavHost(
                navController = navController,
                startDestination = "home"
            ) {
                composable("home") {
                    HomeComposeScreen(
                        onNavigateToDetails = {
                            navController.navigate("details")
                        },
                        onNavigateToSettings = {
                            navController.navigate("settings")
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("details") {
                    DetailsComposeScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("settings") {
                    SettingsComposeScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
fun HomeComposeScreen(
    onNavigateToDetails: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🏠 Home Screen",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Navegação Compose com Tracking",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNavigateToDetails,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Ir para Details", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Ir para Settings", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Voltar", fontSize = 16.sp)
        }
    }
}

@Composable
fun DetailsComposeScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "📋 Details Screen",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(200.dp)
                .background(
                    color = Color(0xFF6200EE),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Details",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Voltar", fontSize = 16.sp)
        }
    }
}

@Composable
fun SettingsComposeScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚙️ Settings Screen",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(200.dp)
                .background(
                    color = Color(0xFF03DAC5),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Voltar", fontSize = 16.sp)
        }
    }
}

