# Deuda Tecnica - efi-driver

Este documento registra el estado tecnico pendiente al 2026-07-23.

## Critico

### Redisenar sincronizacion lista/tarjeta

El flujo actual de tarjeta fue desconectado temporalmente con `detailCardFlowEnabled = false` en `ScraperAccessibilityService.kt`.

Motivo:

- Los overlays de lista no siempre se ocultan al abrir la tarjeta.
- La apertura/cierre de tarjeta y reconstruccion de overlays no estan sincronizados de forma confiable.
- Existen estados globales y callbacks asincronos que pueden dejar la UI desfasada.
- El comportamiento visual real no queda explicado por los logs internos actuales.

Antes de reactivar este flujo se debe definir una maquina de estados o coordinador unico del ciclo visual:

1. Lista visible.
2. Overlays de lista sincronizados.
3. Click sobre viaje valido.
4. Limpieza inmediata de overlays de lista.
5. Tarjeta visible.
6. Mapa/globos visibles.
7. ROI/OCR.
8. Overlay de tarjeta.
9. Cierre de tarjeta.
10. Re-scan real de lista actual.
11. Reconstruccion de overlays.

### Trazabilidad util

Los logs actuales miden eventos internos pero no prueban el contrato visual. Se necesita trazabilidad que capture:

- Estado visual esperado y observado.
- Timestamp de entrada/salida por estado.
- Motivo exacto de eliminacion/recreacion de overlays.
- Trabajo asincrono activo por intento.
- Cancelacion de callbacks obsoletos.
- Comparacion entre lista visible y overlays visibles.

### ROI/OCR

OCR sigue siendo lento e inestable en algunos casos:

- Puede tardar varios segundos aun cuando el mapa ya esta visible.
- Puede procesar recortes incompletos si los globos estan cubiertos.
- Puede devolver solo marcadores `A/B` o valores nulos.

La siguiente iteracion debe separar:

- deteccion visual de globos,
- estabilidad del ROI,
- preparacion del recorte,
- OCR,
- parseo de valores,
- calculo/pintado.

## Alto

### Re-scan post-cierre

Al cerrar tarjeta, la lista puede quedar visible con viajes pero sin overlays durante varios segundos. Esto debe resolverse sin depender de timers largos ni de estado viejo en memoria.

### Scroll y reordenamiento

La lista de InDrive es dinamica y escrolleable. Cuando viajes aparecen/desaparecen o cambian de posicion, los overlays deben:

- actualizar posicion rapido,
- no quedar solapados sobre viajes incorrectos,
- removerse solo con justificacion verificable.

## Medio

### Licenciamiento

Licenciamiento implementado del lado APK con identificador basado en `product + package + ANDROID_ID`. Limitacion conocida:

- No garantiza identidad permanente despues de factory reset.
- Debe mantenerse visible en Settings como identificador de dispositivo no editable.

### SDK

Estado actual:

- `minSdk = 31`.
- `targetSdk = 36`.

Se debe validar en dispositivos target:

- Redmi 13 Pro.
- Moto G24.
- Galaxy S10.
- Galaxy A06.

## Validacion

Build local actual:

```powershell
.\gradlew.bat assembleDebug
```

Instalacion limpia:

```powershell
adb uninstall com.efidriver.icarosnet
adb install .\app\build\outputs\apk\debug\app-debug.apk
```
