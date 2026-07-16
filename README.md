# Efi-Driver: El HUD Inteligente para Conductores de Élite

Efi-Driver es una solución de software avanzada diseñada para optimizar la rentabilidad de conductores en plataformas de movilidad (actualmente optimizado para InDrive). El sistema actúa como un copiloto digital que analiza, filtra y resalta oportunidades de viaje en milisegundos mediante técnicas de intercepción de interfaz y procesamiento matemático de datos.

## 🚀 Logros y Funcionalidades Clave

### 1. Motor de Rentabilidad Real-Time
Implementación de un algoritmo determinista que transforma los datos brutos de la plataforma en métricas de ganancia neta:
*   **Cálculo de Ingreso Real:** Deducción automática de comisiones (15% por defecto).
*   **Filtro "N" de Distancia:** Regla de oro inquebrantable para descartar recogidas lejanas.
*   **Métrica USD/km:** Evaluación de la calidad del pago basada en la distancia total (recogida + viaje base).

### 2. HUD (Heads-Up Display) Dinámico
Una capa visual revolucionaria que "tiñe" la realidad de la aplicación objetivo:
*   **Overlay Persistente:** Recuadros verdes inteligentes que cubren el 40% derecho de cada viaje rentable.
*   **Seguimiento de Objetos:** El overlay "persigue" al viaje por la pantalla mientras este se desplaza o reordena.
*   **Identificación por Huella Digital:** Uso de la "Llave Maestra" (Nombre + Origen) para mantener la identidad del viaje a pesar de cambios visuales.

### 3. Intervención Silenciosa y Invisible
Mecanismos de automatización que limpian la interfaz del conductor sin interferir con su flujo de trabajo:
*   **Ocultación Programática:** Ejecución de comandos `performAction` a nivel de procesador para borrar viajes no rentables.
*   **Estrategia Anti-Parpadeo:** Gestión de estados mediante Flags de visibilidad para asegurar estabilidad visual.
*   **Extractor Blindado:** Lectura robusta de datos capaz de interpretar comas decimales, símbolos de moneda y conversiones automáticas de metros a kilómetros.

### 4. Panel de Control Ágil
Interfaz moderna construida en Jetpack Compose que permite al conductor ajustar sus umbrales de éxito sobre la marcha, con sincronización instantánea hacia el motor de scraping.

## 🛠 Tecnología y Estrategias Utilizadas

*   **Lenguaje:** Kotlin (Modern Android Development).
*   **UI Framework:** Jetpack Compose (Configuración dinámica y reactiva).
*   **Core Engine:** Android Accessibility Service (Scraping de bajo nivel y automatización).
*   **Visual Engine:** WindowManager Overlay System (Inyección de UI sobre apps de terceros).
*   **Data Strategy:** 
    *   *Master Key Fingerprinting:* Algoritmo de normalización de strings para identificación única de nodos volátiles.
    *   *Event-Driven Architecture:* El sistema reacciona a cambios de micro-milisegundos en el árbol de nodos del sistema.

---
**Efi-Driver: Donde la eficiencia se encuentra con la conducción.**
