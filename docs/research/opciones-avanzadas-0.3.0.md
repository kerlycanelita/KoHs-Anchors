# Ocultar el nexo, cadena sin espera y clic inmediato (0.3.0)

Escrito el 28 de septiembre de 2026. Todavía no se midió en el laboratorio ni en juego: el
jugador lo probará. Este documento explica por qué Anchor Optimizer se sentía más rápido, qué hace
cada opción nueva y el análisis de fair play y anticheat de cada una.

## Por qué Anchor Optimizer se sentía más rápido

Se leyó el jar de la instancia del jugador (`AnchorOptimizer-26.3.jar`, "Anchor Optimizer"
1.0.6 de cutebow) sin ejecutarlo:

- Al detonar, antes de enviar nada, cambia el nexo del mundo local por un **bloque barrera
  invisible**, sin contorno si activas `removeOutline`. Durante hasta 40 ticks ignora los paquetes
  del servidor que lo restaurarían.
- El siguiente clic sale en el acto hacia la posición del nexo viejo. Cuando llega, el servidor ya
  lo explotó y coloca ahí el nuevo.
- KoHs Anchor's 0.2.1 dejaba el nexo visible hasta que el servidor lo quitaba y retenía el clic
  siguiente hasta ese momento: un viaje de ida y vuelta más, y hasta un tick.

Además, ese mod añade el servidor WestShop a la lista de multijugador, se actualiza solo desde
Modrinth y tiene licencia "All Rights Reserved".

## Qué hace la 0.3.0

| Opción | Pestaña | Por defecto | Qué cambia |
| --- | --- | --- | --- |
| Ocultar nexo detonado | Efectos | Activada | El nexo desaparece al instante en el dibujo del chunk (Vanilla y Sodium) y sin contorno. El mundo, la colisión y los paquetes no cambian. |
| Detonación instantánea | Efectos | Activada | El sonido, el destello y el ocultado ocurren en el instante en que pulsas usar (tecla remapeada o ratón), no en el siguiente tick. El uso sigue saliendo en el tick. |
| Cadena sin espera | Avanzadas · not secure | Desactivada | Los clics sobre un nexo que explota se envían sin esperar, y el siguiente nexo, su carga y su explosión se dibujan al instante. |
| Clic de detonación inmediato | Avanzadas · not secure | Desactivada | Un clic que detona se envía en el instante en que lo pulsas, entre ticks (hasta 50 ms antes). |

La cadena sin espera es la idea de Anchor Optimizer, mejorada:

- **Sin tocar el mundo.** Solo se dibuja distinto. La colisión y la mira siguen siendo las del
  bloque real, así que no hay barreras fantasma ni problemas en creativo.
- **Cada clic se juzga contra lo que ves.** El nexo siguiente aparece en su sitio, con sus luces
  de carga, y su explosión se ve al instante.
- **Sin colocaciones por error.** Se descartan la glowstone que caería como bloque donde no hay
  nexo y el nexo que se apilaría sobre uno recién puesto.
- **Clics que atraviesan un nexo dibujado.** Si la mira atraviesa un nexo que ves pero que el
  mundo aún no tiene, el clic espera a que el mundo lo tenga, en vez de caer en el suelo de detrás.
- **Pestaña carmesí y advertencia.** Activar una opción de esta pestaña pasa por la advertencia
  "ESTÁS ADVERTIDO", con 2,2 s de lectura, y termina en el ritual del nexo.

## Análisis de fair play y anticheat

Hecho con la skill `minecraft-fair-play-anti-cheat-compatibility-analyst`.

### Ocultar nexo detonado y detonación instantánea

- **Implementación:** cliente, solo dibujo y sonido.
- **¿Cambia el comportamiento Vanilla?** No. No hay paquetes nuevos, ni orden distinto, ni cambios en el mundo.
- **¿Sensible para anticheats?** No. Categoría: COSMETIC_CLIENT_ONLY.
- **Riesgo con las reglas del servidor:** bajo; es una predicción visual.
- **Confianza:** confirmada por diseño (solo se enganchan el mallado, el contorno y el sonido).

### Cadena sin espera

- **Implementación:** cliente. Cada clic lo envía el `startUseItem` de Vanilla, al bloque que
  apunta la mira de Vanilla: es el mismo paquete que manda un jugador Vanilla que hace clic sobre
  el nexo aún visible.
- **¿Cambia el comportamiento Vanilla?** Poco en los paquetes. Lo que cambia es que ya no hay
  espera y que ves el resultado antes, así que se hace clic antes y más a menudo.
- **¿Sensible para anticheats?** Probablemente. En el laboratorio, los clics tempranos de Vanilla
  sobre un nexo que explota provocaron `AirLiquidPlace` de Grim: 6 alertas a +100 ms y 2 a +150 ms
  en 20 ciclos. Esta opción hace ese patrón sistemático. Categoría: INPUT_ASSISTANCE (tiempo).
- **Riesgo con las reglas del servidor:** muchos servidores prohíben los "anchor optimizers".
- **Alternativa en el servidor:** que el servidor acepte, con compensación de lag, la colocación
  en la posición de un nexo recién explotado; o una exención configurada por el dueño del
  servidor. No es algo que el cliente deba esquivar.
- **Confianza:** probable, por los datos de Vanilla; la opción en sí no se ha medido.

### Clic de detonación inmediato

- **Implementación:** cliente; envía `ServerboundUseItemOnPacket` entre ticks del cliente.
- **¿Cambia el comportamiento Vanilla?** Sí, en el orden y el momento de los paquetes. Vanilla solo
  envía interacciones dentro del tick, antes de su paquete de movimiento; esta opción la envía
  después del paquete de movimiento del tick anterior.
- **¿Sensible para anticheats?** Sí. Categorías: PACKET_ORDER / TIMING. Los anticheats con
  comprobaciones de orden de paquetes pueden marcarlo; no se verificó contra el código de Grim.
- **Ganancia:** como mucho 50 ms (unos 25 ms de media) y solo en la detonación: el servidor sigue
  actuando en su propio tick.
- **Riesgo con las reglas del servidor:** alto; es de la misma familia que los mods de "hitreg",
  que muchos servidores prohíben.
- **Confianza:** necesita verificación en el laboratorio con Grim.

### Protecciones

- Las dos opciones avanzadas vienen desactivadas.
- Activarlas exige leer la advertencia durante 2,2 s.
- Desactivarlas es inmediato.
- Viven solas en una pestaña carmesí marcada "not secure".
- Mejora a futuro: activarlas solo en servidores que las anuncien mediante un handshake con un mod
  o plugin de servidor.

## Plan de prueba sugerido

En el laboratorio de KoHs Debug Tools (`anchors-debug`), con Grim y Ping Equalizer:

1. `bench triple` y `bench spam` con **Ocultar nexo detonado**. Esperado: nada cambia en el
   servidor ni en las alertas respecto a la 0.2.1.
2. Los mismos bancos con **Cadena sin espera** a +0, +50, +100 y +150 ms. Comparar el tiempo por
   nexo con la 0.2.1 y contar las alertas `AirLiquidPlace`.
3. **Clic de detonación inmediato**. Mirar la consola de Grim en busca de alertas de orden de
   paquetes.
