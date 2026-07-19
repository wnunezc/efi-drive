# efi-driver — AGENTS.md

## Prioridad local

1. Instruccion literal del usuario
2. Este `AGENTS.md`
3. `D:/OpsZone/DevWorkspace/AGENTS.md`
4. Config global del agente

## Proyecto

- App Android nativa para asistencia operativa de conductores en InDrive.
- Stack principal: Kotlin, Jetpack Compose, Material3, Android AccessibilityService, WindowManager overlays.
- Package/app id actual: `com.efidriver.icarosnet`.
- App objetivo observada: `sinet.startup.inDriver`.

## Contrato operativo

- Usar PowerShell para comandos locales.
- No modificar dumps XML, imagenes, auditorias o documentos de analisis salvo pedido explicito.
- No revertir cambios no propios. El repo puede tener trabajo activo sin commit.
- Antes de cambiar `ScraperAccessibilityService.kt`, revisar el diff actual porque suele contener diagnostico o ajustes de campo.
- Mantener cambios funcionales acotados: no refactorizar UI, engine y accessibility service en la misma tarea salvo necesidad real.
- No introducir servicios externos ni dependencias nuevas sin justificar impacto en Android, permisos y privacidad.

## Arquitectura caliente

- `app/src/main/java/com/efidriver/icarosnet/services/ScraperAccessibilityService.kt`: core operativo; procesa eventos de accesibilidad, extrae viajes, oculta no rentables y sincroniza HUD.
- `app/src/main/java/com/efidriver/icarosnet/engine/ProfitabilityEngine.kt`: motor matematico puro y testeable.
- `app/src/main/java/com/efidriver/icarosnet/engine/SettingsManager.kt`: umbrales persistidos en SharedPreferences.
- `app/src/main/java/com/efidriver/icarosnet/models/Trip.kt`: modelo del viaje y fingerprint.
- `app/src/main/java/com/efidriver/icarosnet/models/TripStatus.kt`: estados y resultado de rentabilidad.
- `app/src/main/java/com/efidriver/icarosnet/ui/screens/`: pantallas Compose de permisos y configuracion.
- `app/src/main/res/xml/accessibility_service_config.xml`: contrato del AccessibilityService.

## Validacion

- Build local: `.\gradlew.bat assembleDebug`
- Tests unitarios: `.\gradlew.bat testDebugUnitTest`
- Si hay cambios en accesibilidad/HUD, validar en dispositivo fisico con InDrive abierto y revisar logs por tags `EfiHUD` y `EfiSonda`.
- No asumir que un selector de InDrive es estable sin contrastarlo con dumps recientes o prueba en dispositivo.

## Continuidad

- Contexto compacto: `D:/OpsZone/DevWorkspace/Knowledge/Obsidian-Vault/08-AI-Context/efi-driver.md`
- Reentry operativo si hay worktree sucio: `D:/OpsZone/DevWorkspace/Knowledge/Obsidian-Vault/08-AI-Context/efi-driver-reentry.md`
- Documentos locales utiles: `README.md`, `ARCHITECTURE_ANALYSIS.md`, `PROJECT_STUDY.md`, `TECHNICAL_DEBT.md`.
