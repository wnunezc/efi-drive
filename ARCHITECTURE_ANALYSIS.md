# Análisis de Arquitectura — efi-driver (Reverse Engineering)

> **Generado:** 2025-07-17  
> **Proyecto:** `D:\OpsZone\DevWorkspace\Projects\Mobile\efi-driver`  
> **Stack:** Kotlin + Jetpack Compose + AccessibilityService + WindowManager Overlay  
> **Target:** Android 15 (API 36) — App objetivo: **inDriver** (`sinet.startup.inDriver`)

---

## 1. Resumen Ejecutivo

**efi-driver** es un **HUD inteligente (Heads-Up Display)** para conductores de InDrive. Intercepta la UI de la app nativa mediante `AccessibilityService`, extrae datos de cada oferta de viaje, ejecuta un **motor de rentabilidad matemático** en tiempo real, y pinta **overlays visuales (HUD)** sobre los viajes rentables mientras **oculta programáticamente** los no rentables — todo en milisegundos, sin intervención del conductor.

**Arquitectura:** Event-driven + Overlay injection + Fingerprint-based tracking  
**Punto de entrada:** `ScraperAccessibilityService` (AccessibilityService)  
**UI Config:** Jetpack Compose (`MainActivity` → `PermissionsScreen` → `ConfigScreen`)  
**Persistencia:** `SharedPreferences` via `SettingsManager`  
**Motor:** `ProfitabilityEngine` (pure Kotlin, sin deps Android)

---

## 2. Estructura de Módulos y Archivos

```
app/src/main/java/com/efidriver/icarosnet/
├── engine/
│   ├── ProfitabilityEngine.kt    # Motor matemático puro (0 deps Android)
│   └── SettingsManager.kt        # SharedPreferences wrapper
├── models/
│   ├── Trip.kt                   # Data class + fingerprint
│   ├── TripStatus.kt             # Enum + ProfitabilityResult
├── services/
│   └── ScraperAccessibilityService.kt  # CORE: AccessibilityService + Overlay + Scraping
├── ui/
│   ├── screens/
│   │   ├── ConfigScreen.kt       # Panel de control (Compose)
│   │   └── PermissionsScreen.kt  # Pantalla de permisos (Overlay + A11y)
│   └── theme/
│       ├── Color.kt, Theme.kt, Type.kt
├── MainActivity.kt               # Entry point + permission gating
```

**Recursos clave:**
- `AndroidManifest.xml` → `SYSTEM_ALERT_WINDOW` + `BIND_ACCESSIBILITY_SERVICE`
- `xml/accessibility_service_config.xml` → `typeWindowStateChanged | typeWindowContentChanged`, `canRetrieveWindowContent=true`

---

## 3. Flujo de Datos — End-to-End

```mermaid
flowchart TD
    A[InDrive App] -->|AccessibilityEvent| B(ScraperAccessibilityService.onAccessibilityEvent)
    B --> C{packageName == inDriver?}
    C -->|Sí| D[processMainFlow()]
    C -->|No| E[clearAllOverlays()]
    D --> F[rootInActiveWindow.findViewById(item_order_container)]
    F --> G[Para cada nodo: extractTripData()]
    G --> H[Trip fingerprint = nombre_origen]
    H --> I[ProfitabilityEngine.calculate()]
    I --> J{status == RENTABLE?}
    J -->|Sí| K[syncOverlay() → WindowManager addView/updateViewLayout]
    J -->|No + pickup>0.05| L[executeDirectHide() → performAction(CLICK)]
    K --> M[activeOverlays[fp] = OverlayRecord]
    L --> M
    M --> N[Cleanup: remove overlays whose keys not in current scan]
```

**Ciclo de vida del overlay:**
1. **Creación** → `syncOverlay()` crea `LinearLayout` + `WindowManager.addView()` con `TYPE_ACCESSIBILITY_OVERLAY`
2. **Actualización** → Mismo `tripKey` → `updateViewLayout()` si bounds cambiaron + `updateHUDText()`
3. **Limpieza** → Keys no presentes en escaneo actual → `removeOverlay()` → `removeView()`

