# Deuda Técnica - efi-driver

Este documento registra las tareas pendientes y compromisos técnicos asumidos durante el desarrollo.

## Pendientes de Configuración
* **Gradle Sync:** Se requiere realizar un sync manual para validar las nuevas dependencias y archivos de recursos creados.
* **Compatibilidad de SDK:** El proyecto está configurado con `compileSdk` y `targetSdk` 36 (Android 15 DP/Preview). Es necesario validar la estabilidad en dispositivos comerciales actuales.

## Pendientes de Implementación
* **Lógica de Scraping:** El `ScraperAccessibilityService` actualmente solo detecta el evento pero no extrae datos específicos de los nodos de la interfaz de InDrive.
* **Manejo de Ciclo de Vida:** La re-validación de permisos en `MainActivity` mediante `LifecycleEventObserver` es funcional pero podría optimizarse con un `StateFlow` en un ViewModel.
* **Iconografía:** Se ha implementado un `VectorDrawable` básico. Podría requerir versiones adaptativas (Adaptive Icons) para mejor integración con launchers modernos.
* **Seguridad:** El permiso de Accesibilidad es altamente sensible. Se debe documentar claramente el uso de datos para cumplir con las políticas de Google Play.
