# efi-driver

Aplicacion Android/Kotlin para asistir a conductores de InDrive mediante `AccessibilityService`, analisis de viajes visibles y overlays de rentabilidad.

## Estado Actual

- Package/app id: `com.efidriver.icarosnet`.
- App objetivo observada: `sinet.startup.inDriver`.
- SDK actual: `minSdk=31`, `targetSdk=36`.
- El APK compila correctamente con `.\gradlew.bat assembleDebug`.
- Ultima instalacion limpia verificada: `adb uninstall com.efidriver.icarosnet` + `adb install app-debug.apk`.
- El flujo de analisis de tarjeta de viaje esta desconectado temporalmente con `detailCardFlowEnabled = false` en `ScraperAccessibilityService.kt`.

## Funcionalidad Activa

- Escaneo de lista de viajes visibles mediante Accessibility.
- Calculo de rentabilidad preview desde los datos disponibles en la lista.
- Overlays de lista para viajes visibles.
- Ocultacion programatica de viajes no rentables segun reglas configuradas.
- Sistema de licencia con validacion del servicio antes de operar.

## Funcionalidad Desconectada Temporalmente

El flujo de tarjeta de viaje queda fuera del camino de ejecucion hasta redisenar la sincronizacion:

- Deteccion de click sobre tarjeta/lista.
- Seguimiento de apertura de modal/tarjeta.
- Deteccion de mapa renderizado.
- ROI/OCR de globos de ruta.
- Recalculo real de rentabilidad desde tarjeta.
- Overlay de rentabilidad dentro de la tarjeta.

El codigo relacionado no fue eliminado; solo esta apagado para evitar que siga afectando la experiencia mientras se replantea el flujo.

## Problema Pendiente Principal

La sincronizacion entre lista, tarjeta, overlays, ROI y OCR no es confiable. Evidencia de video mostro:

- Overlays de lista que no se ocultan inmediatamente al abrir tarjeta.
- ROI/OCR condicionado por overlays de lista que a veces siguen cubriendo el mapa.
- Overlay de rentabilidad de tarjeta tardando varios segundos despues de que el mapa ya esta visible.
- Al cerrar tarjeta, la lista puede quedar varios segundos con viajes visibles pero sin overlays.

La proxima fase debe redisenar el ciclo visual completo antes de reactivar el flujo de tarjeta.

## Comandos

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
adb uninstall com.efidriver.icarosnet
adb install .\app\build\outputs\apk\debug\app-debug.apk
```
