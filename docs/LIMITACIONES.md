# Limitaciones técnicas

**Lo que NO se ha demostrado**
* Que el piloto funcione en un juego real. Solo se ha probado con simulación y con imágenes sintéticas.
* La capa Android no se compiló ni se ejecutó donde se desarrolló: pueden existir errores de compilación o de ejecución.

**Del método**
* **Color como señal**: un vehículo con el color del asfalto no se distingue de la carretera (en la prueba sintética, 0/32).
  Sombras, destellos, monedas, bonificaciones, humo o cambios de iluminación pueden dar falsos obstáculos.
  El clasificador LiteRT los reduciría, pero **no se incluye ningún modelo entrenado** (ver `app/src/main/assets/models/README.md`).
* **Vista fija de 3 carriles en perspectiva**. No sirve para vistas cenitales, cámaras que se mueven o curvas pronunciadas.
* La estimación de velocidad/TTC asume velocidad del mundo constante; las aceleraciones bruscas la degradan unos fotogramas.
* La superposición aparece en la captura: se coloca arriba, pero si tapa la carretera el diagnóstico lo avisa; desactívela en Ajustes.

**De Android**
* La captura exige un permiso nuevo en cada inicio (Android 14+).
* Algunos juegos ignoran gestos inyectados por accesibilidad o los detectan; los juegos con tap por mitades de pantalla
  en vez de deslizamiento no están soportados.
* Latencia total (captura + análisis + gesto + reacción del juego) típica de decenas a cientos de ms; en juegos muy rápidos puede no bastar.
* El rendimiento (15–30 análisis/s) depende del teléfono; el sistema baja solo hasta 15/s y lo muestra en el panel.

**Seguridad**
* Los gestos solo se emiten con el juego elegido en primer plano y el piloto armado; hay parada de emergencia.
* Automatizar juegos en línea puede violar sus términos de servicio.

## Optimización de reacción (v2): qué se midió y qué NO

**Qué cambió**
- Predicción temporal: velocidad del obstáculo estimada de forma bayesiana (regresión + velocidad de referencia del juego), TTC con incertidumbre (σ) y ventana de permanencia adaptativa.
- Planificación con latencia: el motor descuenta la edad real de la imagen, el retardo del gesto (aprendido en marcha) y la duración del cambio de carril; no ordena un segundo cambio mientras el juego aún está en transición.
- Seguridad: filtro del propio vehículo (detección estática pegada a la zona del jugador), veto si el carril destino se ocupa durante el trayecto, jugador localizado por su color, confirmación visual del cambio.
- Alcance: la calibración extiende automáticamente el borde lejano hasta donde la calzada continúa visible; resolución de trabajo adaptativa (160–320 px).
- Rendimiento: análisis dirigido por eventos (hasta 30/s), gesto de 60 ms por defecto.
- Diagnóstico: por obstáculo (visto hace / confirmado tras / TTC±σ) y por reacción (visto→confirmado→decisión→gesto→fin→cambio visto, tiempo total y margen).

**Resultados (SIMULACIÓN, 60 escenarios, imagen 70 ms, gesto 100 ms, cambio 250 ms, no un juego real)**
- Choques: 11 (nuevo) frente a 14 (anterior). Mejora modesta: en esta simulación el factor dominante es el alcance de visión y la edad de la imagen, no el margen de seguridad.
- Movimientos innecesarios: 0 en ambos; un parpadeo de 1 fotograma nunca provoca movimiento (prueba automática).
- Hay escenarios físicamente inevitables (dos carriles ocupados al fondo y poco tiempo); no se pueden evitar con ningún algoritmo.

**No verificado**: nada de esto se ha ejecutado en un teléfono ni en un juego real; la extensión del borde, el color del jugador y la resolución adaptativa solo se validaron con imágenes sintéticas (los tests de visión corren en CI). Las latencias reales de su dispositivo pueden diferir; el motor aprende el retardo del gesto, pero no se ha medido.

## Localización del vehículo del jugador (v3)

**Fallo corregido:** al calibrar, «Su vehículo se localiza en: no localizado». Reproducido con la captura real del usuario:
el color único aprendido (mediana Lab de la parte central de la caja) era (L=140, a=125, b=138), un tono claro del coche que cubre
solo ≈1 % de la ventana del carril; el algoritmo exigía ≥15 % y un ganador claro, así que nunca localizaba el coche. Un coche
multicolor (verde, crema, cristal oscuro, luces naranja) no se puede representar con un solo color. Las coordenadas y el
escalado entre calibración y verificación eran correctos (se comprobó). Además, la caja marcada quedaba corta por abajo (el
parachoques sobresale ≈24 % de su altura).

**Ahora:** se aprende una *apariencia* (paleta de hasta 8 colores de los píxeles que no son asfalto, área, ancho/alto, centro) y se
localiza por área + forma + paleta + posición, con continuidad temporal e histéresis. Se analiza una zona ampliada por debajo de la
caja. Un coche de otro color o de otro tamaño en un carril vecino no se acepta; un coche idéntico al lado es indistinguible por
apariencia (sin historial se declara ambiguo; con historial se mantiene el carril). El diagnóstico dibuja por carril la ventana,
lo que no es asfalto y las puntuaciones A/F/C/P, y explica en texto la condición que provocó un rechazo.

**Pruebas:** `PlayerLocatorTest` (JVM, 16 pruebas) y `PlayerLocalizationTest` (OpenCV real, en CI) usan la captura real del usuario
(limpiada de la superposición con inpainting; `tools/make_player_fixtures.py`) y el sprite real del coche pegado en otros
carriles/posiciones/escalas/ruido. Límite: son píxeles reales pero composiciones, no una partida en ejecución; si el coche del jugador
fuese del color del asfalto no se puede distinguir y la calibración lo dice en lugar de inventar un resultado.
