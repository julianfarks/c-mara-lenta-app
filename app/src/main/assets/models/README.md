# Modelo LiteRT opcional

Esta carpeta está vacía a propósito: **el proyecto no incluye ningún modelo entrenado** y no se
afirma que exista uno. Sin modelo, el detector usa únicamente el análisis de color/forma sobre la
carretera (OpenCV).

Para usar uno, coloque aquí `obstacle_classifier.tflite` con este contrato:

- Entrada: `float32 [1, 64, 64, 3]`, RGB normalizado a `[0, 1]`.
- Salida: `float32 [1, 1]` con la probabilidad de que el recorte sea un obstáculo.
- Un candidato con probabilidad < 0,40 se descarta; la confianza final promedia el detector y el modelo.

El modelo solo *filtra* candidatos del detector (reduce falsos positivos: sombras, efectos, monedas);
no añade vehículos que el detector no haya propuesto.
