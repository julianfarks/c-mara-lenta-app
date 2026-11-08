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
