# Estudio del Proyecto: efi-driver

Este documento detalla el análisis del escenario, el comportamiento de la aplicación objetivo (InDrive) y la lógica de negocio acordada.

## 1. Comportamiento de InDrive (Observaciones de Campo)
*   **Prioridad Dinámica:** La lista de viajes se reordena en tiempo real. Los viajes con menor distancia de recogida (`pickupDistanceKm`) se inyectan en la parte superior, desplazando los existentes hacia abajo.
*   **Volatilidad:** Un viaje puede aparecer, ser retirado por el sistema o el cliente, y volver a aparecer si el cliente realiza un cambio mínimo.
*   **Animaciones de Inyección:** La inserción de nuevos registros puede incluir animaciones que afectan la disponibilidad inmediata de los nodos para el `AccessibilityService`.

## 2. Identificación Única (La Llave Maestra)
Para evitar procesar dos veces el mismo viaje o perder su rastro al moverse, se define una **Huella Digital (Fingerprint)** compuesta por:
`[NombrePasajero] + [Precio] + [DistanciaRecogida] + [Origen] + [Destino]`
*   Esta llave debe ser persistente en la sesión actual para rastrear qué viajes ya han sido marcados como "Ocultar".

## 3. Algoritmo de Rentabilidad (Módulo Autónomo)

### Variables de Entrada
| Variable | Descripción |
| :--- | :--- |
| `tripPrice` | Precio mostrado en la app (USD). |
| `pickupDistanceKm` | Distancia desde la ubicación actual al pasajero. |
| `tripDistanceKm` | Distancia del viaje (Destino). *Nota: En la lista principal se usará un valor base (ej. 1km) para cálculo previo.* |
| `maxPickupDistanceKm` | Límite de recogida configurado por el usuario. |
| `minUsdPerKm` | Rentabilidad mínima deseada (USD/km). |
| `commissionPercent` | Comisión de la plataforma (ej. 15%). |

### Fórmulas Matemáticas
1.  **Ingreso Real:** `expectedIncome = tripPrice * (1 - commissionPercent / 100)`
2.  **Validación de Recogida:** `pickupAccepted = pickupDistanceKm <= maxPickupDistanceKm`
    *   Si es `false` -> Estado: `NOT_RENTABLE_PICKUP`
3.  **Distancia Total:** `totalDistanceKm = pickupDistanceKm + tripDistanceKm`
4.  **Rentabilidad Real:** `expectedUsdPerKm = expectedIncome / totalDistanceKm`

### Estados de Salida
*   `RENTABLE`: Cumple recogida y rentabilidad mínima.
*   `NOT_RENTABLE`: Recogida válida, pero pago insuficiente por km.
*   `NOT_RENTABLE_PICKUP`: El pasajero está demasiado lejos.

## 4. Estrategia de Intervención Silenciosa
*   Se utilizará `AccessibilityNodeInfo.performAction(ACTION_CLICK)` sobre los nodos internos.
*   Se busca ejecutar la cadena `Dots -> Ocultar` a nivel de procesador para evitar el renderizado del menú visual.
*   **Objetivo:** El conductor solo ve aparecer viajes rentables; los demás se esfuman antes de ser percibidos.
