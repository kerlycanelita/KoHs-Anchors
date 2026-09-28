# Nexo y Herzium: el objeto de cada clic (0.2.1)

Medido el 27 de septiembre de 2026 en Minecraft 26.2 con el laboratorio de
[KoHs Debug Tools, `anchors-debug/`](https://github.com/kerlycanelita/KoHs-Debug-Tools-for-KoHs-Mods/tree/main/anchors-debug)
(servidor local con Grim Anticheat). Los datos en bruto están en `testlab/results/herzium-kohs-0.2.0/`
y `testlab/results/herzium-kohs-0.2.1/`.

El reporte: con KoHs Anchor's 0.2.0 y Herzium 1.10.5, el anchor PvP "se siente extraño al cargar y
explotar". El jugador tiene el clic derecho del ratón remapeado a la tecla punto, y en Minecraft
"usar" está en esa tecla.

![KoHs Anchor's con Herzium: antes y después](../images/herzium-antes-despues.png)

## Veredicto

- **La tecla punto no es la causa.** El mismo banco con "usar" en el botón derecho da exactamente
  el mismo resultado: 0 % con la 0.2.0 y 100 % con la 0.2.1.
- **La causa era el orden de la barra.** El jugador pulsa en el mismo tick "ranura, usar, otra
  ranura" (hace clic y enseguida cambia al objeto siguiente). La 0.2.0 solo reordenaba cuando
  Vanilla le daría otro objeto al uso, y dejaba al pase de la barra el caso en que la tecla
  posterior es de una ranura más baja. Herzium, en su orden por defecto (last input), aplica esa
  última tecla antes del uso, así que el clic salía con el objeto siguiente: **glowstone en vez
  del nexo y la espada en vez del glowstone**. Con la barra del jugador (espada 1, glowstone 2,
  obsidiana 3, nexos 4, tótem 5), dos de las tres transiciones caen en ese caso.
- **Herzium solo también falla.** Sin KoHs Anchor's, cada clic hace el trabajo del siguiente (0 %
  en las dos primeras pruebas). Es un fallo de Herzium y se envió a su chat con estas pruebas.
- **La 0.2.0 además apilaba nexos.** Al soltar juntos varios clics retenidos con el mismo objeto,
  colocaba un nexo encima de otro, y Grim lo marcaba (`AirLiquidPlace`).

## La configuración del jugador

Leída de su instancia de 26.2, sin modificarla:

| Qué | Valor |
| --- | --- |
| Usar | `key.keyboard.period` (el clic derecho del ratón envía ".") |
| Teclas de la barra | R, C, F, 4, M, G, 2, 8, 3 |
| Herzium | 1.10.5, `hotbarOrder: HERZIUM` (last input); el mismo jar que el release |
| KoHs Anchor's | 0.2.0, todas las opciones activadas; el mismo jar que `dist/0.2.0` |
| Barra de anchor PvP | la del kit mctiers de Crystal Tweaks: espada, glowstone, obsidiana, nexos, tótem |

Crystal Tweaks e Inventory Tweaks, también instalados, no tocan los clics de "usar" ni las teclas
de la barra (Inventory Tweaks solo actúa con la tecla de inventario).

## Cómo se midió

El banco pulsa las teclas por los manejadores de teclado y ratón de Minecraft, con "usar" en la
tecla punto como el jugador. Herzium está instalado y su orden se cambia en vivo. Cada ciclo
empieza sobre una plataforma reconstruida, para que un bloque mal colocado no arruine los ciclos
siguientes. Tres pruebas:

| Prueba | Qué hace |
| --- | --- |
| Una acción por tick | Cada tick del cliente recibe "tecla del objeto, usar, tecla del siguiente objeto" (20 ciclos). |
| Ritmo rápido | Un clic cada 35 ms y la tecla del siguiente objeto 10 ms después de cada clic, en un momento al azar del tick (30 ciclos). |
| Clics repetidos | Colocar, cargar y detonar, y tres clics más para el siguiente nexo mientras el anterior explota, con +50 ms de lag (20 ciclos). |

Por cada clic se compara la ranura con la que se aplicó con la que pide la acción (colocar con
nexos, cargar con glowstone, detonar con la espada). Además se cuenta todo bloque que el servidor
colocó fuera del sitio del nexo, los clics que el servidor procesó sin efecto y las alertas de
Grim. Lo calcula `testlab/itemcheck.py`.

## Resultados

Nexos logrados (colocados, cargados y detonados por el servidor), clics con otro objeto y bloques
mal colocados:

| Prueba | Configuración | 0.2.0 | 0.2.1 |
| --- | --- | --- | --- |
| Una acción por tick | **KoHs + Herzium last input (el jugador)** | 0 % · 40 con otro objeto · 20 glowstone mal puestos | **100 % · 0 · 0** |
| | Igual, "usar" en el botón derecho | 0 % · 40 · 20 | **100 % · 0 · 0** |
| | KoHs + Herzium en orden Vanilla | 100 % | 100 % |
| | Herzium solo (last input) | 0 % · 60 · 40 | 0 % · 60 · 40 |
| | Vanilla (Herzium en orden Vanilla, KoHs apagado) | 100 % | 100 % |
| Ritmo rápido | **KoHs + Herzium last input (el jugador)** | 76,7 % · 7 cargas con la espada | **100 % · 0 · 0** |
| | KoHs + Herzium en orden Vanilla | 63,3 % · 11 detonaciones con glowstone | **100 % · 0 · 0** |
| | Herzium solo (last input) | 0 % · 71 · 46 | 0 % · 72 · 46 |
| | Vanilla | 23,3 % · 62 · 40 | 23,3 % · 59 · 38 |
| Clics repetidos (+50 ms) | **KoHs + Herzium last input (el jugador)** | 100 % · 13 nexos apilados · 27 alertas de Grim | **100 % · 0 apilados · 0 alertas** |
| | Herzium solo (last input) | 100 % · 21 nexos apilados | 100 % · 20 nexos apilados |

En Vanilla, la detonación sale con el nexo en la mano en vez de la espada (la tecla de la espada
se pierde frente a la ranura más alta), pero un nexo cargado también explota con otro nexo en la
mano. Por eso esa fila llega al 100 % aunque cuente 20 clics con otro objeto.

![El final de la prueba de clics repetidos con la 0.2.1: 20 de 20, un solo nexo, 0 alertas](../images/lab-herzium-clics-repetidos.png)

## Qué cambió en la 0.2.1

1. **Orden de pulsación completo.** Toda ráfaga de nexo que mezcla teclas de barra y usos se
   aplica en el orden en que se pulsó, y queda seleccionada la última tecla. Solo se deja al pase
   de la barra la ráfaga cuyas teclas van todas antes de los usos y son la misma ranura, porque
   ahí cualquier orden de barra da lo mismo (Vanilla, Herzium o cualquier otro).
2. **Clics retenidos sin duplicados.** Un clic repetido con el mismo objeto sobre el nexo que
   explota se une al que ya espera. Si el servidor nunca quita ese nexo, los clics que esperaban se
   descartan en vez de caer encima.
3. **Objetivo actualizado solo si cambió el objeto.** Un segundo uso con el mismo objeto conserva
   el objetivo del primero, como en Vanilla, en vez de apuntar al nexo recién puesto.
4. **Nueva opción "Sin nexos apilados"** (activada por defecto). En juego de nexos, un segundo uso
   con el mismo objeto en el mismo tick es un doble clic y se une al primero. Hacía falta por el
   fuego que deja la explosión: el fuego es reemplazable, así que un doble clic con nexos lo
   reemplazaba con el primero y apilaba el segundo encima, también en Vanilla. Cargar con
   glowstone sobre un nexo no se limita, porque donde el nexo fija la reaparición cargar dos veces
   tiene sentido.

## Lo que corresponde a Herzium

Enviado al chat de Herzium con estas pruebas:

1. **Last input aplica una tecla pulsada después del uso.** Con "ranura A, usar, ranura B" en un
   tick, el uso sale con B. Afecta también al crystal PvP: "obsidiana, usar, cristal" coloca con
   el cristal. La propuesta es contar las pulsaciones de usar y atacar, que gane la última ranura
   pulsada antes de la primera acción pendiente, y dejar en cola para el tick siguiente las teclas
   posteriores.
2. **La vista previa se suspende si otro mod elige la ranura dentro del pase.** En orden Vanilla,
   cuando KoHs Anchor's aplica el orden de pulsación, la ranura final no coincide con la que
   Herzium predijo y Herzium suspende su vista previa en ese mundo. Solo afecta a lo visual y no
   pasa en last input, el orden del jugador.

El chat de Herzium respondió con **Herzium 1.10.6** (compilado, sin publicar), medido con el mismo
guion (`testlab/results/herzium-1.10.6-kohs-0.2.1/`):

| Prueba | Herzium 1.10.5 | Herzium 1.10.6 |
| --- | --- | --- |
| Herzium solo, una acción por tick | 0 % · 60 con otro objeto · 40 mal colocados | **100 % · 0 · 0** |
| Herzium solo, ritmo rápido | 0 % · 72 · 46 | 53 % · 14 · 0 |
| KoHs 0.2.1 + Herzium, todas las pruebas | 100 % · 0 · 0 | 100 % · 0 · 0 |
| Vista previa con KoHs en orden Vanilla | se suspende | ya no se suspende |

Lo que le queda a Herzium solo son los ticks con dos usos ("usar, glowstone, usar"): 14 pasadas en
el ritmo rápido. La tecla del medio queda para el tick siguiente y el segundo uso sale con la
ranura anterior. Taparlo exigiría cambiar de ranura dos veces en un tick, que es lo que hace KoHs
Anchor's con las ráfagas de nexo.

## Fair play

Todo sigue siendo un clic del jugador. La 0.2.1 no crea ni repite ningún clic. Los clics que deja
sin aplicar son repeticiones con el mismo objeto sobre el mismo nexo en el mismo tick, o clics
retenidos cuyo nexo nunca se fue. En Vanilla fallarían o apilarían un bloque. Con la 0.2.1, Grim
no dio ninguna alerta en ninguna prueba; con la 0.2.0 dio 27 al soltar clics repetidos.

## Límites

- La entrada es sintética. Los 35 ms y 10 ms del ritmo rápido son un modelo de jugador rápido.
- La barra se tomó del kit mctiers de Crystal Tweaks del jugador. En cada servidor puede ser otra;
  el fallo de la 0.2.0 dependía de que la tecla siguiente fuera de una ranura más baja.
- Solo se midió 26.2. Las demás versiones compilan y sus puntos de inyección se verifican contra
  el bytecode.
