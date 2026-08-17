# Design: Simple Graffiti

Simple Graffiti is a **server + client** Minecraft mod that adds a spray can. Aim at a block
face, hold use, and paint appears on it — 16×16 texels per face, in any colour you like,
stored with the world and shared by everyone.

This document explains *how* it is built and *why*. The normative, testable behaviour lives
in [SPEC.md](SPEC.md); the goals and scope it serves live in
[REQUIREMENTS.md](REQUIREMENTS.md).

Target: **Minecraft 26.2**, `main` branch, Fabric + NeoForge. 26.1.x back-support is a later
branch ([§11](#11-branches--porting)).

---

## 1. Goals and non-goals

### Goals

* One craftable item, one verb: point at a wall, hold right-click, paint.
* Server-authoritative, world-persistent, shared between players.
* Zero per-frame rendering cost per canvas.
* Degrade to *nothing* — never to an error — when the other side lacks the mod.
* One implementation for both loaders, following the structure proven in Checkbox and RedFX:
  everything in `:common`, loaders contribute registration glue and one renderer adapter.

### Non-goals (v1.0)

* Arbitrary RGB, sub-pixel resolution, image import, stencils, in-game drawing editor.
* Painting entities, partial blocks (slabs/stairs), or anything that is not a sturdy full
  face.
* Paint that survives the block being broken or moved.
* Client-only "personal" graffiti.

### The shaping constraint

**The canvas is a fixed 16×16 grid of texels per block face.** Colour is unrestricted — any
24-bit RGB — but the *grid* is fixed, and everything else follows from that, so it is worth
stating why it was chosen over the obvious alternative, a freeform decal with arbitrary
position and size:

| | 16×16 texel canvas (chosen) | Freeform decals |
| :--- | :--- | :--- |
| Storage per painted face | 1 KB, fixed | unbounded — grows with every spray |
| Wire format for one spray | 16 bytes (a stamp op both sides replay) | a new decal record, forever |
| Rendering | merged coloured quads emitted with the block's own model | per-decal quads, sorted, per frame |
| Look | matches Minecraft's own 16-px texel grid | reads as a foreign overlay |
| Erasing | clear texels | delete/split decal records |
| Worst case | bounded by geometry: 6 faces × 1 KB per block | bounded by nothing |

The grid is what makes the storage, the protocol and the renderer all bounded, and it happens
to be the aesthetic the game already uses. The cost is that you cannot paint smaller than a
texel — which for a mod called *Simple* Graffiti is a feature.

**Colour is not similarly constrained**, because it need not be. A texel stores ARGB directly
(1 KB/face rather than 256 B for a 16-entry palette), and in exchange every colour maths
question disappears: no palette to negotiate, no quantisation on paint, no eviction rule when
a face exceeds N colours, and — most importantly — no palette *ordering* for two concurrent
painters' stamps to disagree about. RLE and region compression recover most of the size on
disk, since real canvases use few colours even though they may use any.

---

## 2. Platform baseline (verified against 26.2)

The facts below were read out of `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`,
`fabric-api-0.154.0+26.2.jar` and `neoforge-26.2.0.45-beta-universal.jar` with `javap`, not
from memory. They drive most of the architecture.

| Fact | Consequence |
| :--- | :--- |
| Vanilla `BlockStateModel.collectParts(RandomSource, List<BlockStateModelPart>)` receives **no `BlockPos`** | A mappings-only model wrapper in `:common` cannot know which block it is drawing. Position-aware emission is the one thing that must be per-loader ([§6](#6-rendering)) |
| Fabric: `WrapperBlockStateModel.emitQuads(QuadEmitter, BlockAndTintGetter, BlockPos, BlockState, RandomSource, Predicate<Direction>)` + `createGeometryKey(…)`, installed via `ModelLoadingPlugin.Context.modifyBlockModelAfterBake()` (packages `…api.client.model.loading.v1.wrapper` and `…api.client.renderer.v1.mesh`) | Graffiti is emitted through FRAPI as part of the block's own model, with a cache key so position-dependent geometry is not rebuilt needlessly |
| NeoForge: `DynamicBlockStateModel.collectParts(BlockAndTintGetter, BlockPos, BlockState, RandomSource, List<BlockStateModelPart>)`, installed via `ModelEvent.ModifyBakingResult`; `BlockStateModelPart.getQuads(Direction)` returns `List<BakedQuad>`; `BakedQuad` is a record of four positions, packed UVs, a direction and `MaterialInfo` | Same idea, different shape: we append one part whose quads are built at runtime from the canvas and cached |
| A vanilla `BakedQuad` carries **no vertex colour** — colour comes from a tint index resolved by a per-block `BlockTintSource`, which is given only `(state, level, pos)`. NeoForge's `MutableQuad.setColor(int).toBakedQuad()` does carry one | The NeoForge adapter must use vertex colour. A tint index cannot express 256 arbitrary colours on one block, because the tint source does not know which face or texel it is colouring |
| `BlockStateModel.materialFlags()` is a bitmask of `FLAG_TRANSLUCENT` and `FLAG_ANIMATED` only — **not** a per-`ChunkSectionLayer` mask | Opaque, unanimated paint contributes no flags, so neither adapter overrides it. The cutout layer is selected per quad instead |
| 26.2 replaced numeric op levels with named permissions: `Player.permissions()` returns a `PermissionSet`, and `Permissions.COMMANDS_GAMEMASTER` is the old level 2 | `OPS_ONLY` and the `/graffiti` permission check are written against the named permission |
| Both APIs are **public and supported**, and both feed the standard model pipeline | Chunk-meshing replacement mods (Sodium class) consume the same pipeline, so paint stays visible under them ([§6.4](#64-compatibility-with-rendering-mods)) |
| `ChunkSectionLayer` is now an enum of `SOLID`, `CUTOUT`, `TRANSLUCENT` with `pipeline()`/`vertexFormat()` | Paint is emitted with cutout material flags: alpha-tested, unsorted, no translucency cost |
| `ClientLevel.setSectionDirtyWithNeighbors(int,int,int)` is public | Canvas changes trigger a remesh with no mixin |
| `SectionCompiler.compile(…)` builds its `Map<ChunkSectionLayer, MeshData>` through a private static `getOrBeginLayer(…)`; 26.2 world rendering is otherwise extraction + submit nodes (Fabric `LevelRenderEvents`/`SubmitRenderPhases`, NeoForge `RenderLevelStageEvent.AfterOpaqueBlocks`) | Two rejected alternatives — a mixin into the vanilla mesher, and a per-frame pass. Both are viable and both are worse; see [§6.3](#63-rejected-alternatives) |
| Mod sprites can be added to the vanilla block atlas by shipping `assets/minecraft/atlases/blocks.json` (RedFX already does this) | Paint sprites live in the block atlas, so `CUTOUT` needs no extra texture bind |
| `BlockStateBase.isFaceSturdy(BlockGetter, BlockPos, Direction)` and `isCollisionShapeFullBlock` | The paintability test, server- and client-side, with no block list |
| `PlayerChunkSender.sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)` (private static) and `dropChunk` | The exact "this chunk is now/no longer on this client" seam, identical on both loaders — Fabric API has no `CHUNK_SENT_TO_PLAYER` event, NeoForge has `ChunkWatchEvent.Sent`; one mixin serves both |
| `ChunkMap extends SimpleRegionStorage`; `SimpleRegionStorage(RegionStorageInfo, Path, DataFixer, boolean, DataFixTypes)` with `read/write(ChunkPos, CompoundTag)` returning futures | Graffiti gets its own region-file store next to `region/` and `entities/`, chunk-granular and off-thread, instead of a whole-dimension `SavedData` blob |
| Fabric: `PayloadTypeRegistry`, `ServerPlayNetworking`, `ClientPlayNetworking.canSend(CustomPacketPayload.Type)` | Graceful degradation on Fabric is a one-line check |
| NeoForge: `RegisterPayloadHandlersEvent` → `PayloadRegistrar.optional()`, `NetworkRegistry.hasChannel(listener, Identifier)`, `ClientPacketDistributor.sendToServer`, `PacketDistributor` | `optional()` is what stops a modded client being disconnected from a server that lacks the mod |
| `DyeRecipe` (`minecraft:crafting_dye`) is data-driven, and `Item.overrideOtherStackedOnMe`/`overrideStackedOnOther` are the hooks vanilla bundles use for inventory-click interactions | Recolouring is a recipe; refilling is a bundle-style click rather than a recipe, so no crafting table is involved |
| `net.minecraft.resources.Identifier` (not `ResourceLocation`); `DataComponentType`; `DyeColor` | Same naming baseline as Checkbox — `SimpleGraffiti.id(String)` helper |

Only one of these is *not* API-stable: the `PlayerChunkSender` injection, a single small
mixin, needed because neither loader exposes "this chunk has just been sent to this player"
in a form both share. Everything else above is public API on at least one loader.

---

## 3. Module and package layout

Mirrors Checkbox and RedFX so the projects stay muscle-memory compatible.

```
Graffiti/
├── build.gradle              # plugin decls, shared repos, Modrinth token lookup
├── settings.gradle           # pluginManagement + include 'common','fabric','neoforge'
├── gradle.properties         # single source of truth for versions
├── common/                   # ALL logic. Loom-compiled against Mojang-named 26.2.
├── fabric/                   # ModInitializer + ClientModInitializer glue only
├── neoforge/                 # @Mod + event-bus glue only
├── docs/{REQUIREMENTS,DESIGN,SPEC}.md
└── release/                  # build output, published to Modrinth
```

`:common` package tree (`com.drinfonty.simplegraffiti`):

```
simplegraffiti/
├── SimpleGraffiti.java            # MOD_ID, LOGGER, debug(), id(String)
├── GraffitiServer.java            # server lifecycle facade the loaders call into
├── GraffitiClient.java            # client lifecycle facade the loaders call into
├── canvas/
│   ├── Canvas.java                # 16x16 byte grid, immutable-on-publish
│   ├── CanvasKey.java             # (BlockPos, Direction) packed into a long
│   ├── FaceAxes.java              # Direction -> (u,v) axes and hit-point mapping
│   ├── PaintColor.java            # ARGB helpers, dye presets, block-colour sampling
│   ├── Brush.java                 # THE deterministic stamp: shared by client and server
│   └── CanvasCodec.java           # NBT and network encodings, RLE
├── world/
│   ├── ChunkCanvases.java         # all canvases in one chunk + dirty flag
│   ├── CanvasStore.java           # server-side: load/unload/lookup/mutate per dimension
│   ├── CanvasRegionStorage.java   # SimpleRegionStorage wrapper, async read/write
│   └── PaintService.java          # validation + apply + broadcast (the authority)
├── item/
│   ├── SprayCanItem.java          # use / useOn, charges, colour, feedback
│   ├── ScrubSpongeItem.java       # brush erase / whole-face erase
│   └── GraffitiItems.java         # item registration hook (both loaders call in)
├── net/
│   ├── GraffitiPayloads.java      # payload types + stream codecs (no loader API)
│   ├── HelloS2C.java              # protocol handshake / capability advertisement
│   ├── PaintC2S.java              # a paint request
│   ├── StampS2C.java              # an applied stamp, broadcast
│   ├── CanvasSyncS2C.java         # full canvases for a chunk
│   ├── ClearS2C.java              # face/block/chunk cleared
│   └── SetColorC2S.java           # colour picker selection
├── server/
│   ├── GraffitiCommands.java      # /graffiti, brigadier, loader-neutral
│   ├── RateLimiter.java           # per-player token bucket
│   └── ServerConfig.java          # config/simple_graffiti/server.json
├── mixin/
│   ├── PlayerChunkSenderMixin.java    # chunk sent / dropped per player
│   └── ChunkMapAccessor.java
└── client/
    ├── ClientCanvasStore.java     # what this client knows, read by mesher threads
    ├── ClientPaintController.java # hold-to-spray, optimistic apply, reconciliation
    ├── ServerCapability.java      # NONE | READY — the degradation gate
    ├── GraffitiKeys.java          # colour picker keybind
    ├── gui/PaletteScreen.java
    └── render/
        ├── CanvasMesher.java      # canvas -> merged rectangles (loader-neutral geometry)
        ├── PaintQuad.java         # one merged rect: face, texel bounds, ARGB
        └── PaintSprites.java      # atlas sprite lookup
```

The renderer is split so the *geometry* — greedy merging, face maths, decal offset, UVs — is
loader-neutral and unit-testable, and only the final hand-off differs:

```
fabric/src/client/…/render/GraffitiWrapperModel.java      # WrapperBlockStateModel + QuadEmitter
neoforge/src/main/…/client/render/GraffitiDynamicModel.java  # DynamicBlockStateModel + BakedQuad part
```

### Why "everything in `:common`"

`:common` is compiled by Loom against the Mojang-named 26.2 jar, and NeoForge also runs
Mojang mappings on 26.x, so the *same* compiled classes are valid on both loaders. `:common`
may reference Minecraft and Mixin, but **never** a loader API. Unlike Checkbox, this mod has
a dedicated-server side, so one extra rule applies: nothing reachable from a server code path
may touch `net.minecraft.client.*`. Client code is confined to `client/`, guarded by
`environment: "client"` in the mixin config and `Dist.CLIENT` on NeoForge.

Only these differ per loader, and each gets a thin adapter:

| Concern | Fabric | NeoForge |
| :--- | :--- | :--- |
| Entry point | `ModInitializer` + `ClientModInitializer` | `@Mod` ctor + `Dist.CLIENT` guard |
| Item / component registration | `Registry.register(BuiltInRegistries.…)` at init | `DeferredRegister.Items` / `.DataComponents` |
| Creative tab | `CreativeModeTabEvents` | `BuildCreativeModeTabContentsEvent` |
| Payload registration | `PayloadTypeRegistry.playC2S()/playS2C()` + `Server/ClientPlayNetworking` receivers | `RegisterPayloadHandlersEvent` → `PayloadRegistrar…optional()` |
| Send | `ServerPlayNetworking.send` / `ClientPlayNetworking.send` | `PacketDistributor` / `ClientPacketDistributor` |
| "Can the peer hear me?" | `ServerPlayNetworking.canSend(player, type)`, `ClientPlayNetworking.canSend(type)` | `NetworkRegistry.hasChannel(listener, id)` |
| Server lifecycle / commands | `ServerLifecycleEvents`, `CommandRegistrationCallback` | `ServerStartingEvent`, `RegisterCommandsEvent` |
| Keybind | `KeyMappingHelper` | `RegisterKeyMappingsEvent` |
| Client tick | `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` |
| Graffiti geometry | `WrapperBlockStateModel.emitQuads` via `ModelLoadingPlugin` | `DynamicBlockStateModel.collectParts` via `ModelEvent.ModifyBakingResult` |

Every adapter forwards into `GraffitiServer` / `GraffitiClient`. Two places need a callback
rather than a direct call:

* **Registration**, because Fabric registers eagerly and NeoForge through a deferred
  register: `GraffitiItems.register(BiConsumer<Identifier, Item>)`, same shape for components.
* **Geometry hand-off**, because the two model APIs have different shapes. `:common` produces
  a `List<PaintQuad>` for a position; the loader turns it into FRAPI emitter calls or into
  `BakedQuad`s. Neither loader's API leaks into `:common`, and the geometry itself is written
  once.

No service-loader indirection — two loaders do not justify the ceremony.

---

## 4. Data model

### Canvas

A **canvas** is the paint on one face of one block: a 16×16 grid of bytes, one per texel.

```
alpha 0     empty (no paint); the RGB bytes are zero
alpha 255   painted, RGB is the exact colour
```

Four bytes per texel, 1 KB per painted face, fixed. `Canvas` holds the `int[256]` (ARGB), the
last painter's UUID and a timestamp. Alpha is stored rather than implied so that a future
partial-opacity feature does not need a storage migration; v1.0 writes only 0 or 255, which
is what keeps rendering in the cutout layer. Canvases are **replaced, not mutated in place** once published: a paint op
copies, stamps, and swaps the reference in the owning map. That is what makes it safe for
the client's chunk-mesher threads to read a canvas without locking — see [§6.2](#62-thread-safety).

`CanvasKey` packs a canvas address into a single `long` so canvases live in a `Long2ObjectMap`
with no allocation per lookup. The key is **chunk-local** — 3 bits face, 4 bits local Z, 4 bits
local X, then the full signed Y — because a world-absolute key does not fit: vanilla's own
`BlockPos` packing already uses all 64 bits (26 X + 12 Y + 26 Z), so there is no room for a face.
Every caller addresses a canvas relative to the `ChunkCanvases` that owns it, so nothing is lost.

**Face-local coordinates.** Each `Direction` gets a fixed (u, v) basis in `FaceAxes`, chosen so
that "up on the screen is up on the wall" for the four side faces, and so the mapping is the
same on client and server. The exact table is normative and lives in
[SPEC §4.2](SPEC.md#42-face-coordinate-system); putting it in one class means the eventual
"paint on slabs" feature changes one file.

### The brush is shared, deterministic code

`Brush.stamp(canvas, u8, v8, size, colorIndex, erase)` is the single implementation of what a
spray does, called by the **server** to apply the authoritative result and by the **client**
to predict it and to replay broadcast stamps. It must produce bit-identical output on both
sides, so:

* The hit point arrives quantised to 1/16 of a texel as two bytes (`u8`, `v8` in 0..255), not
  as a float — the network never carries a float that both sides then round differently.
* Coverage is an **integer** comparison of squared distances; no `sqrt`, no float, no
  platform-dependent rounding.
* The brush is a solid disc: a texel is painted when its centre is inside the radius. Paint is
  either on or off per texel, which is what keeps the renderer in the `CUTOUT` layer. (An earlier
  Bayer-dithered edge was meant to read as soft spray falloff; in game it read as speckle, and it
  made strokes dotty at the smallest size, so it was removed after playtesting.)
* A held drag carries the previous sample point *and the block it was on*, and `FaceStroke` walks
  the segment one quantisation unit at a time across every face it crosses. Two separate mistakes
  were needed to get this right. Sampling on a timer cannot draw a line at all — the gap between
  samples has to be filled deliberately. And filling it only *within one canvas* is nearly useless,
  because a canvas is 16 texels wide, so at normal drag speeds almost every sample lands on a new
  block: the result is still one disc per block. `StrokeApplier` is shared by the predicting client
  and the deciding server for the same reason the brush is — two implementations drift.

Because the same op replays identically everywhere, a spray costs **13 bytes on the wire**
(§7) instead of a canvas diff, and prediction cannot drift from authority.

### Item state

`simple_graffiti:spray_can`, stack size 1, with two data components:

* `minecraft:dyed_color` — the **vanilla** component, an arbitrary RGB int. Using vanilla's
  rather than a custom one is what makes dye mixing, item tinting and the tooltip free.
* charges — carried by vanilla `minecraft:damage` against `max_damage = 128`, so the vanilla
  durability bar, the item tooltip and the "breaks when exhausted" plumbing all work for
  free. An exhausted can is **not** destroyed; it stops painting (an item that vanishes
  mid-mural would be infuriating), which is why `SprayCanItem` checks charges itself rather
  than letting vanilla damage handling consume it.

The item model is one model whose paint layer is tinted by the `minecraft:dye` tint source.
Any of 16 million colours renders with a single texture and no client code — which is the
concrete payoff of storing colour in the vanilla component.

**Colour and charge are changed by separate recipes** ([SPEC §3.2](SPEC.md#32-refilling-and-recolouring)),
so a player never has to spend one to get the other:

* *Recolour* is a single `minecraft:crafting_dye` recipe. 26.2's `DyeRecipe` is data-driven — a
  `target` ingredient, a `dye` ingredient and a result — not hardcoded to armour, so one JSON
  gives the can leather-armour dye mixing through `DyedItemColor.applyDyes`. Arbitrary colours
  are therefore reachable in survival, at a crafting table, without any UI at all. (The
  serializer id is `crafting_dye`; a plain `minecraft:dye` is rejected at data load. The *tint
  source* on the item model is separately named `minecraft:dye`, which is an easy confusion.)
* *Refill* is a bundle-style inventory click: carry a magma cream, left-click the can. No
  crafting table, and it preserves the colour. Implemented with `overrideOtherStackedOnMe` and
  `overrideStackedOnOther`, the same hooks vanilla bundles use.

**Verification note:** whether a transmute recipe's result component patch is applied over the
copied input components (needed to reset `minecraft:damage` to 0) must be confirmed in-game on
26.2. If not, the fallback is a small custom recipe serializer in `:common` — one class, no
user-visible difference.

### The scrub sponge

`simple_graffiti:scrub_sponge` is a second item, `max_damage = 128`, crafted from a wet sponge
and an iron nugget. Use erases a brush-sized area; sneak-use clears the whole face.

It exists rather than reusing the vanilla wet sponge for two reasons. The first is a rule this
mod set itself: *add no behaviour to vanilla items* — a wet sponge that silently gained a new
right-click action is exactly the kind of surprise that makes mods hard to reason about, and
would collide with any other mod doing the same. The second is interaction budget: sneak-use
with the can is the eyedropper, so erasing needed its own verb anyway, and a dedicated item
gives it one that is discoverable in the creative tab and the recipe book.

Erasing runs through the *same* `PaintService` path as painting, with `value = 0` — same
validation, same permission check, same rate limit, same broadcast. Erasing someone's mural is
a build action too, so it must not be a back door around protection.

---

## 5. Server authority and data flow

```
 client                                   server
 ──────                                   ──────
 use on face
   │  local paintability + capability gate
   │  Brush.stamp(local copy)   ← optimistic, instant feedback
   │  mark section dirty
   └── PaintC2S(pos, face, u8, v8, size, flags) ─────►
                                            PaintService.validate:
                                              · capability + protocol version
                                              · player holds a charged can
                                              · reach ≤ 6 blocks, face is exposed & sturdy
                                              · block not denied, chunk under its canvas cap
                                              · permission mode allows it here
                                              · rate limiter has a token
                                            Brush.stamp(authoritative canvas)
                                            consume charge, mark chunk dirty
                     ◄───────── StampS2C(same 13 bytes + RGB) to every player
                                tracking that chunk (including the painter)
   apply stamp, remesh
   (idempotent — replaying our own prediction is a no-op)

 rejected?          ◄───────── CanvasSyncS2C for that one face (authoritative repair)
```

Rejection repairs the client with the real canvas rather than sending "no": the client cannot
be left holding a ghost, and a single-face sync is RLE over 1 KB — a few dozen bytes for a
real canvas.

The same repair covers the one case where replaying stamps does not converge: two players
painting the same face at once see their own stamp applied first and the other's second, in
opposite orders. The server detects two painters on one canvas within 20 ticks and sends a
full face instead of a stamp ([SPEC §6.1](SPEC.md#61-client-prediction)). Prediction is a
latency trick, not a consistency model, and this is where it has to yield.

**Chunk lifecycle.** `PlayerChunkSenderMixin` fires when a chunk is sent to a player →
`CanvasSyncS2C` for that chunk if it has any canvases and if the player's channel is open;
and when a chunk is dropped → the client discards it. Chunk load/unload on the server drives
`CanvasStore`: an async region read on load, a write on unload and on autosave.

**Block changes.** Breaking or replacing a block clears its six canvases and broadcasts
`ClearS2C`. This is hooked from the server-side block-change path rather than from a client
prediction, so a cancelled break does not wipe paint.

---

## 6. Rendering

### 6.1 Paint is part of the block's model

Every baked block-state model is wrapped at model-load time. When the wrapper is asked for
the geometry of a block *at a position*, it delegates to the wrapped model and then appends
graffiti quads for whichever of that block's six faces are painted:

```
Fabric    ModelLoadingPlugin → modifyBlockModelAfterBake → WrapperBlockStateModel
            .emitQuads(QuadEmitter, level, pos, state, random, cullTest)
              ├─ super.emitQuads(…)           # the real block
              └─ CanvasMesher quads → emitter.pos/uv/color/cullFace/emit

NeoForge  ModelEvent.ModifyBakingResult → DynamicBlockStateModel
            .collectParts(level, pos, state, random, parts)
              ├─ wrapped.collectParts(…)      # the real block
              └─ parts.add(paintPart)         # BakedQuads built from the canvas, cached
```

`CanvasMesher` is shared and does the actual work, per canvas:

1. **Greedy-merge** runs of equal ARGB into maximal rectangles (rows first, then
   merge identical adjacent rows). A typical tag of one or two colours collapses from ~200
   texels into a handful of rectangles; the worst case (every texel a different colour) is
   bounded at 256.
2. Produce one `PaintQuad` per rectangle on the face plane, offset **0.005 blocks** along the
   face normal to avoid z-fighting, carrying the texel ARGB and UVs into a single
   `simple_graffiti:paint/spray` sprite in the block atlas (a subtle grain, sampled
   continuously across the face so it does not repeat per texel).
3. Emit with cutout material flags and `cullFace = null` (the quad floats just off the
   surface, so it must not be culled with the face it sits on) and `nominalFace = dir` so
   lighting and AO come out right.

Lighting, AO, culling, fog, sorting and the section mesh itself are all handled by the
pipeline, because as far as it is concerned this *is* block geometry.

Cost model: **zero per-frame work**. The wrapper costs one `Long2ObjectMap` lookup per block
per section rebuild, which early-outs for the unpainted 99.9% of blocks; a canvas change costs
one section rebuild, which is exactly what vanilla does for a block placement. On Fabric,
`createGeometryKey` returns the canvas identity so FRAPI (and Sodium's implementation of it)
can cache position-dependent geometry instead of re-emitting it; on NeoForge the built
`BakedQuad` list is cached per canvas by us.

### 6.2 Thread safety

Section building runs on worker threads. `ClientCanvasStore` is therefore read directly from
those workers: a `Long2ObjectMap` per chunk inside a `ConcurrentHashMap`, holding canvases
that are **never mutated after publication**. A worker sees either the old canvas or the new
one, never a half-stamped one. Reading a canvas one rebuild stale is harmless — the
`setSectionDirtyWithNeighbors` call that accompanied the change schedules another rebuild.

### 6.3 Rejected alternatives

**A mixin into `SectionCompiler.compile`.** 26.2 builds a section's `Map<ChunkSectionLayer,
MeshData>` through a private static `getOrBeginLayer(…)`, which a mixin can reach with a
MixinExtras `@Local` capture and an `@Invoker`. It has one real advantage — a single
implementation in `:common`, no per-loader adapter — and two disqualifying costs. It injects
into a private method with captured locals, so every 26.x point release is a re-derivation;
and chunk-meshing replacement mods (Sodium class) never call it, so under them the mixin
loads, applies, and then silently never fires. That failure mode is the worst kind: no crash,
no log line, paint saved and synced correctly but simply absent from the screen, reported by
users as "the mod does nothing".

**A per-frame pass.** Both loaders can draw custom world geometry per frame (Fabric
`LevelRenderEvents` + `SubmitRenderPhases.AFTER_TERRAIN`, NeoForge
`RenderLevelStageEvent.AfterOpaqueBlocks`). It needs no mixin and is Sodium-safe, but the
cost is per-canvas-per-frame: a painted town centre is thousands of canvases each needing a
matrix, a light lookup and a buffer write *every frame*, plus its own culling. It stays the
fallback of last resort, and `CanvasMesher` is written so it could be driven from there
unchanged.

### 6.4 Compatibility with rendering mods

Because graffiti is emitted through the standard model pipeline rather than around it, mods
that replace terrain meshing consume it like any other block geometry: Sodium implements
FRAPI on Fabric, and NeoForge's model extensions are what NeoForge mods already rely on. That
is the main reason this route was chosen over the mixin.

Two caveats, stated rather than assumed:

* Sodium's 26.2 status could not be verified from this machine (26.x is new). The claim above
  is structural — "it consumes the model pipeline" — and MUST be re-checked against a real
  build before release; it is on the acceptance list ([SPEC §13](SPEC.md#13-acceptance-criteria)).
* Wrapping every block-state model means composing with other model-wrapping mods
  (connected textures, CTM-style packs). Wrappers compose by delegation and this is the
  intended use of both APIs, but a wrapper that fails to delegate correctly will drop either
  our paint or the block. Registering in Fabric's `WRAP_LAST_PHASE` and delegating
  unconditionally is the mitigation.

---

## 7. Protocol and graceful degradation

Five payloads under the `simple_graffiti:` namespace, all **optional** channels, wire format
normative in [SPEC §7](SPEC.md#7-network-protocol). Sizes: a spray is 13 bytes serverbound
and 13 clientbound; a full canvas is 4 + RLE(256) bytes.

Degradation is a state machine on the client with exactly two states:

```
NONE  ── HelloS2C with a compatible protocol version ──►  READY
READY ── disconnect / world unload ──►  NONE
```

Everything the client does — rendering, painting, erasing, storing, the picker's "apply" —
is gated on `READY`. On `NONE` the can is an inert item: right-click does nothing, shows one
action-bar message per session, and sends no packet.

Both directions are covered by the same rule — **never send to a peer that has not declared
the channel**:

| Situation | Behaviour |
| :--- | :--- |
| Singleplayer | The integrated server has the mod; handshake happens over the memory connection; full function. |
| Modded client, vanilla server | No `HelloS2C` ever arrives → `NONE`. Fabric additionally guards every send with `ClientPlayNetworking.canSend`, NeoForge with `NetworkRegistry.hasChannel`. The item exists client-side but cannot be obtained (the server has no recipe) and does nothing if given in creative. |
| Modded server, mod-less client | **Not supported, and not something the mod tries to fix.** Registering items means a server running this mod requires it on clients, exactly like any content mod: NeoForge refuses such a client during negotiation, and on Fabric it would meet unresolvable item ids the moment a spray can enters its view. What the mod *does* guarantee is narrower and testable — it never sends a payload to a connection that has not declared the channel (`canSend` / `hasChannel` per player, per payload), so it is never itself the cause of a disconnect or an error. `optional()` registration on NeoForge keeps the mod's own channels out of the negotiation's required set. |
| Protocol version mismatch | Treated exactly as "no mod": the client stays `NONE`, logs one line, and the server stops sending to it. |

The *other* direction is the one this mod genuinely supports, and it is the original design
goal: registering items does not endanger a modded client's connection to a **vanilla**
server, because modded item ids are appended after the vanilla ones, leaving vanilla registry
ids decoding correctly. That is standard behaviour, but it is on the acceptance list
([SPEC §13](SPEC.md#13-acceptance-criteria)) because it is exactly the kind of thing that
silently breaks.

---

## 8. Storage

Graffiti lives beside vanilla's own chunk data, in its own region files:

```
<world>/<dimension>/simple_graffiti/r.<x>.<z>.mca
```

via `SimpleRegionStorage`, the same class vanilla uses for `region/`, `entities/` and
`poi/` — which means chunk-granular async reads, zlib compression, the region-file crash
resilience, and no bespoke file format to get wrong.

Why not `SavedData`: a `SavedData` is loaded whole and held in memory for the life of the
dimension. On a long-lived server with a painted spawn town, that is a permanent resident
heap cost proportional to the *total* paint ever created, whereas graffiti is naturally
chunk-scoped data with exactly the same lifecycle as the chunk it decorates.

Per-chunk NBT is a list of `{X, Y, Z, F, Data(256 bytes), Owner, Time}`; the region file's own
compression takes care of the fact that most canvases are mostly one value. Format and
tolerances in [SPEC §8](SPEC.md#8-storage-format).

Failure posture, matching `RedfxConfig`/`TodoStore`: an unreadable chunk entry logs once and
yields *no graffiti* for that chunk. It is never allowed to fail chunk loading, and a chunk
whose data failed to read is not overwritten until something paints in it.

---

## 9. Server-side operation

Painting is a build action, so it must be governable like one.

* **Permission modes** — `ANYONE` (default), `OPS_ONLY`, or `BUILD_PERMISSION`: the last
  delegates to the server's own "may this player modify this block" check, so land-protection
  mods that hook block placement govern graffiti for free without an integration API.
* **Rate limiting** — a per-player token bucket in `RateLimiter`, refilled at the configured
  sprays/second, checked before any work. Bounded first so packet spam costs a map lookup.
* **Caps** — max canvases per chunk (default 1 024) bounds worst-case memory, disk and mesh
  cost; over the cap, new *faces* are refused while existing ones stay paintable.
* **Tags** — `#simple_graffiti:paintable` and `#simple_graffiti:not_paintable` let a pack
  restrict surfaces without code.
* **Commands** — `/graffiti clear <radius>`, `/graffiti clear player <name>`,
  `/graffiti stats`, `/graffiti reload`, `/graffiti enable|disable` (the `COMMANDS_GAMEMASTER`
  permission — 26.2 replaced numeric op levels with named permissions, and that is the old
  level 2). The
  per-canvas owner UUID exists precisely so `clear player` can work after the fact.

Config is `config/simple_graffiti/server.json`, Gson, field-by-field repair on load, the same
defensive posture as Checkbox's config. Client config is
`config/simple_graffiti/client.json` and holds only rendering/UX preferences — no client
setting can affect what the server accepts.

---

## 10. Build, verification, publishing

Copied from Checkbox, which encodes hard-won knowledge about this toolchain:

* `gradle.properties` is the single source of version truth: `minecraft_version=26.2`,
  `loader_version=0.19.3`, `loom_version=1.17-SNAPSHOT`, `neoforge_version=26.2.0.45-beta`,
  `fabric_api_version=0.154.0+26.2`, `mc_version_suffix=mc26.2.x`,
  `maven_group=com.drinfonty.simplegraffiti`. Gradle 9.5.1, Java 25 toolchain,
  `options.release = 25` in all three modules.
* `:fabric` uses `loom.splitEnvironmentSourceSets()` and declares the mod over
  `main` + `client` + `project(":common").main`.
* `:common` adds `compileOnly "net.fabricmc:fabric-loader"` purely to silence
  `EnvType.CLIENT` annotation warnings, and `compileOnly "org.spongepowered:mixin"`.
* Jars land in `release/` as `simple-graffiti-<version>-mc26.2.x-<loader>.jar`; `:fabric`
  also copies to the local `mods/` folder.
* **`-PtestJar` is mandatory pre-publish verification on both loaders**, with the NeoForge
  caveat Checkbox documents: under `-PtestJar` the `mods { … sourceSet … }` block must be
  omitted and the jar staged in an isolated `run-testjar/`, or FML's in-dev folder locator
  wins and silently tests the loose classes.
* Unlike Checkbox, this mod has a server side, so verification also requires a **dedicated
  server run** on both loaders (`:fabric:runServer`, `:neoforge:runServer`) with at least two
  clients, plus one mod-less client. Singleplayer alone cannot exercise the degradation
  paths, the chunk-send hook, or the broadcast path.
* `-Dsimple_graffiti.debug=true` gates verbose diagnostics, set only by non-`testJar` dev
  runs, compiled out via a `static final boolean` guard.
* Modrinth publishing via Minotaur, token from `MODRINTH_TOKEN` or `local.properties`,
  rehearsable with `-PmodrinthDebug`.

### Automated tests

`:common` gets a JUnit 5 `test` source set over the parts with no Minecraft dependency, which
here is most of the risky logic:

* `Brush` determinism — the same op from the same inputs produces byte-identical canvases,
  including every brush size and edge case at the canvas border, plus the invariant that a fast
  drag paints byte-identically to an infinitely slow one.
* `FaceAxes` — hit point → texel for all six faces, including the corners and the seams.
* `CanvasCodec` — NBT and network round-trips, RLE of pathological canvases, and rejection of
  malformed input without throwing.
* Greedy meshing — quad count and coverage equivalence against a naive per-texel reference.
* `RateLimiter` — refill behaviour and burst bounds.

These are exactly the places where a regression is invisible in a playtest: a brush that
rounds differently on the client than the server looks like "lag", not like a bug.

---

## 11. Branches & porting

`main` targets 26.2. `legacy-26.1` comes later and follows RedFX's cherry-pick workflow
(implement on `main`, cherry-pick, re-derive anything touching build config or mappings).

| Branch | Built against | Status |
| :--- | :--- | :--- |
| **`main`** | **26.2** | active |
| `legacy-26.1` | 26.1.2 | planned (v1.1) |

Flagged now as unlikely to cherry-pick cleanly:

1. **The section-compiler injection.** 26.1 predates parts of the render-pipeline rework;
   the two model APIs must be re-verified against the 26.1.2 jar and the matching loader
   releases (both are already in the Gradle caches). All the *geometry* logic in
   `CanvasMesher` should port untouched; the two adapters may not.
2. **Fabric mappings.** If the legacy branch needs `fabric-loom-remap` and publishes
   `remapJar`'s intermediary output, `-PtestJar` is not valid verification there; it needs
   `:fabric:runProdClient`, exactly as RedFX documents.

Everything else — canvas model, brush, protocol, storage, item, commands — is written against
APIs that are stable across 26.x and should move by cherry-pick.

---

## 12. Roadmap

| Version | Contents |
| :--- | :--- |
| **1.0.0** | Spray can + scrub sponge, arbitrary RGB with picker/dye/eyedropper, server-authoritative canvases, region-file storage, chunk sync, model-pipeline rendering, degradation on vanilla servers, server config, `/graffiti` commands |
| **1.1.0** | `legacy-26.1`, stencils/stamps, bigger brushes, opt-in weathering, paint moved by pistons, audit log |
| **1.2.0** | Region export/import, partial-block surfaces, protection-mod integrations |

---

## 13. Open questions

* **Charges (128), the 250 ms drain interval and sponge durability (128)** are play-tested
  guesses. A
  mural should feel like it costs something without becoming an inventory-management chore.
* Should a can be craftable *pre-coloured* by including a dye in the recipe? The supplied
  recipe has no dye slot, so v1.0 crafts white and recolours afterwards.
* Should the scrub sponge be craftable back from a broken one, or is 128 uses enough that
  nobody notices? Currently it just breaks.
* Is 1 024 canvases/chunk the right cap? It is 1 MB of paint per chunk uncompressed, far less
  on disk; the real limit may end up being what a section rebuild can chew through.
* Per-texel ARGB costs 4× a 16-entry palette. If painted worlds turn out heavier than
  expected, a per-canvas palette recovers most of it — at the cost of an eviction rule and a
  replay-order hazard, which is why it was not chosen up front.
