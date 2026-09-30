# Relevo "Nubecilla" · KoHs Anchor's 0.4.0 (en curso)

Notas para seguir el trabajo en otra sesión. Borrar este archivo cuando todo lo de abajo esté hecho.

## Dónde se quedó

- Rama `wip/0.4.0` (sale de `main` 08ded22, que es la build 0.3.0 publicada). `main` no se toca hasta que el usuario lo pida: su HEAD tiene que cuadrar con la última build publicada.
- Todo el trabajo de 0.4.0 está en esta rama y compila en 26.2. El build completo es `tools/build-all.ps1` (6 versiones: 1.21.11 legacy, 26.1.x classic, 26.2 modern, 26.3 pearl; también corre `tools/verify_mixin_targets.py`, copia a `dist/0.4.0` y genera CHECKSUMS). Lee el script para ver cómo se compila cada versión con Gradle.
- Ya hecho en 0.4.0 (no rehacer): rendimiento de la GUI (`AnchorsUi.isolate` / `nextStratum`), glow con cono de visión, backface culling, LOD y calidad (performance/balanced/high), luz fantasma al explotar arreglada, forma de tick (Grim: un cambio de slot por tick antes de los clics), velo con timeout según el ping, humo de explosión (vanilla/ligero/nada), glowstone guard (ya **desactivado por defecto**), tiempo de ciclo en stats, tracker siempre activo, servidores permitidos solo en memoria (nunca se guardan ni se muestran), icono nuevo (`assets/kohs_anchors/icon.png`, 256×256).

## Pendiente (órdenes del usuario, en este orden)

### 1. Advertencia del glowstone guard (morada, muy animada, skill de diseño Zymekoh)

- Al activar `glowstone_guard` en General (`gui/AnchorsScreen.java`) se abre un modal morado muy animado. En el centro va el vídeo `src/main/resources/assets/kohs_anchors/video/safe_anchor.mp4`: 5 s de secuencias de safe anchor recortadas de una grabación del usuario, 854×480, **60 fps**, 300 fotogramas, H.264 main, CRF 18, sin B-frames, un IDR cada 60 fotogramas (4,6 MB).
- Los textos ya están en las 8 lenguas: `kohs_anchors.glowstone_warning.title`, `.body` ("Si activas esta opción no podrás hacer safe anchor. Está pensada más para spameo. Estás advertido."), `.accept`, `.cancel`, `.charging` y `.activated`.
- **El usuario exige mantener los 60 fps y la calidad alta.** Método: decodificar el H.264 en Java con JCodec (`org.jcodec:jcodec`, licencia BSD-2; se mete en el jar con `include` de Loom) en un hilo de fondo, con un búfer circular de 3 o 4 fotogramas, y subirlos a una `DynamicTexture` (NativeImage RGBA) en el hilo de render, con el tiempo que marque `System.nanoTime`. En bucle, reiniciando en el IDR 0. Primero comprueba que JCodec decodifica este stream (CABAC main). Si falla, vuelve a codificarlo con libx264 `profile=baseline`: PyAV 18 con libx264 funcionó en el PC del usuario (`crf 18`, `bf 0`, `keyint=60`). No metas JPEG fotograma a fotograma: a 24 fps ya pesaban 7 MB.
- Después de aceptar viene una animación de carga corta con sonido: un anchor con efecto de velocidad perseguido por una glowstone con glow. Al terminar la carga, la glowstone alcanza al anchor, lo carga entero (4 cargas), explota y sale el mensaje "Activado". Usa los sonidos vanilla del respawn anchor (charge, explosión).

### 2. Glow en bloques bajos

El derrame de luz del glow no llega a los bloques más bajos alrededor de un pilar: bajo un anchor puesto encima de un pilar queda una sombra dura. Tiene que llegar al menos 2 bloques hacia abajo, o busca algo mejor (por ejemplo, emisores por cara con línea de visión propia). Mira `glow/AnchorGlowRenderer.java` (`SpillLight`, `faces[]`/`planes[]`, `SPILL_DISTANCE`).