---

## 4. Modelos de Dominio

### `Trip.kt` (L24)
```kotlin
data class Trip(
    val passengerName: String,
    val price: Double,
    val pickupDistance: Double,
    val fromAddress: String,
    val toAddress: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val fingerprint: String by lazy {
        "${passengerName}_${fromAddress}".replace("\\s".toRegex(), "")
    }
}
```
- **Fingerprint = "Llave Maestra"**: Normaliza `NombrePasajero + Origen` sin espacios. Permite rastrear el mismo viaje aunque la lista se reordene.

### `TripStatus.kt` (L9-18)
```kotlin
enum class TripStatus { RENTABLE, NOT_RENTABLE, NOT_RENTABLE_PICKUP }

data class ProfitabilityResult(
    val status: TripStatus,
    val expectedIncome: Double,      // Neto tras comisión
    val expectedUsdPerKm: Double,    // $/km
    val totalDistanceKm: Double,     // pickup + trip
    val trueProfit: Double,          // GANANCIA REAL = Neto - (DistTotal * minUsdKm)
    val pickupDistanceKm: Double,
    val isPreview: Boolean,          // true = cálculo estimado (lista), false = detalle
    val pickupAccepted: Boolean,
    val tripAccepted: Boolean
)
```

---

## 5. Motor de Rentabilidad — `ProfitabilityEngine.kt`

**Pure function** — cero dependencias Android, 100% testable.

```kotlin
fun calculate(
    tripPrice: Double,
    pickupDistanceKm: Double,
    tripDistanceKm: Double = 1.0,   // DEFAULT: 1km base (lista principal)
    maxPickupDistanceKm: Double,
    minUsdPerKm: Double,
    commissionPercent: Double
): ProfitabilityResult
```

**Fórmulas (L19-40):**
| Paso | Fórmula | Descripción |
|------|---------|-------------|
| 1 | `expectedIncome = tripPrice * (1 - commission/100)` | Neto tras comisión |
| 2 | `totalDistanceKm = pickup + tripDistance` | Distancia total |
| 3 | `expectedUsdPerKm = round(expectedIncome / totalDistance * 100) / 100` | $/km redondeado 2 dec |
| 4 | `operatingCost = totalDistanceKm * minUsdPerKm` | Costo operativo mínimo |
| 5 | `trueProfit = round((expectedIncome - operatingCost) * 100) / 100` | **GANANCIA REAL** |
| 6 | `pickupAccepted = pickup <= maxPickup` | Filtro distancia |
| 7 | `tripAcceptedByPrice = expectedUsdPerKm >= minUsdPerKm` | Filtro rentabilidad |
| 8 | `status = when { !pickupAccepted → NOT_RENTABLE_PICKUP; !tripAcceptedByPrice → NOT_RENTABLE; else → RENTABLE }` | Estado final |

> **Nota:** `tripDistanceKm = 1.0` por defecto porque en la lista principal no se conoce la distancia destino. Es un **cálculo preview/conservador**.

---

## 6. SettingsManager — Persistencia

```kotlin
class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("efi_prefs", MODE_PRIVATE)
    
    var maxPickupDistance: Double   // default 2.5 km
    var minUsdPerKm: Double         // default 0.80 $/km
    var commissionPercent: Double   // default 15%
}
```
- **Storage:** `SharedPreferences` (key-value simple, sin Room)
- **Redondeo:** Helper `roundToTwo(Float)` → `Double` con 2 decimales
- **Inyección:** Instanciado en `ConfigScreen` via `remember { SettingsManager(context) }` y en `ScraperAccessibilityService.onServiceConnected()`

---

## 7. ScraperAccessibilityService — Núcleo del Sistema

