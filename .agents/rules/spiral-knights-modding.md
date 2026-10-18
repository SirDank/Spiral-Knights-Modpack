---
trigger: always_on
description: Conventions and runtime constraints for Spiral Knights client modding and optimization.
---

# Spiral Knights Modding & Runtime Conventions

## Runtime Environment
- **JVM**: Java 25 LTS (64-bit HotSpot VM). Compile mods targeting Java 25 bytecode (`-source 25 -target 25`).
- **Engine**: Three Rings Clyde (`com.threerings.clyde`, `com.threerings.opengl`, `com.threerings.openal`) + Tudey (`com.threerings.tudey`).
- **Windowing/OpenGL**: LWJGL 3 (GLFW).
- **Installed Directory**: Dynamically discovered via `scripts/sk_paths.py` (checks `$SK_GAME_DIR`, Steam registry, VDF library folders, and drive roots; e.g. `<SteamLibrary>\steamapps\common\Spiral Knights`).
- **Current Version (`pxVersion`)**: `20261006114613`. All `mod.json` manifests for class mods must match the target `pxVersion`.

## Mod Packaging Standards
- Mods are zip archives loaded via KnightLauncher (`mods/<name>-class.zip`). Class mods must use `-class.zip` suffix to ensure proper hash tracking and mount order.
- Every mod zip must contain `mod.json` at root:
  - Must be encoded as pure ASCII or raw UTF-8 **without BOM** (byte 0 must be `{` / `0x7B`).
  - Must wrap all metadata in a root `"mod"` object:
    ```json
    {
      "mod": {
        "name": "<Mod Name>",
        "description": "<Description>",
        "author": "<Author>",
        "version": "1.0.0",
        "type": "class",
        "pxVersion": "20261006114613"
      }
    }
    ```
  - Without the `"mod"` root object, KnightLauncher's `Mod.java` throws `JSONObject["mod"] not found` and silently downgrades the mod to an untyped resource mod, extracting `.class` files to `rsrc/` where the JVM ignores them.
  - `"type": "class"` extracts all files into `code/class-changes/` &rarr; `code/config.jar` (first item on classpath).
  - Custom assets bundled with class mods must be read via `ClassLoader.getResourceAsStream(...)` or `Class.getResourceAsStream(...)` from `config.jar`, which makes them completely immune to KnightLauncher `extractSafeguard()` disk wipes.
  - Mod cover icons can be placed at the root of the archive as `mod.png`.
  - Mod configuration data belongs in `mods/config/<mod-id>/config.properties`.

## Chat Feedback & String Protocols
- Custom slash commands hook into `com/threerings/crowd/chat/client/b.class` or `META-INF/sk-commands/`:
  - Call `o("chat", msg)` for cyan/blue system feedback (`(byte) 1`).
  - Prepend `"~"` to literal strings to avoid `MessageBundle` translation key lookup failures and argument splitting on `|`.

## Gameplay & HUD Invariants
- Quickbar item activation requires calling `QuickbarPanel.Slot.UF()` (not `Fk()`, which only refreshes the visual icon).
- HUD Map and Minimap reveal: `com.threerings.projectx.client.hud.A` controls map discovery opacity in `a(Coord)` and discovery bounds in `Kc()`.
- **Anti-AFK & Idle Invariants**:
  - Idle state and disconnect timeout are managed by `com.threerings.projectx.client.ProjectXApp$d` (implements `com.threerings.opengl.gui.event.e`, scheduled every 10 seconds via `schedule(10000L, true)`).
  - Field `alB` stores the timestamp of the last dispatched input event.
  - In `expired()`, sleep triggers at `l2 >= 180000L` (3 minutes) calling `this.s(true)` (which syncs sleeping state to the server).
  - Disconnect / idle-out triggers at `l2 >= 900000L` (15 minutes, or 3,600,000L if subscriber) calling `this.aMY.apT.Q(false)`.
  - Anti-AFK hooks must intercept `expired()` at the top: if enabled, set `this.alB = System.currentTimeMillis()`, call `this.s(false)` to clear sleeping state, and return immediately.
