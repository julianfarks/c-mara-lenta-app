# Manual de calibración

Cada juego necesita su propio perfil (geometría de la carretera, posición de su vehículo y color de la carretera).

## Pasos
1. En la app: elija el juego y pulse **Calibrar el juego**. Conceda la captura.
2. Se abre el juego con un panel «Capturar referencia». Deje la partida en un punto con la
   **carretera lo más vacía posible** y su vehículo visible, y pulse el botón.
3. Se abre el asistente con la captura. Toque, en este orden:
   1. borde IZQUIERDO de la carretera, parte LEJANA (donde empieza a verse la calzada)
   2. borde DERECHO, parte LEJANA
   3. borde IZQUIERDO, parte CERCANA (abajo)
   4. borde DERECHO, parte CERCANA
   5. esquina superior-izquierda de su vehículo
   6. esquina inferior-derecha de su vehículo
   Los tres carriles se dibujan como tercios del ancho. Si no coinciden con las líneas del juego, reinicie los toques.
4. **Aprender y verificar**: aprende el color y ejecuta el detector real sobre la propia captura.
   * *Obstáculos detectados* debería ser **0** si la carretera estaba vacía.
   * *Su vehículo se localiza en* debería coincidir con el carril marcado.
   Si algo no cuadra aparece en ámbar; repita con una captura más limpia o toques más precisos.
5. **Guardar calibración**.

## Después de calibrar
* Abra **Diagnóstico** con el piloto en marcha: verá detecciones (amarillo), seguimientos (verde/naranja con TTC),
  estado de cada carril y el motivo de cada decisión.
* **Ajustes**: la *distancia del deslizamiento* es lo primero que hay que afinar (que un gesto = un cambio de carril).
  Mida cuánto tarda su vehículo en cambiar de carril y ajuste *duración del cambio de carril*.

## Cuándo recalibrar
Cambio de escenario/iluminación/skin de carretera, otra resolución u orientación, o muchos falsos obstáculos.
