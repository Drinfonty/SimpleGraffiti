# Simple Graffiti — Requirements

> Spray-paint on any block surface with a craftable spray can.
> Minecraft **26.2**, **Fabric + NeoForge** from one shared codebase.
> Server + client mod, playable in singleplayer, harmless on servers that do not have it.

This document states *what* the mod must do and for whom. The architecture is in
[DESIGN.md](DESIGN.md); the normative, testable behaviour is in [SPEC.md](SPEC.md).
IDs below (`FR-…`, `NFR-…`) are stable references used by both.

---

## 1. Vision & goals

Minecraft gives you blocks; it does not give you a way to leave a mark on them. Simple
Graffiti adds one item — the **spray can** — and one verb: point at a wall and paint. Paint
lands as 16×16 pixel art on the face you sprayed, in the aesthetic the game already uses,
and everyone on the server sees it.

Design pillars, in priority order:

1. **One item, one verb.** Craft a can, hold right-click, paint. No block entities to place,
   no editor mode, no canvas item to hang. If the mod needs a tutorial, it has failed.
2. **Shared and persistent.** Graffiti is world data, owned by the server, saved with the
   world, and visible to every player who has the mod. It is not a client-side overlay.
3. **Harmless when absent.** A client with the mod on a server without it does nothing
   surprising: no crash, no kick, no phantom paint. A server with the mod never breaks a
   client that lacks it.
4. **Cheap.** A painted world must not cost measurable frame time or tick time. Paint is
   baked into chunk geometry, not drawn per frame per decal.
5. **Operable.** A server admin can rate-limit it, restrict it, inspect it, and clean it up
   without editing region files by hand.

### Non-goals (v1.0)

- Freeform sub-pixel painting, gradients, or arbitrary RGB. The palette is the 16 dye
  colours and the resolution is 16×16 per block face — deliberately.
- Importing images, copy/paste, stencils, or a in-game drawing editor screen.
- Painting on entities, mobs, items, non-full blocks, or the sky.
- Graffiti that survives the block moving (pistons) or the block breaking.
- Client-side-only "private" graffiti that other players cannot see.

## 2. Personas

| Persona | Needs |
| :--- | :--- |
| **Player (builder)** | Craft a can, pick a colour, decorate a build; paint must appear instantly, look intentional, and survive a relog. |
| **Player (jokester)** | Tag a friend's base fast, in a group, without lag; be able to wipe it off again. |
| **Server operator** | Install one jar server-side, cap abuse (rate, area, permissions), clear a region or one player's paint with a command, and not worry about world-file bloat. |
| **Mod-less player on a modded server** | Join and play normally. They see no paint, and nothing about their session breaks. |
| **Modded player on a vanilla server** | Nothing happens. No error spam, no disconnect, one clear message if they try. |

## 3. Scope & milestones

### M1 — v1.0 (the mod)

- Spray can item, craftable by the recipe in [SPEC §3.1](SPEC.md#31-crafting), with a colour
  and a charge count.
- Paint and erase on any sturdy full-block face, in survival and creative, singleplayer and
  multiplayer.
- Server-authoritative canvas storage, chunk-granular, saved with the world.
- Client rendering emitted through each loader's block-model pipeline (so it bakes into the
  chunk mesh), with correct lighting and occlusion.
- Colour selection UI and colour pick-up from coloured blocks.
- Graceful degradation both directions (mod-less server, mod-less client).
- Server config, `/graffiti` admin commands, per-player rate limits.
- Fabric and NeoForge jars from one `:common` module, verified with `-PtestJar` on both.

### M2 — v1.1

- `legacy-26.1` branch (back-support for 26.1.x).
- Stencils/stamps (fixed shape library: arrow, skull, heart, letters).
- Larger brushes, spray falloff options, per-player brush presets.
- Paint fades in rain (opt-in), moss/aging over time (opt-in).
- Paint moved by pistons.

### M3 — v1.2

- Export/import a painted region as a data file (share a mural).
- Painting on slabs, stairs and other partial shapes.
- Integration hooks for common land-protection mods.

### Out of scope

- Bedrock parity, resource-pack-driven custom palettes, animated paint.

## 4. Functional requirements

**[M1]** marks v1.0-critical.

### 4.1 The spray can

