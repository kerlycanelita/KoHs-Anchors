# Entrada del nexo: 1.21.11 frente a 26.x

Verificado el 27 de septiembre de 2026 con `javap -c -p` sobre los jars que Loom descarga
(`minecraft-clientonly` / `minecraft-common` con nombres de Mojang) de 1.21.11, 26.1, 26.1.1,
26.1.2, 26.2 y 26.3. `tools/verify_mixin_targets.py` repite en cada build la parte que el mod
necesita.

La pregunta de partida: los nexos se sienten mejor en 1.21.11 y anteriores. ¿Qué cambió en 26.x?

## Lo que es igual (camino del clic)

| Paso | Resultado |
| --- | --- |
| `Minecraft.handleKeybinds` | Mismo orden en 1.21.11 y 26.2: teclas de barra (como mucho **una pulsación por tecla y tick**, del 1 al 9), inventario, acciones rápidas, mano secundaria, soltar, y al final **todos** los usos (`while (keyUse.consumeClick()) startUseItem()`), y después la repetición con la tecla mantenida (`rightClickDelay == 0`). En 26.2 chat, comando, logros, social y HUD pasaron a `Gui.handleKeybinds`, antes del inventario. |
| `Minecraft.startUseItem` | Idéntico para bloques. 26.2 solo unificó `interactAt` e `interact` para entidades. Fija `rightClickDelay = 4` en cada uso. |
| `MultiPlayerGameMode` | `useItemOn`, `performUseItemOn`, `ensureHasSentCarriedItem`, `startPrediction` y `tick` idénticos. |
| `RespawnAnchorBlock` | Idéntico salvo detalles de compilación. En el cliente, `useWithoutItem` devuelve `CONSUME` con carga > 0 en **cualquier** dimensión: la explosión (`canSetSpawn`) solo se decide en el servidor. |
| `ItemInHandRenderer.tick` | Idéntico: la animación al cambiar de objeto no cambió. |
| Raycast de la mira (`pick`) | 1.21.11 `GameRenderer.pick(float)` público; 26.x `Minecraft.pick(float)` privado. Misma lógica, por frame y al inicio de cada tick con `1.0F`. En 26.2 `gameMode.tick()` va antes de `pick`; en 1.21.11, después. |

Conclusión: **la forma en que el cliente convierte clics en acciones de nexo no cambió.** Lo que
se pierde (ver abajo) se pierde igual en 1.21.11 y en 26.x.

## Lo que sí cambió: el mallado de chunks

1.21.11 recompila las secciones sucias y sube sus mallas en el mismo pase
(`LevelRenderer.compileSections` llama a `SectionRenderDispatcher.uploadAllPendingUploads`).

26.x separó el proceso: `LevelExtractor.extract` recoge los cambios en
`LevelRenderState.sectionUpdateRenderStates` durante la extracción del frame,
`compileSections` los compila (`compileSync` / `compileAsync`) y la subida pasó a los "uber
buffers" (`SectionRenderDispatcher.uploadTerrainBuffersToGpu`).

Con la opción por defecto *Actualizaciones de chunks: ninguna*, colocar, cargar o destruir un
nexo se compila en segundo plano en ambas versiones. **Hipótesis:** en 26.x esos cambios tardan
uno o más frames extra en verse. **No está medido.** Antes de tocar el render hay que medir los
frames entre el cambio de bloque y la malla visible en ambas versiones, con la misma escena.

También cambió la explosión: desde 1.21.9 el paquete trae partículas de escombros de bloques
(`trackExplosionEffects`), que en una pelea de nexos pueden costar fotogramas.

## Dónde se pierden los clics (en todas las versiones)

1. **Orden.** "usar, 2, usar" dentro de un tick se aplica como "2, usar, usar": el nexo que
   querías colocar se intenta colocar con glowstone en la mano. Y varias teclas numéricas en el
   mismo tick ganan por número de slot, no por la última pulsada.
2. **Objetivo viejo.** Todos los usos de un tick apuntan al raycast hecho antes del primero. El
   glowstone apunta al suelo donde acabas de poner el nexo, no al nexo, y el clic falla.
3. **Retroalimentación.** La explosión solo se ve y se oye cuando llega el paquete del servidor,
   un viaje de ida y vuelta después del clic.

## Qué hace la 0.1.0 y su frontera de fair play

| Función | Categoría | ¿Lo ve el servidor? | Riesgo |
| --- | --- | --- | --- |
| Orden real de clics | Entrada (orden) | Sí: `slot → uso → slot → uso` en un tick, en vez de `slot → uso → uso` | Bajo. Cada paquete es una pulsación real, ninguno extra, mismo tick. Probado contra Grim 2.3.74 con hasta +150 ms de lag: 0 alertas ([laboratorio](laboratorio-servidor-0.2.0.md)). |
| Objetivo actualizado | Entrada (precisión) | Sí: el uso apunta al bloque nuevo | Bajo. Raycast de Vanilla, alcance intacto; es lo que Vanilla haría un frame después. |
| Detonación instantánea | Cosmético | No | Ninguno. No borra bloques ni aplica daño. |
| Escombros del nexo | Cosmético, calidad/rendimiento | No | Ninguno. Declarado como reducción de calidad visual. |

Descartado a propósito:

- quitar el retraso de repetición al mantener el clic (equivale a *fast place*);
- ejecutar usos fuera del tick, en el callback de entrada: el paquete llegaría con una rotación
  que el servidor aún no recibió y en un momento que Vanilla nunca usa;
- elegir slots, cargar o detonar por el jugador;
- convertir el nexo en aire en el mundo del cliente (desincroniza colisiones y movimiento). Un
  borrador de la 0.2.0 lo probó en el laboratorio: Grim marca y cancela el clic siguiente
  (`AirLiquidPlace`). La 0.2.0 retiene el clic temprano en su lugar.

## Pendiente

- Hecho en el [laboratorio de servidor](laboratorio-servidor-0.2.0.md): la secuencia
  `nexo → usar → 2 → usar → 3 → usar` dentro de un tick y a ritmo, en 26.2, con entrada por los
  manejadores de teclado y ratón de Minecraft, contra Grim y con hasta +150 ms de lag. Falta
  probarla con tecla remapeada, mano secundaria y manos humanas.
- Medir la latencia del mallado del nexo en 1.21.11 y 26.2 (hipótesis de arriba).