### 3. Cambio a enemy anchors

- Animación de ida: el anchor del usuario va al centro, el fondo pasa a rojo, el anchor enemigo (según su configuración) ocupa despacio su lugar y se abre la página enemy (`toggleEnemyAnchors` / `drawEnemySwitch` en `AnchorsScreen`).
- Solo la primera vez: modal "Enemy anchors: qué es" (sirve para cambiar cómo se ven los anchors que tú no colocaste) con "No volver a mostrar" (guardado en la config) y "Continuar".
- El color enemigo es editable y la vista previa lo muestra exactamente igual que el anchor enemigo.
- Al volver, la animación inversa: el fondo rojo y el anchor enemigo del centro vuelven al del usuario.
- Reforzar la detección de anchors propios frente a enemigos (`glow/AnchorTracker`, `mixin/ClientLevelPredictionAccessor`: predicciones pendientes y acks). Se puede usar el laboratorio de servidor, pero solo en localhost.

### 4. Pestaña Herzium (6.ª pestaña)

- Sube `TAB_COUNT` y mantén en verde `AnchorsLayoutCheck` (`src/layoutCheck`).
- Sin Herzium instalado, la pestaña sale opaca. Al pulsarla se abre una ventana animada con el logo de Modrinth (su círculo exterior gira), el texto "Requiere Herzium instalado; consíguelo en Modrinth" con enlace clicable (comprueba el slug real) y una explicación: acoplar y priorizar anchors con Herzium, las órdenes de Herzium, y que "last input" ayuda mucho a la velocidad.
- La opción de Herzium que ya existe se mueve a esta pestaña.
- Con Herzium instalado: al entrar, una ventana que explique el uso y las órdenes. Recomienda siempre "last input" como primera opción; el cambio de orden se lee del archivo de configuración que crea Herzium (no imponemos config). Debajo van las opciones, incluida "Better communication with Herzium orders", para que los dos mods se comuniquen sin errores (por ejemplo, avisar a Herzium cuando KoHs consume o selecciona slots: `HotbarOrderController.hotbarClickConsumed` / `ImmediateHotbarInput.noteCallSiteSelection`). Comprueba la API real en el jar de Herzium antes de usarla.

### 5. Pestaña / ventana KoHs

Muy animada, con la chica de la web (https://kerlycanelita.github.io/KoHs-Mod-Suite/, repo `kerlycanelita/KoHs-Mod-Suite`). Debajo, su nombre, su aka y lo que hace, y "KoHs on top" al final. Botones: Discord, página web y Modrinth.

### 6. Después

Docs en inglés para GitHub y Modrinth: README 0.4.0 con galería, CHANGELOG, doc de investigación 0.4.0 (rendimiento, forma de tick, luz fantasma, resultados del laboratorio: Herzium 100 %, 0 alertas con Grim estricto), traducción de los 4 docs de investigación en español (renombrando sus imágenes), quitar las imágenes `config-3d-*` en español y la descripción de Modrinth. Las gráficas en inglés ya están en `docs/images/`.

## Reglas (del usuario)

- Commits como `Zymekoh <204989852+kerlycanelita@users.noreply.github.com>`, **sin** `Co-Authored-By` ni ninguna atribución a Claude. Usa `--author` y `-c user.name/-c user.email`.
- No publiques en Modrinth ni toques `main`.
- Nada de automatización: nada que apunte o actúe por el jugador; solo velocidad y precisión.
- Mixins: nada de `require = 0`; verifica los targets con `tools/verify_mixin_targets.py`/javap en las 4 eras.
- GUI al estilo Zymekoh: morado dominante, animación por tiempo (`System.nanoTime`), sin parpadeos fuertes, y rendimiento cuidado (`isolate`).
- Cada texto nuevo va a las 8 lenguas.
- Si el límite de uso llega al 94 %: commit y push de lo hecho, y actualiza este archivo con el punto exacto donde lo dejas.