### 7.1 Configuración del Servicio (`accessibility_service_config.xml`)
```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100" />
```
- **Eventos:** Cambios de ventana + cambios de contenido
- **Flags críticos:** `flagRetrieveInteractiveWindows` + `flagIncludeNotImportantViews` + `canRetrieveWindowContent=true` → acceso completo al árbol de nodos

### 7.2 Flujo Principal — `processMainFlow()` (L71-106)

```kotlin
private fun processMainFlow() {
    val rootNode = rootInActiveWindow ?: return
    // Selector hardcodeado de InDriver (v7.2)
    val nodes = rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container")
    
    for (node in nodes) {
        val trip = extractTripData(node) ?: continue
        val tripKey = trip.fingerprint
        foundKeysInThisScan.add(tripKey)

        val result = ProfitabilityEngine.calculate(...)
        
        if (result.status != TripStatus.RENTABLE) {
            if (trip.pickupDistance > 0.05) executeDirectHide(node)  // Ocultar no rentables
        } else {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            syncOverlay(tripKey, bounds, result)  // Pintar HUD
        }
    }
    // Limpieza: keys que ya no aparecen → removeOverlay()
}
```

### 7.3 Extracción de Datos — `extractTripData()` (L265-273)

```kotlin
private fun extractTripData(node: AccessibilityNodeInfo): Trip? {
    val name = node.findViewById("driver_common_textview_name")?.text ?: return null
    val priceT = node.findViewById("info_textview_stage_price_view")?.text ?: ""
    val distT = node.findViewById("order_info_stage_textview_distance")?.text ?: ""
    val from = node.findViewById("order_info_textview_from_address")?.text ?: ""
    return Trip(name.trim(), parseDoubleSafe(priceT), parseDoubleSafe(distT), from.trim(), "")
}
```
**Selectores hardcodeados (frágiles):**
| Campo | View ID (inDriver) |
|-------|-------------------|
| Nombre pasajero | `driver_common_textview_name` |
| Precio | `info_textview_stage_price_view` |
| Distancia recogida | `order_info_stage_textview_distance` |
| Origen | `order_info_textview_from_address` |

### 7.4 Parsing Robusto — `parseDoubleSafe()` (L275-280)
```kotlin
private fun parseDoubleSafe(text: String): Double {
    val isMetro = text.contains("metro", ignoreCase = true)
    val cleaned = text.replace(",", ".").replace(Regex("[^0-9.]"), "")
    val value = cleaned.toDoubleOrNull() ?: 0.0
    return if (isMetro) value / 1000.0 else value  // metros → km
}
```
- Maneja: comas decimales, símbolos moneda, texto "metro"/"km", basura Unicode

### 7.5 Ocultación Silenciosa — `executeDirectHide()` (L255-263)
```kotlin
private fun executeDirectHide(node: AccessibilityNodeInfo) {
    val hideBtn = node.findViewById("item_order_options_container_hide").firstOrNull()
    if (hideBtn != null) hideBtn.performAction(ACTION_CLICK)
    else node.findViewById("item_order_imageview_dots").firstOrNull()?.performAction(ACTION_CLICK)
}
```
- **Estrategia:** Click directo en botón "Ocultar" o menú "⋮" → "Ocultar"
- **Nivel procesador:** `AccessibilityNodeInfo.performAction` → no renderiza menú visual → **anti-parpadeo**

### 7.6 Overlay HUD — `syncOverlay()` + `updateHUDText()` (L149-235)

```kotlin
// Layout params: TYPE_ACCESSIBILITY_OVERLAY (sobre todo, sin foco, sin touch)
val params = WindowManager.LayoutParams(
    width = (bounds.width() * 0.4).toInt(),   // 40% ancho derecho
    height = bounds.height(),
    type = TYPE_ACCESSIBILITY_OVERLAY,
    flags = NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS,
    format = TRANSLUCENT
).apply { gravity = TOP | START; x = bounds.right - width; y = bounds.top }
```