- **FR-CAN-1 [M1]** A `simple_graffiti:spray_can` item exists, stacks to 1, and is crafted by
  the shaped recipe supplied by the project owner ([SPEC §3.1](SPEC.md#31-crafting)).
- **FR-CAN-2 [M1]** A can carries a **paint colour** (one of the 16 dye colours) and a
  **charge count**; both are visible on the item (tooltip and durability-style bar).
- **FR-CAN-3 [M1]** Each spray action consumes one charge. A can at zero charges paints
  nothing and gives clear feedback.
- **FR-CAN-4 [M1]** A can can be refilled and/or recoloured by crafting it with a dye. The
  remaining charges of the input can must not be silently lost.
- **FR-CAN-5 [M1]** In creative mode charges are not consumed.
- **FR-CAN-6 [M1]** The player can change the can's colour in-game without crafting: a
  palette UI on a keybind, and by sneak-using the can on a block whose colour is a dye colour
  (wool, concrete, terracotta, glass, shulker box).
- **FR-CAN-7** The can is not enchantable and is not repaired in an anvil (v1.0).

### 4.2 Painting

- **FR-PAINT-1 [M1]** Using the can on a **paintable face** (see FR-PAINT-3) stamps a
  brush-shaped mark of the can's colour at the point aimed at, in that face's 16×16 pixel
  grid.
- **FR-PAINT-2 [M1]** Holding use paints continuously at a fixed rate, so dragging the
  crosshair draws a line.
- **FR-PAINT-3 [M1]** A face is paintable when the block's face is a sturdy full face, the
  block is not on the server's deny list, and the face is exposed (not flush against another
  block). Non-paintable targets do nothing and are not consumed against.
- **FR-PAINT-4 [M1]** Sneak-using the can **erases** paint under the brush instead of adding
  it, and costs no charge.
- **FR-PAINT-5 [M1]** Using a wet sponge on a painted face clears that whole face.
- **FR-PAINT-6 [M1]** Breaking a block removes all paint on that block. Paint is not dropped
  and is not recoverable.
- **FR-PAINT-7 [M1]** Painting over existing paint replaces the colour of the pixels covered.
- **FR-PAINT-8 [M1]** Paint applies to exactly one face; it does not wrap around corners, and
  spraying at a corner affects only the face aimed at.
- **FR-PAINT-9 [M1]** Painting obeys the player's reach and the server's rate limit; a
  rejected paint is corrected on the client, not left as a ghost.

### 4.3 Persistence & sharing

- **FR-DATA-1 [M1]** Graffiti is stored server-side per dimension and per chunk, saved with
  the world, and survives restarts.
- **FR-DATA-2 [M1]** Every player with the mod sees the same graffiti, and sees new paint
  from other players within one tick of the server applying it.
- **FR-DATA-3 [M1]** Loading a chunk delivers its graffiti to the players tracking it;
  unloading it releases the memory.
- **FR-DATA-4 [M1]** Each canvas records who last painted it and when, for moderation.
- **FR-DATA-5 [M1]** Corrupt or unreadable graffiti data must degrade to "no graffiti here"
  and must never prevent the chunk, the world, or the server from loading.

### 4.4 Compatibility & degradation

- **FR-COMPAT-1 [M1]** Singleplayer works fully, with no special setup.
- **FR-COMPAT-2 [M1]** A client with the mod on a server **without** it: no crash, no kick,
  no rendering, no local state. Trying to paint gives one clear message and nothing else.
- **FR-COMPAT-3 [M1]** A server with the mod and a client **without** it: the client is never
  sent a graffiti packet and is never disconnected because of the mod.
- **FR-COMPAT-4 [M1]** Protocol version mismatch between client and server is treated exactly
  like "the other side does not have the mod".
- **FR-COMPAT-5 [M1]** The mod adds no behaviour to vanilla items, blocks or recipes.

### 4.5 Server operation

- **FR-OPS-1 [M1]** A server config file controls: enabled, permission mode, per-player paint
  rate, maximum painted faces per chunk, and the block deny list.
- **FR-OPS-2 [M1]** `/graffiti` admin commands (op level 2) can clear paint in a radius,
  clear all paint by one player, report stats, and reload the config.
- **FR-OPS-3 [M1]** Permission modes: anyone, ops only, or "only where the player may build"
  (delegated to the server's own block-place check).
- **FR-OPS-4 [M1]** Paintable blocks are controlled by block tags, so packs can restrict
  surfaces without code.
- **FR-OPS-5** Optional per-player paint budget and an audit log of paint operations (v1.1).

### 4.6 Client experience

- **FR-UX-1 [M1]** Paint appears immediately on the painter's screen (optimistic), before the
  server round-trip.
- **FR-UX-2 [M1]** A palette screen shows the 16 colours, the brush size, and the can's
  remaining charges.
- **FR-UX-3 [M1]** Graffiti is lit, occluded and fogged like the block it sits on, and does
  not z-fight at any distance or angle.
- **FR-UX-4 [M1]** A client config can disable rendering entirely (for low-end machines or
  screenshots) without disconnecting.
- **FR-UX-5** Colour picker on scroll-with-modifier, and a "recent colours" row (v1.1).

## 5. Non-functional requirements

### 5.1 Performance

- **NFR-PERF-1** Rendering costs **zero per-frame work per canvas**: paint is baked into the
  chunk section mesh and only re-baked when it changes.
- **NFR-PERF-2** A single paint operation costs < 100 µs of server tick time, and remeshes at
  most one chunk section plus its neighbours on each client.
- **NFR-PERF-3** A chunk with 2 048 painted faces adds < 1 ms to a section rebuild and
  < 1 MB of client memory.
- **NFR-PERF-4** Continuous painting by one player costs < 1 KB/s of downstream bandwidth per
  observer; full-chunk sync is compressed and capped.
- **NFR-PERF-5** No file I/O on the render thread or the network thread.

### 5.2 Storage

- **NFR-STORE-1** Graffiti storage is chunk-granular, loaded and unloaded with the chunk —
  never a single whole-dimension blob held in memory.
- **NFR-STORE-2** A painted face costs 256 bytes uncompressed; on disk, a heavily-painted
  chunk stays in the same order of magnitude as a vanilla chunk.
- **NFR-STORE-3** Writes are atomic at region-file granularity and survive an unclean
  shutdown with at most the last autosave interval lost.

### 5.3 Compatibility

- **NFR-COMPAT-1** Minecraft **26.2** (Java 25) on both loaders, from one `:common` module.
- **NFR-COMPAT-2** Back-support for **26.1.x** on a later branch; nothing in v1.0 may make
  that port structurally harder (drawing and mapping-sensitive code stays isolated).
- **NFR-COMPAT-3** The protocol is versioned and negotiated; both sides degrade rather than
  fail on mismatch.
- **NFR-COMPAT-4** No dependency on any mod other than Fabric API (Fabric only).
- **NFR-COMPAT-5** Graffiti must render under common rendering mods (Sodium/Embeddium class).
  This is why paint is emitted through each loader's block-model pipeline rather than injected
  into the vanilla chunk mesher. Any residual incompatibility must be stated, not hidden.
  See [DESIGN §6.4](DESIGN.md#64-compatibility-with-rendering-mods).

### 5.4 Security & fairness

- **NFR-SEC-1** The server is authoritative over every canvas. A client may only *request* a
  paint; position, reach, permission, charges and rate are validated server-side.
- **NFR-SEC-2** All incoming payloads are bounds-checked; no packet may allocate unbounded
  memory or address a chunk the sender does not track.
- **NFR-SEC-3** Rate limiting is per player and cannot be bypassed by packet spam.

### 5.5 Maintainability

- **NFR-MAINT-1** All logic lives in `:common`; each loader module contributes only
  registration glue and one renderer adapter that hands `:common`-produced geometry to that
  loader's model API (the Checkbox/RedFX structure, plus the exception in NFR-COMPAT-5).
- **NFR-MAINT-2** Canvas maths, brush maths, serialisation and rate limiting are unit-tested
  in a plain JVM with no game bootstrap.
- **NFR-MAINT-3** Brush application is **bit-exact deterministic** and shared by client and
  server, so prediction and authority cannot drift.

## 6. Constraints & assumptions

- Vanilla's `BlockStateModel` is not position-aware in 26.2, so per-position geometry must go
  through each loader's own model extension (Fabric FRAPI, NeoForge `DynamicBlockStateModel`).
  This is the one place where a per-loader renderer adapter is unavoidable, and the main
  technical risk; see [DESIGN §6](DESIGN.md#6-rendering).
- Both loaders run Mojang mappings on 26.x, which is what makes a single compiled `:common`
  valid on both.
- Fabric API 26.2 exposes `ClientPlayNetworking.canSend`; NeoForge exposes optional payloads
  and `NetworkRegistry.hasChannel`. Degradation depends on these existing.
- The build environment mirrors Checkbox: Gradle 9.5.1, Java 25 toolchain, Loom
  `1.17-SNAPSHOT`, NeoForge ModDev `2.0.143`.
- The crafting recipe is fixed by the project owner and is not up for redesign.

## 7. Acceptance criteria (v1.0 "done")

Verified on both loaders with `-PtestJar`, in singleplayer **and** against a dedicated
server. The full, numbered list is [SPEC §11](SPEC.md#11-acceptance-criteria); the headline
gates are:

1. Craft a can from the given recipe; paint a legible mark on a stone wall; relog; it is
   still there and unchanged.
2. Two clients, one server: player A paints, player B sees it appear within a tick, and
   still sees it after both relog.
3. A vanilla client joins the modded dedicated server, plays for five minutes near painted
   chunks, and is never disconnected or errored.
4. A modded client joins a vanilla server, right-clicks with a creative-given can, gets one
   message, and nothing else happens — no paint, no packet, no log spam.
5. Breaking a painted block removes its paint for every observer.
6. 2 048 painted faces in one chunk: no measurable frame-time regression and no tick-time
   warning.
7. Killing the server mid-paint loses at most the last autosave; the world still loads.

## 8. Open questions

- **Charges per can (64) and per-spray cost (1)** are first guesses; they need play-testing
  against how much paint a mural actually takes.
- Should erasing be free (specced) or cost a charge? Free favours experimentation.
- Should paint block light or affect the block's light emission? Currently no.
- Is "paint moves with pistons" worth the storage complexity, or is deleting it acceptable
  forever? Currently deleted, revisit in v1.1.