- **Auction House Time Left Invariants**:
  - The server protocol (`ListingInfo.timeLeft`) only transmits discrete relative duration enums (`VERY_SHORT`, `SHORT`, `MEDIUM`, `LONG`, `VERY_LONG`). Absolute expiry timestamps are intentionally omitted server-side to prevent bot sniping.
  - Active bidding during `VERY_SHORT` extends the auction window by 1–5 minutes (anti-sniping buffer).
  - Client countdowns hook `com.threerings.projectx.auction.client.d` row binding and use Clyde's `renderComponent()` thread loop at 1 Hz with zero background thread creation.
- **Distributed Set (`DSet`) Query Invariants**:
  - Distributed sets (`com.threerings.presents.dobj.DSet`, such as `ServerObject.gates`, `LevelPartyObject.loot`, `LevelPartyObject.heat`) do not implement `Map.get(Object)`. Querying entries by comparable key requires calling `DSet.f(Comparable key)`.
  - Party loot and heat records (`com.threerings.projectx.dungeon.data.Loot`, `Heat`) extend `BasePartyMember`, implementing `DSet$Entry` with `_playerOid` (`new Integer(oid)`) as the primary key.
- **Crash-Proof Custom HUD Controls & Checkboxes**:
  - Clyde widgets lacking definitions in game `.dat` bundles crash on instantiation with NPEs.
  - Interactive toggles and checkboxes should be implemented as lightweight `Label` components with `"Default/ChatOverlayText"` styling, toggled via relative coordinate containment checks in `MouseAdapter.mouseClicked(MouseEvent)`.

## Bytecode Patching & Compilation
- **Obfuscated Package-Class Collision**: Obfuscated Three Rings packages often contain classes with identical names to package segments (e.g. class `com.threerings.tudey.a` coexisting with package `com.threerings.tudey.a.c`). In standard `javac`, references like `com.threerings.tudey.a.c.b` trigger `cannot find symbol: class c, location: class a`. Avoid static imports; use `Object` typing with reflection, or inject bytecode directly via Javassist.
- **Javassist Instrumentation**: Prefer Javassist bytecode transformation over recompiling full decompiled classes to maintain compatibility across game updates.

## Process Lifecycle & Tooling Invariants
- Spiral Knights startup lifecycle runs: Launcher (`KnightLauncher.jar`) &rarr; Updater (`getdown-pro.jar`, window title `"Spiral Knights"`) &rarr; Game Client (`projectx-pcode.jar`, `Bootstrap`).
- Automation and profiler scripts must strictly ignore `*KnightLauncher*`, `*getdown-pro.jar*`, and `*GetdownApp*`, and require `projectx-pcode.jar` or `ProjectXApp` on the command line to detect the true game process. **Never filter generic `*getdown*`**, as the official game client command line always contains `-Dcom.threerings.getdown=true`.

## JVM & Extra Arguments
- `extra.txt` only accepts strict JVM arguments:
  - Must begin with `-`
  - Must NOT contain spaces
  - Must NOT contain comment lines (`#`)
- **KnightLauncher Properties Overwrite Lifecycle**:
  - `extra.txt` is regenerated on every launch by KnightLauncher from `game.additionalArgs.v2` in `KnightLauncher.properties`.
  - Profiling or JVM argument automation scripts must update `KnightLauncher.properties` in addition to `extra.txt`.
  - **Do NOT set `attrib +r` on `extra.txt`**: KnightLauncher attempts to open `extra.txt` with `new PrintWriter()`; setting `+r` causes a fatal `FileNotFoundException (Access is denied)` crash. Writing 100% valid arguments with no trailing newlines completely eliminates launcher reset behavior without write locking.
- **JVM JRE Upgrade via Directory Junction**:
  - Spiral Knights' bundled `java_vm` is a minimal `jlink` image missing `jdk.jfr` and `java.management`.
  - Replace `java_vm` with an NTFS junction (`mklink /J java_vm <JDK25_HOME>`) to a full Adoptium JDK 25 LTS to enable JFR recording and JMX management beans with zero disk overhead.

