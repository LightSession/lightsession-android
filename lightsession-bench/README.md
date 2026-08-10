# lightsession-bench — quanto o SDK custa, medido

App que mede **memória, CPU, frames e vazamento** do LightSession dentro de um processo real,
comparando o mesmo uso da tela com a gravação desligada e ligada. Nasceu do harness do
`okhttptest`, que fazia a mesma pergunta para três clientes HTTP; a disciplina de medição veio de lá
inteira, porque nada nela era sobre HTTP.

## Rodar

Pela tela:

```bash
./gradlew :lightsession-bench:installDebug
```

Abre, aperta **Init + run A/B**, e assiste. A lista embaixo da linha divisória é o que está sendo
gravado; tudo acima dela é instrumento.

Por linha de comando, que é como os números viram reproduzíveis. **Num aparelho físico, suba um
ingest primeiro** — sem isso você mede o caso offline sem perceber:

```bash
# um sink que aceita tudo, no host
python3 scripts/sink.py 5055 &
adb reverse tcp:5055 tcp:5055
adb reverse tcp:3002 tcp:3002

adb shell am start -n com.lightsession.bench/.BenchActivity --ez autorun true --ei arm 20 \
  --es ingest http://127.0.0.1:5055 --es api http://127.0.0.1:3002
adb logcat -s LightSession.Bench

# ordem invertida, pra separar custo da lib de custo de ser o primeiro braço
adb shell am start ... --ez onFirst true

# só a caça a vazamento
adb shell am start -n com.lightsession.bench/.BenchActivity --ez autorun true --es mode leak
```

Tudo que aparece no log da tela sai também nesse tag, tabela final incluída.

**Confira que o ingest recebeu.** Se o sink não registrou nenhum POST, o braço ON mediu envio
falhando e spool em disco, não envio. Já aconteceu aqui — veja as armadilhas.

**Emulador serve pra validar o instrumento. Os números que valem são de aparelho real** — e três
execuções, não uma.

## A sequência

```
GC → aquece o app (12s, descartado) → baseline → init
   → aquece o recorder (12s, descartado) → drena o flush
   → braço OFF (20s) → braço ON (20s)
   → stopRecording → GC → retido
```

Os dois braços arrastam a mesma lista, com os mesmos gestos sintéticos, pelo mesmo tempo. A única
diferença entre eles é se o recorder está rodando — é isso que torna a diferença atribuível a ele.
O contador de gestos no fim diz se deu certo: se os dois braços não tiveram o mesmo número, a carga
não foi a mesma.

Os dois aquecimentos não são zelo. Sem o primeiro, o braço que roda antes paga carga de classe e JIT
do app inteiro; sem o segundo, o braço ON paga a do próprio SDK. Ambos já inverteram o resultado
aqui — veja as armadilhas.

O braço OFF vem primeiro por convenção, não por necessidade: `--ez onFirst true` inverte, e é assim
que se checa se uma diferença é da lib ou de ser o primeiro.

## As quatro medições, e por que são quatro

| O quê | Como | Responde |
|---|---|---|
| Memória | PSS/heap/nativo via `Debug.getMemoryInfo`, com GC forçado de verdade | quanto sobe no pico, e quanto **sobra** depois de coletar |
| CPU do SDK | `/proc/self/task/*/stat` filtrado pelas threads que o SDK batiza | quanto de CPU é atribuível à lib, sem subtrair dois builds ruidosos |
| Frames | `FrameMetrics` da janela | o custo que o SDK **tira** da main thread, que a CPU por thread não enxerga |
| Vazamento | LeakCanary, com `ObjectWatcher` explícito | se uma Activity gravada e destruída ainda é alcançável |

As duas primeiras não bastam. Planejar as máscaras percorre a árvore de views **na main thread** —
10 a 26 ms por captura, medido escrevendo o `MaskStalenessTest` — e esse tempo é cobrado da thread do
app, não das do SDK. Não é CPU que a lib *usa*; é CPU que ela *tira*. Aparece como travada, não como
porcentagem. Um harness que reportasse só a CPU das threads do SDK daria um número lisonjeiro e
perderia a metade cara.