**Contenido visual (verde oscuro `#E6004D00`):**
```
┌─────────────────┐
│    RENTABLE     │  ← 14sp Bold Blanco
│   1.25 $/km     │  ← 12sp
│   Gana: $3.45   │  ← 12sp
│ 1.2 km | ● Total: 5.5? km  │  ← 10sp + círculo naranja
└─────────────────┘
```
- **Tracking por fingerprint:** `activeOverlays: MutableMap<String, OverlayRecord>`
- **Posición:** `updateViewLayout()` si `bounds` cambió (viaje se movió en lista)
- **Limpieza:** `clearAllOverlays()` al salir de InDrive

### 7.7 Sonda Estructural — `ejecutarSondaEstructural()` (L109-147)
```kotlin
// Busca nodos cuyo ID termine en "_PointB" (ancla destino)
private fun recorrerNodosPorEstructura(node: AccessibilityNodeInfo?) {
    if (node == null) return
    if (node.viewIdResourceName?.endsWith("_PointB") == true) {
        Log.e(TAG_SONDA, "ANCLA DETECTADA: $viewId")
        // Inspecciona HERMANOS del padre → busca globo de texto con distancia destino
        val parent = node.parent
        parent?.children?.forEach { sibling ->
            Log.e(TAG_SONDA, "DATO ENTORNO -> Txt: '${sibling.text}' | Desc: '${sibling.contentDescription}' | Class: ${sibling.className}")
        }
    }
    node.children.forEach { recorrerNodosPorEstructura(it) }
}
```
- **Propósito:** Reverse engineering en producción — descubre estructura de nodos sin saber selectores exactos
- **Aislada:** `try/catch` vacío, no afecta flujo principal
- **Trigger:** `TYPE_WINDOW_CONTENT_CHANGED` | `TYPE_WINDOW_STATE_CHANGED`

---

## 8. UI de Configuración — Jetpack Compose

### `MainActivity.kt` — Gatekeeper de Permisos
```kotlin
var permissionsGranted = remember {
    Settings.canDrawOverlays(this) && 
    isAccessibilityServiceEnabled(this, ScraperAccessibilityService::class.java)
}
// Re-check en ON_RESUME via LifecycleEventObserver
```
- **Pantalla 1:** `PermissionsScreen` → solicita `SYSTEM_ALERT_WINDOW` + `AccessibilityService`
- **Pantalla 2:** `ConfigScreen` → Panel de control

### `ConfigScreen.kt` — 3 Inputs Numéricos
| Campo | Key SharedPrefs | Default | Validación |
|-------|-----------------|---------|------------|
| Distancia Máx. Recogida (km) | `max_pickup` | 2.5 | `toDoubleOrNull()` + replace `,`→`.` |
| Rentabilidad Mín ($/km) | `min_usd_km` | 0.80 | idem |
| Comisión Plataforma (%) | `commission` | 15.0 | idem |

- **Reactividad:** `mutableStateOf` + callback → `settingsManager.prop = value` (escribe SP inmediato)
- **Sync instantáneo:** `ScraperAccessibilityService` lee `SettingsManager` en cada `processMainFlow()`

### `PermissionsScreen.kt` — Comprobación Activa
```kotlin
// LifecycleEventObserver en ON_RESUME
hasOverlayPermission = Settings.canDrawOverlays(context)
hasAccessibilityPermission = isAccessibilityServiceEnabled(context, ScraperAccessibilityService::class.java)
// Auto-navega a ConfigScreen cuando ambos true
```

---

## 9. Puntos Críticos y Deuda Técnica