## Optimization Mod Rules
- Never degrade on-screen visual fidelity (no low-poly mesh downgrades, no texture downscaling, no particle disabling).
- Focus optimizations on:
  1. Redundant OpenGL state caching in `com.threerings.opengl.renderer`.
     - **In-place array reuse invariant**: Never short-circuit state changes on array reference equality (`_states == $1`). Clyde subsystems (e.g., Tudey `com.threerings.tudey.a.c.b` map chunk renderer) mutate elements of shared array instances (`bRK[0].texture = tex`) and pass the same array into `setTextureState()`. Deduplication must compare slot contents and check `dirty` flags.
  2. Multi-hop Virtual Delegation Unrolling:
     - Clyde wraps `GlContext` in `com.threerings.opengl.util.e` (`GlContextWrapper`) in 5–10 deep delegation chains. Constructor-cache direct references to `getCompositor()` and `getRenderer()` to collapse call overhead across all draw calls.
  3. Exception Churn Elimination:
     - Replace exception-throwing resource lookups (e.g. `MessageBundle.getString()` in `com.threerings.util.Q`, and `resourceBundle.getString("__parent")` / `getString("msgbundle_class")` in `com.threerings.util.R`) with non-throwing checks (`containsKey()`) to eliminate tens of thousands of native `MissingResourceException` stack trace allocations per session.
  4. Spatial Octree Traversal Acceleration:
     - In `com.threerings.opengl.scene.e$d` and `e$b`, invert contains/intersects checks and prune disjoint child nodes before virtual dispatch (`box.e(d2._bounds)`) to reduce octree traversal overhead during dynamic light and element updates.
  5. Draw call deduplication and static batching.
  6. Mod Deployment & Direct `config.jar` Injection:
     - Master build tooling must deploy mod zips to `mods/` and directly update `<game>/code/config.jar` using `jar -uf`. This guarantees immediate mod activation even when KnightLauncher's cache invalidation does not run.
  7. Spatial Octree Bounds Hysteresis:
     - `Articulated.tick()` bounds updates must use a `0.15f` hysteresis envelope. Sub-pixel bone animation changes cause exact `Box.equals()` to fail every frame, driving continuous `e$b.remove` / `e$b.add` octree thrashing (>10,700 JFR execution samples).
  8. Zero-Allocation Minimap Discovery Engine:
     - `map.client.a.e(Coord)` circular falloff calculations must use precomputed static lookup tables `(dx, dy, weight)` to avoid allocating thousands of `HashMap`, `Coord`, and boxed `Float` objects per frame (>8,100 JFR allocation samples, over 50% of game total).
  9. Frame Capping & VSync Layering:
     - `ProjectXApp.aLk` controls native frame cap, while `ProjectXApp.sync()` sleeps when `fps > 0`. When uncapped (`fps <= 0` or Unlimited), `sync()` returns immediately. VSync is controlled via `GLFW.glfwSwapInterval(vsync ? 1 : 0)`. Options slider can be expanded to 500 FPS.
  10. Uncapped Render Thread Sleep Bypass:
     - `ProjectXApp.updateFrame()` calls `sync(aLk)` which executes `Thread.sleep()`. When uncapped (`aLk <= 0`) or Max FPS preset (`aLk >= 500`), bypass `sync()` completely for 0 ms sleep.
  11. Skeletal Animation LOD & Node Transform Decoupling:
     - In `Articulated.tick()` (`com.threerings.opengl.model.e`), decouple distant entity updates using multi-tier camera distance checks (resolving `getTarget()` or `getViewerTranslation()` on `CameraHandler`). Skip both `updateTransforms()` and `e$b.update()` (node world matrix concatenation) on throttled frames.
  12. Batch Color Invalidation Pruning:
     - In `Renderer.render(List)`, Clyde checks `_colorArray.enabled != Boolean.FALSE`. Since `_colorArray.enabled` is `null` for uncolored batches, it unconditionally invalidates color state on every batch. Replace with `Boolean.TRUE.equals(_colorArray.enabled)` to eliminate thousands of redundant `invalidateColorState()` driver calls per frame.
  13. Hardware-Accelerated P/N Vertex Frustum Culling:
     - In `Frustum.f(Box)`, replace Clyde's vanilla 48 plane-vertex tests ($6 \times 8$) and static `ahO` allocation with standard P/N vertex testing (1 dot product per plane). Exits immediately on the 1st or 2nd plane with 0 allocations, speeding up frustum culling by ~8x on the #3 CPU hotspot (775 JFR samples).
  14. Fast Batch Comparison:
     - In `com.threerings.opengl.renderer.a.compareKeys(int[], int[])`, short-circuit array reference identity (`$1 == $2`) and loop directly up to `Math.min(len1, len2)`, eliminating per-element ternary and bounds-checking overhead across thousands of draw call sorting comparisons.
  15. Clyde ComboBox & Dialog Styling Invariant:
     - Spiral Knights does NOT define `"Default/ComboBox"` in its runtime resource bundles; UI styles are exclusively mapped inside `.dat` files. Programmatic `new ComboBox(ctx, ...)` leaves `_styleConfigs` null, provoking a fatal NPE during `layoutConfig() -> tfact.wrapText()`. Use native `CheckBox` (`k`) toggles whose styles are cloned from existing dialog controls (`box.setStyleConfigs(aJT.getStyleConfigs())`), and avoid calling `this.pack()` on interfaces loaded from `.dat` definitions.
  16. Synchronous glGetInteger Pipeline Stall Elimination:
     - In `Renderer.setVertexArray()`, Clyde contained a leftover production assertion: `if (GL11.glGetInteger(34964) == 0)`. Querying `GL_ARRAY_BUFFER_BINDING` (34964 / 0x8894) halts the CPU thread and forces a synchronous driver flush on every single VBO batch (1,300 to 3,200 stalls per frame). Stripping this assertion removes the #1 OpenGL driver stall point.
  17. Particle System Spatial Octree Hysteresis:
     - In `com.threerings.opengl.effect.a.tick(float)`, particle systems suffered from identical sub-pixel bounds oscillation as `Articulated.tick()`. Applying a `0.15f` hysteresis envelope stabilizes dynamic particle systems and prevents continuous octree removal and re-insertion churn.
  18. Frustum-Aware Skeletal Animation Decoupling:
     - Off-screen entities (`cachedFrustum.f(bounds) == IntersectionType.NONE`) do not require 120 Hz skeletal matrix recalculations. Throttling off-screen transforms to 1/4 rate (30 Hz) slashes `Articulated.tick` matrix math overhead by 75% on hidden entities while keeping 100% full frame rate on the player and in-screen combat.
  19. Clockworks Dungeon Combat Telemetry Hooks:
     - Dungeon Party registration hooks `com.threerings.projectx.client.X.a(DObject)` and `l(DObject)` (where `X` is the base for dungeon directors like `com.threerings.projectx.dungeon.client.T`).
     - Damage events hook `com.threerings.projectx.dungeon.data.PartyObject$b` (`void a(PartyObject$DamageEvent)`). Attacker pawn is resolved via `place().occupantInfo` matching `pawnId`.
     - Depth progression hooks `QuickbarPanel$a.tick(float)` to monitor `party.depth` / `LevelPartyObject.floor` transitions with zero extra thread dispatch.

## Continuous Self-Learning Protocol (/learn)
- Every confirmed runtime invariant, reverse-engineered mechanism, or verified optimization must be codified:
  1. Engine/runtime constraints &rarr; `.agents/rules/spiral-knights-modding.md`
  2. Lifecycle & reversing patterns &rarr; `.agents/skills/spiral-knights-modder/SKILL.md`
  3. Telemetry/JFR analysis & benchmarks &rarr; `.agents/skills/spiral-knights-optimizer/SKILL.md`
  4. User-facing capabilities & slash commands &rarr; `README.md`
  5. Technical verification logs & deltas &rarr; `walkthrough.md` and conversation artifacts

