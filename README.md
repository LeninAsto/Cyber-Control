# 🖥️ Cyber Control

Aplicación Android para la **gestión de cabinas en cibers / locutorios de videojuegos**.  
Permite controlar en tiempo real el estado de cada PC, manejar distintos modos de cobro, registrar ventas y configurar el negocio desde el propio dispositivo.

---

## 📋 Descripción

**Cyber Control** es un sistema de punto de venta (POS) diseñado específicamente para dueños de cibercafés o salas de videojuegos que alquilan equipos por tiempo. Con una interfaz moderna basada en **Material Design 3** y **Jetpack Compose**, el propietario puede:

- Ver el estado de todas las cabinas de un vistazo.
- Iniciar, pausar y cerrar sesiones con un solo toque.
- Cobrar por minuto transcurrido o con duraciones prepagadas fijas.
- Agregar consumibles (gaseosas, galletas, etc.) durante la sesión.
- Registrar el historial de ventas agrupado por fecha.
- Configurar zonas de precio, productos y horario de cierre.

---

## ✨ Funcionalidades principales

### 🖥️ Gestión de cabinas
- Soporte de **1 a 10+ cabinas** (numeración configurable).
- Estados visuales: **Libre**, **Ocupado**, **Pausado** y **Tiempo agotado**.
- Indicador de progreso circular animado que muestra el tiempo restante en sesiones prepagadas.
- Visualización del tiempo transcurrido, la hora de inicio y la hora estimada de fin.
- Cálculo automático del costo en tiempo real.

### 💰 Modos de cobro
| Modo | Descripción |
|------|-------------|
| **Libre** | Se cobra por el tiempo exacto utilizado (por minuto). |
| **Prepago** | El cliente paga por adelantado una duración fija (15 min, 30 min, 1 hora o personalizado). |

### 🏷️ Grupos de precio
- Permite crear distintas **zonas de precio** (ej.: cabinas VIP, estándar).
- Cada zona tiene su propia **tarifa por hora** y sus **presets de duración**.
- Zona base "Básico" como respaldo predeterminado.

### 🛒 Extras / consumibles
- Agrega productos al total de la sesión en cualquier momento.
- Productos predefinidos o artículos personalizados con nombre y precio manual.

### 💳 Cobro y cierre de sesión
- Resumen final con detalle de extras antes de cobrar.
- Métodos de pago: **Efectivo** o **Yape** (pago móvil).
- Al confirmar, la venta queda registrada automáticamente.

### 📊 Estadísticas de ventas
- Historial agrupado por fecha (las más recientes primero).
- Las ventas de **hoy** se destacan visualmente.
- Desglose por método de pago: efectivo vs. Yape.
- Muestra el rango horario de cada sesión.
- Opción para borrar el historial.

### ⚙️ Configuración
- Número de cabinas y esquema de numeración (0–9 ó 1–10).
- **Hora de cierre**: aviso automático cuando quedan menos de 60 minutos.
- Crear, editar y eliminar grupos de precio.
- Gestionar los presets de duración de cada grupo.
- Administrar el catálogo de productos disponibles.

### 🔔 Notificaciones
- Notificación push de alta prioridad cuando el tiempo prepago de una cabina se agota.

---

## 🛠️ Tecnologías utilizadas

| Categoría | Tecnología | Versión |
|-----------|-----------|---------|
| Lenguaje | Kotlin | 2.0.21 |
| UI | Jetpack Compose + Material 3 | BOM 2024.09.00 |
| Temas | Material You (color dinámico Android 12+) | — |
| Almacenamiento | SharedPreferences + JSON | — |
| Notificaciones | AndroidX NotificationCompat | — |
| Build | Gradle | 8.13 |
| SDK mínimo | Android 12 (API 32) | — |
| SDK objetivo | Android 15 (API 36) | — |
| Java bytecode | Java 11 | — |

---

## 📦 Requisitos

- **Android 12 o superior** (API nivel 32+).
- Permiso `POST_NOTIFICATIONS` para recibir alertas de tiempo agotado.

---

## 🚀 Cómo compilar y ejecutar

1. Clona el repositorio:
   ```bash
   git clone https://github.com/LeninAsto/Cyber-Control.git
   ```
2. Ábrelo en **Android Studio** (versión compatible con Kotlin 2.0 y Compose).
3. Deja que Gradle sincronice las dependencias.
4. Conecta un dispositivo con Android 12+ o usa un emulador con la misma API.
5. Pulsa **Run ▶** para instalar y ejecutar la aplicación.

> **Nota:** el archivo `local.properties` con la ruta del SDK se genera automáticamente por Android Studio y no está incluido en el repositorio.

---

## 📁 Estructura del proyecto

```
Cyber-Control/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/leninasto/cybercontrol/
│   │   │   │   ├── MainActivity.kt          # Lógica principal y toda la UI (Compose)
│   │   │   │   └── ui/theme/
│   │   │   │       ├── Color.kt             # Paleta de colores Material 3
│   │   │   │       ├── Theme.kt             # Tema dinámico (Android 12+)
│   │   │   │       └── Type.kt              # Tipografía
│   │   │   ├── res/                         # Recursos (iconos, strings, temas)
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                            # Pruebas unitarias
│   │   └── androidTest/                     # Pruebas de instrumentación
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml                   # Catálogo centralizado de dependencias
├── build.gradle.kts
├── settings.gradle.kts
└── LICENSE                                  # Apache License 2.0
```

---

## 📄 Licencia

Este proyecto está licenciado bajo la **Apache License 2.0**.  
Consulta el archivo [LICENSE](LICENSE) para más detalles.

---

> Desarrollado por **LeninAsto** 🚀
