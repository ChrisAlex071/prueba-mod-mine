# Player Clones — 2.0.0-alpha

Mod de Minecraft Java **26.2** + Fabric. Crea clones con tu skin y asígnales tareas autónomas.
Versión 2: combina la estructura de comandos/IDs/permisos de la 1.0.0-alpha con la lógica real de trabajo
(cofres, comercio, movimiento) y persistencia.

> **Alpha sin compilar.** Se escribió sin acceso al repositorio de dependencias de Gradle. Hay que compilarlo
> localmente y corregir lo que reporte el compilador (ver "Si no compila").

## Qué hace

Tarea `trade <dar> <recibir>`, por ejemplo `iron_ingot` → `emerald`:

1. Busca cofres a ≤16 bloques y toma todo el `<dar>`.
2. Busca aldeanos a ≤32 bloques que tengan esa oferta (sin segundo ingrediente y con stock) y comercia todo lo posible.
3. Guarda lo recibido en un cofre con espacio (prefiere el cofre de origen).
4. Repite. Si no hay trabajo, espera 5 s y reintenta.

Funciona en el servidor, **sin que el jugador esté cerca**: ahora el mod mantiene
cargados automaticamente los chunks que necesita cada clon (sin `/forceload` manual).

## Comandos

`/clone` y `/pclone` son idénticos. Usa `/pclone` si `/clone` te exige permisos de operador
(el `/clone` de vanilla los exige y Brigadier fusiona ambos comandos).

```mcfunction
/clone spawn            # 1 clon
/clone spawn 3          # 3 clones
/clone list
/clone 1 info
/clone 1 task trade iron_ingot emerald
/clone 2 task trade paper emerald
/clone 1 stop           # o: /clone 1 task stop
/clone 1 remove         # suelta lo que llevaba
```

Máximo 10 clones por jugador. Solo el dueño controla su clon (consola y bloques de comandos pueden con todos).
No hay override de operador: la API de permisos cambió en 26.x y preferí no depender de ella.

## Cambios respecto a 1.0.0-alpha

- Cuerpo: `Mannequin` con tu skin (antes un aldeano con IA propia que se escapaba y podía comerciar con otros clones).
- Tarea real implementada (antes solo se guardaba el texto).
- `spawn [cantidad]`.
- `/clone list` solo muestra los clones del jugador.
- **Persistencia:** ID, dueño, tarea, objetos transportados y propiedad de chunks se guardan en tags de la entidad; el clon se
  re-registra al cargar el chunk. Las IDs duplicadas tras reinicios se reasignan.
- Autocompletado de items en `task trade`.
- **Carga automática de chunks:** los clones continúan trabajando a cualquier distancia y después de reiniciar el servidor.
- Build corregido para 26.x (plugin sin remapeo, sin `mappings`, `implementation`).
- Corregidos errores de compilación: constructor de `CloneRecord` con argumentos de menos y
  `source.getCommandContext()` (no existe).

## Limitaciones conocidas

- Camina en línea recta y salta obstáculos de 1 bloque; no rodea paredes (si se atasca ~3 s descarta el objetivo).
- Solo cofres normales y trampa (no barriles); cada mitad de un cofre doble se trata por separado.
- Los clones mantienen forzados sus chunks: un clon con tarea carga una zona de **5×5 chunks**,
  para incluir cofres y aldeanos hasta 32 bloques de distancia; un clon inactivo solo carga su chunk.
- La zona cargada se mueve junto con el clon. Al eliminarlo, libera los chunks que creó este mod,
  sin soltar los utilizados por otros clones ni los forzados manualmente antes de la creación.
- La carga **persiste al reiniciar**: el mundo recuerda los chunks forzados y cada clon registra
  sus chunks administrados mediante tags. El jugador puede desconectarse y el clon continúa trabajando.
- Los clones de partidas antiguas, si estaban descargados antes de instalar esta versión,
  necesitan que visites su zona **una sola vez** para activar el sistema.
- Para reducir consumo de CPU/RAM en servidores con muchos clones, modifica
  `WORK_RADIUS_CHUNKS` en `CloneChunkLoader.java` (valor 2 = 25 chunks por clon antes de compartir).
- Usa `setChunkForced`, no los tickets temporales de 40 ticks de las perlas de Ender.
  No se desactiva cuando el dueño se desconecta.
- Si editas o eliminas entidades por herramientas externas mientras el servidor está apagado,
  podrían quedar chunks forzados huérfanos. Se pueden liberar manualmente con `/forceload remove`.

## Requisitos y compilación

- JDK 25, Minecraft 26.2, Fabric Loader ≥ 0.19.3, Fabric API `0.161.0+26.2`.
- Gradle 9.5.1 / Fabric Loom 1.17.21 (el wrapper está configurado pero faltan `gradlew` y el jar; genera con `gradle wrapper`).

```bash
gradle wrapper --gradle-version 9.5.1
./gradlew build          # Windows: gradlew.bat build
```

El jar queda en `build/libs/`.

## Si no compila

Los puntos más probables, por renombres en 26.x (la nueva logica no se ha validado con un build local):

- `Villager` / `Mannequin`: cambia el `import` (p. ej. `net.minecraft.world.entity.npc.villager.Villager`).
- `Mannequin.setProfile(ResolvableProfile)` y `ResolvableProfile.createResolved(...)`.
- `level.hasChunkAt(BlockPos)`, `Entity.onGround()`, `Entity.horizontalCollision`.
- Plugin de Loom: si `1.15.2` no resuelve, prueba `1.17-SNAPSHOT`.

Pega los errores y se ajustan.

Licencia: MIT.

## Patrulla con azada de madera (implementación experimental)

1. Asigna la tarea comercial habitual con `/pclone <id> task trade <dar> <recibir>`.
2. Clic derecho al clon con una azada de madera en la mano principal para seleccionarlo.
3. Clic derecho sobre la cara de un bloque para añadir un punto de ruta (la posición de los pies será el bloque inmediatamente encima). Repite sobre ese bloque para quitarlo.
4. Mientras mantengas la azada, verás partículas en los puntos y su número en la barra de acción cuando mires hacia ellos.
5. El clon toma insumos del cofre, recorre la ruta en ambos sentidos y comercia con aldeanos cercanos. Regresa al cofre de origen al completar el ciclo, deposita los resultados y vuelve a patrullar.
6. Clic derecho de nuevo al mismo clon para dejar de editar la ruta. Hasta 64 puntos por clon.

Las rutas se guardan en los tags de la entidad y sobreviven reinicios. La selección de edición no se guarda. El movimiento utiliza el desplazamiento directo existente, no navegación por caminos: crea rutas sin obstáculos importantes.

**Pendiente:** compilar y probar en el cliente y servidor Fabric Minecraft 26.2; este paquete contiene código fuente, no un JAR validado.