| Área | Riesgo | Mitigación / Pendiente |
|------|--------|------------------------|
| **Selectores hardcodeados** | InDriver actualiza UI → rompe scraping | Sonda estructural (`_PointB`) para descubrimiento automático |
| **Fingerprint frágil** | `nombre_origen` puede colisionar | Añadir precio + distancia recogida al fingerprint |
| **tripDistanceKm = 1.0** | Cálculo preview impreciso | Fase 2: navegar a detalle → leer distancia real |
| **SharedPreferences** | No thread-safe para escrituras concurrentes | Usar `DataStore` o `Mutex` |
| **Overlay TYPE_ACCESSIBILITY_OVERLAY** | Requiere Android 8+ + permiso especial | Verificar compatibilidad dispositivos objetivo |
| **No tests unitarios** | `ProfitabilityEngine` sin cobertura | Añadir `junit` tests para motor matemático |
| **Target SDK 36 (Android 15 DP)** | Inestable en dispositivos comerciales | Bajar a 34/35 para producción |

---

## 10. Decisiones Arquitectónicas Clave

| Decisión | Justificación |
|----------|---------------|
| **AccessibilityService + WindowManager** | Única forma de leer UI de app tercera + dibujar encima sin root |
| **Fingerprint = nombre + origen** | Estable ante reordenamiento; no requiere ID interno de InDriver |
| **Motor puro (sin Context)** | Testable, portable, separa lógica de plataforma |
| **Overlay 40% ancho derecho** | No tapa info crítica (precio, origen, destino) |
| **Ocultación via performAction(CLICK)** | Nivel procesador → sin menú visual → UX limpio |
| **Sonda estructural pasiva** | Descubrimiento continuo de selectores sin romper producción |
| **Compose solo para config** | Overhead mínimo; servicio corre en proceso separado |

---

## 11. Próximos Pasos Recomendados

1. **Tests unitarios** para `ProfitabilityEngine` (casos borde: distancia 0, comisión 100%, metros vs km)
2. **Migración a DataStore** (Flow + coroutines + thread-safety)
3. **Fingerprint v2:** `hash(nombre + origen + precio + pickupKm)` → menos colisiones
4. **Fase detalle:** Click en viaje → leer `tripDistanceKm` real → recalcular `trueProfit` exacto
5. **Selectores configurables:** Mover IDs a JSON/Remote Config para hot-fix sin release
6. **Hardening overlay:** `FLAG_WATCH_OUTSIDE_TOUCH` + listener para auto-hide al tocar fuera
7. **Logging estructurado:** Timber + tags por módulo → observabilidad en producción

---

## 12. Métricas de Código

| Archivo | Líneas | Complejidad Ciclomática (est.) | Responsabilidad |
|---------|--------|--------------------------------|-----------------|
| `ScraperAccessibilityService.kt` | 288 | Alta (~18) | **Core** — Scraping + Overlay + Hiding + Sonda |
| `ProfitabilityEngine.kt` | 54 | Baja (~4) | **Domain** — Matemáticas puras |
| `SettingsManager.kt` | 25 | Baja (~2) | **Infra** — Persistencia |
| `Trip.kt` / `TripStatus.kt` | 43 | Mínima | **Domain** — Modelos |
| `ConfigScreen.kt` | 106 | Media (~6) | **UI** — Configuración |
| `PermissionsScreen.kt` | 155 | Media (~7) | **UI** — Permisos |
| `MainActivity.kt` | 61 | Baja (~3) | **Entry** — Gatekeeper |

**Total Kotlin source:** ~730 líneas (excluyendo tests y tema)

---

> **Conclusión:** efi-driver es un sistema **event-driven de alta frecuencia** que combina ingeniería inversa (AccessibilityService), matemáticas de negocio deterministas y manipulación de UI a nivel de sistema. La arquitectura separa limpiamente el **dominio** (`ProfitabilityEngine`, `Trip`) de la **infraestructura Android** (`ScraperAccessibilityService`, `SettingsManager`), permitiendo testear la lógica crítica sin dispositivo. La deuda técnica principal radica en la fragilidad de selectores hardcodeados y la ausencia de tests automatizados.