# FarKs AutoPilot — Android

Piloto automático **local** para juegos de carretera de 3 carriles. Captura la pantalla del juego que
usted elija, detecta obstáculos con visión por computadora, predice cuándo llegan a su carril, decide
la maniobra más segura y la ejecuta con un deslizamiento lateral. Sin root. Sin red ni claves de API.
Android 13–16 (API 33+).

> **Estado honesto del proyecto — léalo antes de usarlo**
>
> | Parte | Estado real |
> |---|---|
> | Núcleo de decisión (seguimiento, TTC con perspectiva, planificador, motor y protecciones) | **Probado**: 51 pruebas JVM, incluidos los escenarios A–J en simulación de lazo cerrado, con pruebas de control que demuestran que SIN piloto hay choques. |
> | Detector OpenCV | Algoritmo validado en Python con **imágenes sintéticas** (202/202 vehículos con color propio, 0 falsos positivos en 60 carreteras vacías). El port a Kotlin compila contra un stub de la API y trae su propia prueba, que **solo corre en CI** (necesita la librería nativa de OpenCV). |
> | Capa Android (captura, servicio, accesibilidad, superposición, pantallas) | **Escrita pero NO compilada ni ejecutada** por quien la desarrolló (el entorno no tenía SDK de Android ni acceso a Maven/Google). El primer `./gradlew assembleRelease` puede revelar errores de compilación que habrá que corregir. |
> | Funcionamiento en un juego real | **Sin probar.** No hay evidencia de que gane o sobreviva en ningún juego concreto. |
> | Modelo LiteRT | **No incluido.** Es un filtro opcional; sin modelo se usa solo color/forma. |

## Cómo funciona

```
MediaProjection ──► OpenCvObstacleDetector ──► AutopilotEngine ──► GestureAccessibilityService
 (captura 540 px)   (Lab vs. modelo de la        seguimiento + TTC      dispatchGesture, solo si el
                     carretera, por carril)       + planificador         juego elegido está al frente
                                                  + protecciones
```

* **Detección** (`vision/`): se aprende el color de la carretera vacía por franjas de profundidad en
  espacio Lab; los píxeles que se desvían de ese modelo, dentro de cada carril, forman candidatos
  (morfología + componentes conexos + filtros de tamaño/relleno). Opcionalmente un clasificador LiteRT los confirma.
* **Seguimiento y predicción** (`core/`): cada obstáculo recibe un id; la profundidad en perspectiva
  `1/(y − horizonte)` baja linealmente con el tiempo, lo que da velocidad y **tiempo hasta colisión (TTC)**.
* **Estados por carril**: LIBRE · OCUPADO · POSIBLE PELIGRO · PELIGRO INMINENTE · DESCONOCIDO.
* **Decisión**: programación dinámica sobre carril × tiempo, con coste de ocupación, margen de seguridad,
  duración del cambio de carril y penalización por movimientos innecesarios. Explica cada decisión.
* **Protecciones**: parada de emergencia, tiempo mínimo entre movimientos, un solo movimiento pendiente,
  verificación visual de que el cambio ocurrió, suspensión si se pierde la captura o las imágenes se
  quedan viejas, gestos solo con el juego elegido en primer plano.

## Estructura

```
core/     Lógica pura (JVM): models, calibration, tracking, prediction, decision, automation, engine, diagnostics
vision/   Detector OpenCV + modelo de color (JVM; compila contra la API Java de OpenCV)
app/      Android: ui, overlay, capture, detection (LiteRT), automation (accesibilidad), service, settings, calibration, diagnostics
tools/    prototype_detector.py (validación del algoritmo) y local-core-test.sh (pruebas del núcleo sin red)
docs/     Instalación, calibración y limitaciones
.github/  CI: pruebas + APK + publicación en Releases
```

## Compilar

```bash
./gradlew :core:test :vision:test :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

El flujo `.github/workflows/build.yml` hace lo mismo en GitHub Actions y publica el APK en
*Releases* (`latest-build`). Sin red, solo el núcleo: `bash tools/local-core-test.sh`.

## Documentación

* [Instalación y permisos](docs/INSTALACION.md)
* [Manual de calibración](docs/CALIBRACION.md)
* [Limitaciones técnicas](docs/LIMITACIONES.md)

## Uso responsable

Automatizar el juego puede incumplir los términos de servicio de juegos en línea o competitivos y puede
provocar la suspensión de la cuenta. Úselo solo en juegos y modos donde sea aceptable.
