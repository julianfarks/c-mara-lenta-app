# Instalación y permisos

## 1. Instalar el APK
1. Descargue `app-release.apk` desde *Releases* (o compílelo, ver README).
2. Ábralo y permita «instalar apps desconocidas» para el navegador/gestor de archivos si se lo pide.
3. El APK se firma con la clave de depuración salvo que el CI tenga un keystore; para actualizar
   encima de una instalación previa debe ser la misma firma.

## 2. Permisos (la pantalla principal muestra el estado de cada uno)
| Permiso | Para qué | Cómo |
|---|---|---|
| Superposición | Panel flotante con el botón de parada y para abrir la calibración desde segundo plano | Botón «Conceder» → active FarKs AutoPilot |
| Notificaciones | Notificación obligatoria del servicio en primer plano (con botón Detener) | Diálogo del sistema |
| Accesibilidad | Deslizar el dedo y saber qué app está al frente | Botón «Abrir» (ver abajo) |
| Captura de pantalla | Ver el juego | Diálogo del sistema **cada vez que pulsa INICIAR** (Android 14+ solo concede un uso) |

### Accesibilidad con «ajuste restringido»
Si instaló el APK fuera de Play Store, Android 13+ deja gris el interruptor. Solución:
**Ajustes → Aplicaciones → FarKs AutoPilot → menú ⋮ → «Permitir ajustes restringidos»**, y vuelva a
Accesibilidad → «FarKs AutoPilot (gestos)» → Activar.

El servicio solo emite deslizamientos mientras el juego elegido está en primer plano y no lee el contenido de las pantallas.

### Ahorro de batería
Si el sistema mata el servicio en segundo plano, ponga la app en «Sin restricciones» en Ajustes → Batería.

## 3. Primer uso
1. **Elegir juego.**
2. **Calibrar el juego** (ver `CALIBRACION.md`).
3. **INICIAR** → concede la captura → se abre el juego → el panel flotante muestra el estado.
4. Use **■ PARAR** del panel o **DETENER** de la app para parar de inmediato.
