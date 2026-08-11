package com.sample.lightsession

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lightsession.mapper.withNavigationTracking
import kotlinx.coroutines.delay

/**
 * A screen that shows a spinner and then becomes itself, which is the case every dashboard is.
 *
 * ## Why this Activity exists
 *
 * A production screen — a rep's metrics page — stored a wireframe far poorer than the screen. Not
 * masking, not the subcomposition bug: the settle detector declared the screen quiet **139 ms**
 * after navigation, and it was right to. An indeterminate `CircularProgressIndicator` animates in
 * the draw phase, through a RenderNode the compositor re-renders on its own, so it produces neither
 * a snapshot apply nor a `ViewRootImpl` draw pass — the two signals settling watches. The screen
 * looked motionless, and the scan stored the shell around a spinner.
 *
 * Nothing in this repository could reproduce that. Every Compose sample here paints its content
 * immediately, so every one of them settles into a screen that is already finished. This is the
 * missing shape, and it is the common one: fetch on entry, spinner, then content.
 *
 * ## What it reproduces, exactly
 *
 * The nesting of the app it came from, because the wireframe has to survive all of it — a
 * `Scaffold` with a bottom bar, a `NavHost` in its content slot, and a destination that is *itself*
 * a `Scaffold`. Three composition boundaries, which `NestedScaffoldNavProbeTest` measures the scan
 * through, plus the [LOAD_MS] wait that the probe cannot stage.
 *
 * It navigates on its own after [ENTER_MS]. That is not convenience: a tap would send `ACTION_DOWN`
 * through the mapper's touch hook, which cancels a pending capture by design, and a run that
 * depends on someone tapping at the right moment is not a measurement. Navigating from a
 * `LaunchedEffect` reproduces the arrival without the touch.
 *
 * Watch it work:
 * ```
 * adb shell am start -n com.sample.lightsession/.LoadingScreenActivity
 * adb logcat -s ScreenMapper:D ComposeSettle:D
 * ```
 * The wireframe is sent while the spinner is up, and `Late content: skeleton replaced` follows when
 * the data lands — the second line is the one this Activity exists to produce.
 */
class LoadingScreenActivity : ComponentActivity() {

    private companion object {
        /**
         * How long the fake fetch takes, overridable with `--el load <ms>`.
         *
         * The default is far past the ~130 ms the screen settles in — the ordinary case. Passing
         * something like 300 stages the fast one: data landing while the first wireframe is still
         * being recoloured and sent, which is exactly the window the watch must already be armed
         * for. A cached API answers in that range.
         */
        const val LOAD_MS = 2_500L

        /** Time on the first destination before navigating, so the arrival is a real transition. */
        const val ENTER_MS = 1_500L
    }

    private var loadMs = LOAD_MS

    /**
     * Which destination to navigate to, with `--es screen form`.
     *
     * `performance` is the loading-then-content case. `form` is a screen of text fields, which is
     * the shape that exposed what masking does to a container's sampled colour: a field is mostly
     * masked text, so a column of them is mostly mask grey.
     */
    private var destination = "performance"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadMs = intent?.getLongExtra("load", LOAD_MS) ?: LOAD_MS
        destination = intent?.getStringExtra("screen") ?: "performance"
        setContent { Shell() }
    }

    @Composable
    private fun Shell() {
        val nav = rememberNavController().withNavigationTracking()

        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        listOf("route" to "Roteiro", "performance" to "Métricas").forEach { (route, label) ->
                            NavigationBarItem(
                                selected = false,
                                onClick = { nav.navigate(route) },
                                icon = { Text(label.take(1)) },
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    NavHost(nav, startDestination = "route") {
                        composable("route") {
                            LaunchedEffect(Unit) {
                                delay(ENTER_MS)
                                nav.navigate(destination)
                            }
                            Column(Modifier.fillMaxSize().padding(16.dp)) {
                                Text("Roteiro", style = MaterialTheme.typography.headlineSmall)
                                Text("indo para Métricas em ${ENTER_MS}ms…")
                            }
                        }
                        composable("performance") { Metrics() }
                        composable("form") { FormScreen() }
                    }
                }
            }
        }
    }

    /** The screen under study: a spinner for [loadMs], then the cards. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun Metrics() {
        var loading by remember { mutableStateOf(true) }

        // The fake network. Flipping this is a `MutableState` write, which is a snapshot apply —
        // the same event a real `isLoading = false` produces through `collectAsStateWithLifecycle`,
        // and the one the SDK now wakes on.
        LaunchedEffect(Unit) {
            delay(loadMs)
            loading = false
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Minhas métricas") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    MetricsContent()
                }
            }
        }
    }

    /**
     * A form, which is what a screen of text fields does to colour sampling.
     *
     * Mirrors the production `doctor/edit/{id}`: a `Scaffold` over a scrolling `Column` of
     * `OutlinedTextField`s. Every field is masked text, so the pixels inside any container that
     * wraps several of them are mostly `Masking.MASK_COLOR` — and a container is only supposed to
     * gain a `surface` when a colour it *actually has* dominates it.
     */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun FormScreen() {
        Scaffold(topBar = { TopAppBar(title = { Text("Editar médico") }) }) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    "Nome" to "Dra. Helena Vasconcelos",
                    "CRM" to "123456-SP",
                    "Especialidade" to "Cardiologia",
                    "E-mail" to "helena@clinica.com.br",
                    "Telefone" to "(11) 98888-7777",
                    "Endereço" to "Av. Paulista, 1000",
                    "Cidade" to "São Paulo",
                    "Observações" to "Prefere visitas pela manhã",
                ).forEach { (label, value) ->
                    androidx.compose.material3.OutlinedTextField(
                        value = value,
                        onValueChange = {},
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Composable
    private fun MetricsContent() {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Mensal", "Trimestral", "Anual", "Personalizado").forEach {
                    FilterChip(selected = it == "Mensal", onClick = {}, label = { Text(it) })
                }
            }
            repeat(3) { index ->
                // The first card is red on purpose, mirroring the production goal card that
                // exposed the recolour gap: a CONTAINER is a stroked rect, stroked rects were
                // never sampled, and the renderer never filled them — so the most striking
                // surface on the screen rendered white.
                Card(
                    Modifier.fillMaxWidth(),
                    colors = if (index == 0) {
                        androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFFC62828),
                        )
                    } else {
                        androidx.compose.material3.CardDefaults.cardColors()
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Cobertura $index")
                                Spacer(Modifier.height(2.dp))
                                Text("72,5%", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { 0.725f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("29 de 40 médicos visitados")
                    }
                }
            }
        }
    }
}
