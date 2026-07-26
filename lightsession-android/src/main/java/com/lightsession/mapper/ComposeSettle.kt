package com.lightsession.mapper

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.snapshots.Snapshot
import java.lang.ref.WeakReference

/**
 * Espera uma tela Compose terminar de compor, em vez de adivinhar quanto tempo isso leva.
 *
 * # O problema
 *
 * Para uma Activity, `onActivityResumed` só é chamado depois de measure/layout — a
 * hierarquia já está desenhável e o skeleton pode ser gerado no ato.
 *
 * Compose não funciona assim. O `OnDestinationChangedListener` do NavController
 * dispara quando o *destino* muda, o que acontece antes da composição emitir
 * qualquer LayoutNode. Capturar nesse instante devolve uma árvore vazia. Pior:
 * falha em silêncio — o bitmap sai válido, só que em branco.
 *
 * A tentativa anterior era `postDelayed(1000)` (e `postDelayed(300)` no outro
 * caminho). Nenhum número funciona: é demais para uma tela estática e de menos
 * para uma que busca dados da rede. Quando erra, grava um skeleton errado.
 *
 * # A solução
 *
 * Não existe callback de "a composição terminou" — porque conceitualmente ela
 * nunca termina, só para de mudar. Então é isso que se observa:
 *
 * * [Snapshot.registerApplyObserver] avisa a cada aplicação de estado, ou seja, a
 *   cada recomposição que de fato mudou algo;
 * * `OnDrawListener` avisa a cada desenho;
 * * um frame callback do [Choreographer] verifica, a cada quadro, se as duas
 *   coisas ficaram quietas por [quietMs].
 *
 * Quando o silêncio dura o suficiente **e** a composição tem ao menos um nó, a
 * tela assentou. Tela rápida é capturada em ~100ms em vez de 1000ms; tela lenta
 * espera o quanto precisar, até [timeoutMs].
 *
 * O observador de snapshot é só uma escrita de timestamp, então o custo por
 * recomposição é desprezível — nada de varrer a composição a cada quadro.
 */
internal class ComposeSettleDetector(
    private val quietMs: Long = DEFAULT_QUIET_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    companion object {
        /** Silêncio necessário para considerar a tela assentada. */
        const val DEFAULT_QUIET_MS = 120L

        /**
         * Teto absoluto. Uma tela com animação infinita (shimmer, spinner) nunca
         * fica quieta; passado esse tempo captura-se o que houver, que é melhor
         * que não ter skeleton nenhum.
         */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }

    private var handle: Handle? = null

    /**
     * Uma espera em andamento. Cancelável — o chamador precisa disso porque o
     * usuário pode navegar de novo, ou tocar na tela (o que, pela heurística do
     * ScreenMapper, desqualifica a tela para mapeamento).
     */
    inner class Handle internal constructor(
        private val activityRef: WeakReference<Activity>,
        private val hasContent: () -> Boolean,
        private val onSettled: (Activity) -> Unit,
    ) {
        private val startedAt = SystemClock.uptimeMillis()

        @Volatile
        private var lastChangeAt = SystemClock.uptimeMillis()

        @Volatile
        private var cancelled = false

        private var snapshotHandle: androidx.compose.runtime.snapshots.ObserverHandle? = null
        private var drawListener: ViewTreeObserver.OnDrawListener? = null
        private var frameCallback: Choreographer.FrameCallback? = null
        private var observedView: WeakReference<View>? = null

        internal fun start() {
            val activity = activityRef.get() ?: return finish(null)
            val root = activity.window?.decorView ?: return finish(null)

            // Qualquer estado aplicado é uma recomposição que mudou algo.
            snapshotHandle = Snapshot.registerApplyObserver { _, _ ->
                lastChangeAt = SystemClock.uptimeMillis()
            }

            val listener = ViewTreeObserver.OnDrawListener {
                lastChangeAt = SystemClock.uptimeMillis()
            }
            runCatching { root.viewTreeObserver.addOnDrawListener(listener) }
                .onSuccess {
                    drawListener = listener
                    observedView = WeakReference(root)
                }

            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (cancelled) return
                    val now = SystemClock.uptimeMillis()

                    val current = activityRef.get()
                    if (current == null || current.isFinishing || current.isDestroyed) {
                        return finish(null)
                    }

                    val quietFor = now - lastChangeAt
                    val elapsed = now - startedAt

                    if (quietFor >= quietMs && hasContent()) {
                        Log.d("ComposeSettle", "settled after ${elapsed}ms (quiet ${quietFor}ms)")
                        return finish(current)
                    }

                    if (elapsed >= timeoutMs) {
                        // Sem conteúdo depois do teto: capturar geraria um skeleton
                        // em branco, que é pior que nenhum. Desiste.
                        val content = hasContent()
                        Log.w(
                            "ComposeSettle",
                            "timed out after ${elapsed}ms (hasContent=$content)"
                        )
                        return finish(if (content) current else null)
                    }

                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            teardown()
        }

        private fun finish(activity: Activity?) {
            if (cancelled) return
            cancelled = true
            teardown()
            onSettled.let { callback ->
                if (activity != null) callback(activity)
            }
        }

        private fun teardown() {
            snapshotHandle?.dispose()
            snapshotHandle = null

            frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            frameCallback = null

            drawListener?.let { listener ->
                observedView?.get()?.let { view ->
                    runCatching { view.viewTreeObserver.removeOnDrawListener(listener) }
                }
            }
            drawListener = null
            observedView = null
        }
    }

    /**
     * Aguarda a tela assentar e então chama [onSettled] na main thread.
     *
     * `onSettled` não é chamado se a espera for cancelada, se a Activity morrer,
     * ou se estourar o tempo sem a composição produzir nenhum nó.
     *
     * @param hasContent avaliado só quando a tela parece quieta, não a cada quadro.
     */
    fun await(
        activity: Activity,
        hasContent: () -> Boolean,
        onSettled: (Activity) -> Unit,
    ): Handle {
        cancel()
        val handle = Handle(WeakReference(activity), hasContent, onSettled)
        this.handle = handle
        handle.start()
        return handle
    }

    fun cancel() {
        handle?.cancel()
        handle = null
    }
}