## Armadilhas que já custaram caro aqui

**Cleartext bloqueado, e falha calada.** Faltava `usesCleartextTraffic` no manifest. Desde a API 28
o Android bloqueia HTTP sem TLS por padrão, então *todo* envio falhava, ia pro spool e era
retentado — e nada na saída dizia isso. Sobreviveu a várias execuções porque a checagem óbvia passa:
`adb shell curl` alcança o ingest, já que o shell não é o app e não está sujeito à política de rede
dele. **A única checagem que pega é contar o que chega do outro lado.**

**`10.0.2.2` não existe num aparelho físico.** É o alias do host visto do emulador. Apontado pra lá,
cada envio espera timeout num endereço não roteável, e o braço mede a pilha de rede desistindo. Use
`adb reverse` e `127.0.0.1`.

**Sem aquecimento, a comparação sai invertida.** Num Tab A7 o primeiro braço pagava carga de classe,
JIT e primeiras composições: gravação **desligada** deu 32,2% de jank e p95 46,7 ms contra 15,0% e
37,4 ms com ela **ligada** — o que se lê como a lib deixando o app mais rápido. O emulador escondeu
isso por ser rápido o bastante pro aquecimento caber na acomodação do primeiro braço.

**O `stopRecording()` do aquecimento despeja flush dentro do braço seguinte.** Ele força o envio de
tudo que o aquecimento gravou. Com uma acomodação só, isso ainda estava rodando quando o braço OFF
tirou o baseline — e o braço *sem* gravação estava medindo o gravador.

**Contagem de gestos é o controle.** Se os dois braços não tiveram o mesmo número de gestos, a carga
não foi a mesma e a comparação não vale. Nas execuções boas dá 32/32.

**O kernel corta nome de thread em 15 caracteres.** `LightSession-Scheduler` vira
`LightSession-Sc` no `/proc`. Casar pelo nome inteiro não acha nada, silenciosamente, e o harness
reporta uma biblioteca que custa zero CPU.

**Limiar de jank em 1 vsync não mede nada.** Neste emulador a 60,000004 Hz o frame mediano leva
16,8 ms contra um intervalo de 16,67 — então "acima de um intervalo" marcou 99,7% dos frames com a
gravação desligada e 98,7% com ela ligada. `TOTAL_DURATION` cobre o pipeline inteiro e cai um triz
acima do intervalo sem que frame nenhum tenha sido descartado. Da API 31 em diante o certo é o
`FrameMetrics.DEADLINE`, que é o prazo daquele frame segundo a plataforma; abaixo disso, dois
intervalos.

**LeakCanary dumpando heap destrói a medição de memória.** O dump são centenas de milissegundos e
dezenas de MB **neste processo**. Por isso `dumpHeap` fica desligado e a resposta "vazou?" sai da
contagem de objetos retidos depois de um GC real, que é de graça. Ligue o dump só quando a resposta
for "sim" e a próxima pergunta for "quem está segurando".

**GC antes de todo baseline, sem exceção.** A primeira versão do `ensureInitialised` lia o baseline
sem coletar, e a coleta caía dentro da janela medida: o `init` apareceu *liberando* 1,7 MB. Com o GC
no lugar certo, +3,3 MB.

**Rolar por código não é a carga.** `LazyListState.scrollBy` move a lista e mais nada. O recorder
captura a cada `captureIntervalMs` (1 s) normalmente e a cada `interactionCaptureIntervalMs`
(100 ms) **enquanto há toque** — sem `MotionEvent` ele nunca sai do ritmo lento, e o braço mediria um
décimo da taxa de captura que uma rolagem real provoca. Por isso o `TouchDriver` injeta gesto de
verdade em `Window.getCallback()`, que é onde o framework entrega, e portanto também atravessa o
`InteractionAwareCallback`.

## O que este módulo *não* mede

O custo de **enviar** a biblioteca: dex, fatia do oat, recursos. Isso está no APK chamando `init` ou
não, então nenhuma comparação dentro de um processo enxerga. Esse número sai de compilar dois APKs e
comparar.

E a coluna de CPU não inclui o trabalho em `Dispatchers.IO`/`Default` (o `SessionDataManager` e o
escopo do `ScreenMapperIntegration`): são pools compartilhados com o app hospedeiro, então o tempo
neles não é atribuível a ninguém. Está dentro de "process cpu", sem dono.

## Referência — Galaxy Tab A7 (SM-T500, API 31, Snapdragon 662, 2,7 GB)

Três execuções, 20 s por braço, ingest recebendo de verdade. Médias:

```
                              off        on          Δ
  process cpu             22354 ms   24465 ms   +2111 ms  (+9,4%)
  cpu das threads do SDK     60 ms      90 ms     +30 ms
  main thread cpu         11460 ms   10163 ms   -1297 ms
  p50 do frame             21,4 ms    23,6 ms     +2,2 ms
  frames em 20 s              1000       1031
  gestos                        32         32
  init: código +274 KB, +7 a +8 threads
```

**O achado principal é a linha 2 contra a linha 1.** Gravar custa ~9,4% a mais de CPU do processo, e
as threads que o SDK batiza respondem por **1,4% disso** (30 ms de 2111). O resto está na main thread
e nos dispatchers compartilhados de corrotina, que não são atribuíveis a ninguém.

Ou seja: a atribuição por thread — que é justamente o que este módulo tem de mais afiado — **subestima
o custo real em quase duas ordens de grandeza**. Era a hipótese de projeto, e ela se confirmou de
forma mais extrema do que eu esperava. Quem medisse só `sdkCpuMs` concluiria que a lib é de graça.

**p50 é o sinal de frame, não a cauda.** p50 subiu nas três (20,7→23,5, 20,3→23,5, 23,2→23,8) e o p95
foi pra cima, pra baixo e pra cima (40,6→41,2, 40,7→37,1, 40,8→41,1). Durante o toque a captura roda
a cada 100 ms, que a 60 Hz é um frame em seis — frequente demais pra ficar na cauda, então desloca o
meio da distribuição.

**A main thread gastando menos CPU com gravação ligada** repetiu nas três, e não tenho explicação
fechada. Junto com "mais frames, cada um mais lento e o processo gastando mais CPU no total", sugere
trabalho saindo da main e a main passando mais tempo bloqueada. Não investiguei.

**Memória: sem sinal claro.** Pico de PSS ficou maior com gravação em 2 de 3 execuções e menor na
outra; nativo empatou. O custo de memória de gravar, nesta carga, está dentro do ruído de ±2 MB.

**Depois do `stopRecording()`:** 35–36 threads vivas e PSS retido negativo. As threads ficam mesmo
(o `Recorder.shutdown()` continua inalcançável), mas não há inchaço de memória junto.

**`ls-pixelcopy` não apareceu em nenhuma execução**, nem no tablet nem no emulador: a carga não tem
bitmap de hardware, então a captura foi pelo caminho de software, que desenha na main thread. Uma
carga com Coil/Glide empurraria pro `PixelCopy` e mudaria a divisão inteira. Vale medir os dois.

### Emulador, pra comparar

O mesmo instrumento num emulador API 36 dava p95 21–22 ms no braço ON contra 16,9–20,2 no OFF, com
piso de ruído do mesmo tamanho do efeito. Serviu pra provar que o instrumento mede; não serviu pra
dizer quanto a lib custa.

## Estrutura

```
probe/MemProbe.kt    PSS/heap/threads/fds + GC forçado   (portado do okhttptest)
probe/CpuProbe.kt    CPU por thread via /proc
probe/JankProbe.kt   FrameMetrics da janela
probe/LeakProbe.kt   LeakCanary na coleira
probe/Sampler.kt     amostragem em thread própria
run/Bench.kt         config, resultado e a sequência
run/TouchDriver.kt   gestos sintéticos
run/LeakHunt.kt      abre uma tela, grava, fecha, pergunta se soltou
ui/                  gráfico e a lista que serve de carga
```
