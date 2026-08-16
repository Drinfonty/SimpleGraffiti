# Specification: Simple Graffiti

Simple Graffiti is a server + client Minecraft mod that adds a spray can which paints 16×16
pixel graffiti onto block faces.

This document is normative: it defines observable behaviour, data formats, defaults and
acceptance criteria. Rationale and architecture live in [DESIGN.md](DESIGN.md); scope and
requirement IDs live in [REQUIREMENTS.md](REQUIREMENTS.md).

Keywords: **MUST**, **SHOULD**, **MAY**.

---

## 1. Environment & support

* **Modern branch (`main`)**
  * **Minecraft**: 26.2 (Java 25)
  * **Fabric**: Loader `>=0.19.3`, Loom `1.17-SNAPSHOT`, Fabric API `0.154.0+26.2`
  * **NeoForge**: `26.2.0.45-beta`, ModDev `2.0.143`
  * **Gradle**: 9.5.1
* **Planned branch**: `legacy-26.1` (26.1.2) — not in scope for v1.0.
* **Sides**: The mod MUST run on a dedicated server, an integrated server, and a client. It
  MUST NOT require the client mod for a server to boot, nor a server mod for a client to
  boot. Client-only classes MUST NOT be reachable from any server code path.
* **Mod identity**: mod id `simple_graffiti`, group `com.drinfonty.simplegraffiti`, display
  name `Simple Graffiti`.
* **Dependencies**: Fabric API (Fabric only). No other mod dependency.
* **Artifacts**: `simple-graffiti-<version>-mc26.2.x-fabric.jar`,
  `simple-graffiti-<version>-mc26.2.x-neoforge.jar`.

---

## 2. Palette

The palette is exactly the 16 vanilla dye colours. Canvas values are `paletteIndex + 1`;
`0` means "no paint".

| Value | `DyeColor` | Value | `DyeColor` |
| :--- | :--- | :--- | :--- |
| 1 | `WHITE` | 9 | `LIGHT_GRAY` |
| 2 | `ORANGE` | 10 | `CYAN` |
| 3 | `MAGENTA` | 11 | `PURPLE` |
| 4 | `LIGHT_BLUE` | 12 | `BLUE` |
| 5 | `YELLOW` | 13 | `BROWN` |
| 6 | `LIME` | 14 | `GREEN` |
| 7 | `PINK` | 15 | `RED` |
| 8 | `GRAY` | 16 | `BLACK` |

Values `17..255` are reserved and MUST be treated as `0` on read, without error.

Rendered colour MUST be `DyeColor.getTextureDiffuseColor()` (the same RGB vanilla uses for
dyed leather and wool tinting), so paint matches the block of the same colour.

---

## 3. The spray can

`simple_graffiti:spray_can`. Stack size 1. `max_damage = 64`. Not enchantable, not repairable
in an anvil, no `minecraft:tool` component.

**Components**

| Component | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `simple_graffiti:paint_color` | `DyeColor` | `WHITE` | The colour sprayed |
| `minecraft:damage` | int | `0` | Charges used; remaining charges = `64 − damage` |

* A can at 0 remaining charges MUST NOT be destroyed and MUST NOT paint. It MUST show the
  action-bar message `message.simple_graffiti.empty` when used.
* In creative mode, charges MUST NOT be consumed.
* The tooltip MUST show the colour name and `charges/64`.
* The item model MUST be selected from the colour component by a `minecraft:select` model on
  `minecraft:component` (`simple_graffiti:paint_color`), each variant a `minecraft:constant`
  tint over one shared texture.

### 3.1 Crafting

Shipped verbatim as supplied, at `data/simple_graffiti/recipe/spray_can.json`:

```json
{
  "type": "minecraft:crafting_shaped",
  "pattern": [
    " = ",
    "#M#",
    "#u#"
  ],
  "key": {
    "=": "minecraft:iron_ingot",
    "#": "minecraft:iron_nugget",
    "M": "minecraft:magma_cream",
    "u": "minecraft:water_bucket"
  },
  "result": {
    "id": "simple_graffiti:spray_can"
  }
}
```

* The crafted can MUST be `WHITE` with full charges (`damage = 0`).
* The water bucket MUST leave an empty bucket in the grid (vanilla crafting-remainder
  behaviour); the recipe MUST NOT be modified to work around this.
* An unlock advancement MUST be provided so the recipe appears in the recipe book on
  obtaining a magma cream.

### 3.2 Refilling and recolouring

* Crafting a can with any dye MUST produce a can of that colour with `damage = 0`, preserving
  nothing else that would be lost, via 16 `minecraft:crafting_transmute` recipes at
  `data/simple_graffiti/recipe/spray_can_<colour>.json`.
* If `crafting_transmute` on 26.2 does not apply the result's component patch over the copied
  input components, a custom recipe serializer MUST be used instead; the observable behaviour
  above is what is normative, not the mechanism.
* A can MAY additionally be recoloured in-world (§5.3), which does **not** refill it.

---

## 4. Canvases

### 4.1 Definition

A **canvas** is the paint on one face of one block: 256 bytes, one per texel, row-major with
index `= v * 16 + u`. A canvas also carries the UUID of the last player to modify it and the
epoch-millis timestamp of that modification.

* A canvas MUST be discarded when every texel is `0`.
* A canvas MUST be discarded when its block is broken, replaced, or has its state changed
  such that the face is no longer paintable (§5.1).
* Canvases MUST NOT move with the block (pistons destroy them in v1.0).
* Once published to another thread, a canvas's byte array MUST NOT be mutated; changes
  replace the canvas.

### 4.2 Face coordinate system

Given a hit position inside the block as local coordinates `(lx, ly, lz)` in `[0,1]`, face
coordinates `(u, v)` in `[0,1]` are:

| Face | `u` | `v` |
| :--- | :--- | :--- |
| `NORTH` (−Z) | `1 − lx` | `1 − ly` |
| `SOUTH` (+Z) | `lx` | `1 − ly` |
| `WEST` (−X) | `lz` | `1 − ly` |
| `EAST` (+X) | `1 − lz` | `1 − ly` |
| `UP` (+Y) | `lx` | `lz` |
| `DOWN` (−Y) | `lx` | `1 − lz` |

`u` increases to the right and `v` increases downwards as seen by a player looking at the
face; for `UP`/`DOWN`, `v` increases southwards. The texel is
`pu = clamp(floor(u × 16), 0, 15)`, `pv = clamp(floor(v × 16), 0, 15)`.

This table is the single definition of face orientation and MUST be used identically by the
client, the server and the renderer.

### 4.3 Brush

Sprays are quantised to **1/16 of a texel**: `u8 = clamp(floor(u × 256), 0, 255)`, likewise
`v8`. These bytes are what travel on the wire; no float ever crosses the network.

Brush sizes and radii, in 1/16-texel units:

| Size | Id | Radius `r` | Texels |
| :--- | :--- | :--- | :--- |
| Small | `0` | `24` | 1.5 |
| Medium (default) | `1` | `40` | 2.5 |
| Large | `2` | `64` | 4.0 |

`Brush.stamp(canvas, u8, v8, size, value)` MUST be implemented exactly as:

```
r  = radius(size)
for pv in clamp(floor((v8 - r) / 16), 0, 15) .. clamp(floor((v8 + r) / 16), 0, 15):
  for pu in clamp(floor((u8 - r) / 16), 0, 15) .. clamp(floor((u8 + r) / 16), 0, 15):
    dx = (pu * 16 + 8) - u8
    dy = (pv * 16 + 8) - v8
    d2 = dx*dx + dy*dy                       # integer
    t  = BAYER4[pv & 3][pu & 3]              # 0..15
    if 256 * d2 < r*r * (16 - t) * (16 - t): # integer comparison, no sqrt, no float
        canvas[pv * 16 + pu] = value         # value = paletteIndex+1, or 0 to erase
```

with the standard 4×4 Bayer matrix:

```
BAYER4 = [[ 0,  8,  2, 10],
          [12,  4, 14,  6],
          [ 3, 11,  1,  9],
          [15,  7, 13,  5]]
```

* The result MUST be bit-identical on client and server for identical inputs. No
  floating-point arithmetic is permitted anywhere in this function.
* Paint MUST NOT spill onto adjacent faces or adjacent blocks: texels outside `0..15` are
  clipped, not wrapped.
* Erasing uses `value = 0` and is otherwise identical.
* A stamp that changes no texel MUST NOT consume a charge and MUST NOT be broadcast.

---

## 5. Painting

### 5.1 Paintable surfaces

A face `(pos, dir)` is paintable if **all** hold:

1. `state.isFaceSturdy(level, pos, dir)` is true;
2. `state.isCollisionShapeFullBlock(level, pos)` is true;
3. the neighbouring block at `pos.relative(dir)` does not occlude that face
   (`isFaceSturdy` on the opposing face is false), i.e. the face is visible;
4. the block is not in `#simple_graffiti:not_paintable` (default contents: none) and, when
   `restrictToTag` is enabled, is in `#simple_graffiti:paintable`;
5. the block is not a block entity with an interactive use action (chests, furnaces, signs,
   doors, beds, and anything whose right-click has an effect) — the spray MUST NOT pre-empt
   vanilla interaction; sneak-use with a can, which suppresses vanilla interaction, MAY still
   paint such blocks if they otherwise qualify;
6. fluids are never paintable.

A use on a non-paintable face MUST do nothing: no charge, no packet, no message beyond the
optional `message.simple_graffiti.not_paintable` action bar (rate-limited to one per second).

### 5.2 Spraying

* Use (right-click) on a paintable face applies one stamp of the can's colour at the hit
  point, and consumes one charge.
* Holding use MUST repeat every **5 ticks** (4 sprays/second) for as long as use is held and
  the crosshair is on a paintable face, consuming one charge per stamp.
* Sneak-use MUST erase instead of paint, and MUST NOT consume a charge.
* Each stamp MUST play `entity.generic.extinguish_fire` at volume 0.3 and pitch 1.6 ± 0.1,
  and MUST emit 1–2 `minecraft:dust` particles of the paint colour at the hit point. Sounds
  MUST be rate-limited to at most one per 5 ticks per player.
* Painting MUST NOT swing the arm more than once per stamp, and MUST NOT trigger block
  breaking, block placing, or item use of any other kind.
* Reach: the server MUST reject any paint whose target is further than the player's
  interaction range plus 1 block, or in a chunk the player does not track.

### 5.3 Colour selection

* Sneak-use on a block whose colour maps to a `DyeColor` (wool, carpet, concrete, concrete
  powder, terracotta, glazed terracotta, stained glass, stained glass pane, shulker box,
  candle, bed, banner) MUST set the held can's colour to that colour, play
  `block.note_block.hat`, and MUST NOT paint or erase on that use.
* The **palette key** (default `G`, `key.simple_graffiti.palette`) opens `PaletteScreen`,
  which shows the 16 colours, the three brush sizes, and remaining charges. Choosing a colour
  sends `set_color` (§7.6). The screen MUST be openable only while holding a can and only
  when the server capability is `READY`.
* Brush size is a **client-side** preference, sent with each paint request and clamped
  server-side to `maxBrushSize`.

### 5.4 Removal

* Using a **wet sponge** on a painted face clears that entire face, converts the sponge to a
  dry sponge, and plays `block.sponge.absorb`.
* Breaking or replacing a block MUST clear all six of its canvases.
* `/graffiti clear` (§9) clears by radius or by player.
* Explosions, fire, water flow and rain MUST NOT clear paint in v1.0.

---

## 6. Server authority

Every paint is a **request**. The server MUST validate, in this order, and MUST silently drop
(no reply beyond the correction of §6.2) on any failure:

1. the mod is enabled (`enabled = true`);
2. the payload is well-formed and within bounds (§7);
3. the player tracks the target chunk;
4. the target is within reach (§5.2);
5. the face is paintable (§5.1);
6. permission allows it (§9.1);
7. the player holds a spray can in the stated hand with ≥ 1 charge (or is in creative);
8. the rate limiter has a token (§9.2);
9. the chunk is under `maxCanvasesPerChunk`, or the face already has a canvas.

On success the server applies `Brush.stamp` to the authoritative canvas, consumes the charge,
records owner + timestamp, marks the chunk dirty, and broadcasts `stamp` (§7.3) to every
player tracking that chunk **whose connection has the channel**, including the painter.

### 6.1 Client prediction

The client MUST apply the stamp locally before sending, and MUST NOT wait for the broadcast
to render it. Replaying its own broadcast stamp MUST be a no-op (the operation is
idempotent).

### 6.2 Correction

When the server rejects a paint that the client is likely to have predicted (failures 5–9
above), it MUST send `canvas_sync` (§7.4) containing just that face's authoritative canvas —
or an explicit empty canvas — so no ghost paint can persist. Corrections MUST be rate-limited
to at most 4 per second per player.

---

## 7. Network protocol

Protocol version **1**. All payloads are in the `simple_graffiti` namespace and MUST be
registered as **optional** channels (`PayloadRegistrar.optional()` on NeoForge). Neither side
may send a payload to a peer that has not declared the channel
(`ServerPlayNetworking.canSend` / `ClientPlayNetworking.canSend` on Fabric;
`NetworkRegistry.hasChannel` on NeoForge).

`BlockPos` is encoded as vanilla's packed `long`. `Direction` is its `get3DDataValue()`
(0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST). All lengths are VarInts.

### 7.1 `simple_graffiti:hello` (S2C, play)

Sent once, on player join, only to players whose connection has the channel.

| Field | Type | Notes |
| :--- | :--- | :--- |
| `protocolVersion` | VarInt | `1` |
| `flags` | byte | bit 0: painting enabled |
| `maxBrushSize` | byte | `0..2` |
| `maxCanvasesPerChunk` | VarInt | informational; the server still enforces it |

A client that receives a `protocolVersion` it does not implement MUST log one line and remain
in capability state `NONE` (§10).

### 7.2 `simple_graffiti:paint` (C2S, play) — 13 bytes

| Field | Type |
| :--- | :--- |
| `pos` | long |
| `face` | byte `0..5` |
| `u8` | byte `0..255` |
| `v8` | byte `0..255` |
| `brush` | byte `0..2` |
| `flags` | byte — bit 0: erase, bit 1: offhand |

Out-of-range `face` or `brush` MUST cause the packet to be dropped, not clamped.

### 7.3 `simple_graffiti:stamp` (S2C, play) — 14 bytes

The `paint` fields plus `value` (byte, `0..16`; `0` = erase). Recipients apply
`Brush.stamp` with exactly these arguments.

### 7.4 `simple_graffiti:canvas_sync` (S2C, play)

| Field | Type | Notes |
| :--- | :--- | :--- |
| `chunkX`, `chunkZ` | VarInt | |
| `replace` | boolean | true = discard this chunk's known canvases first |
| `count` | VarInt | ≤ 512 per payload |
| entries × `count` | | |
| — `xz` | byte | `(localX << 4) \| localZ` |
| — `y` | VarInt | zig-zag, absolute world Y |
| — `face` | byte | |
| — `data` | VarInt length + bytes | RLE (§7.7) |

A chunk with more than 512 canvases is sent as multiple payloads; only the first has
`replace = true`.

### 7.5 `simple_graffiti:clear` (S2C, play)

| Field | Type | Notes |
| :--- | :--- | :--- |
| `scope` | byte | 0 = face, 1 = block, 2 = chunk |
| `pos` / `chunkX,chunkZ` | long / VarInt ×2 | per scope |
| `face` | byte | scope 0 only |

### 7.6 `simple_graffiti:set_color` (C2S, play)

| Field | Type |
| :--- | :--- |
| `colorIndex` | byte `0..15` |
| `hand` | byte, 0 = main, 1 = off |

The server MUST verify the player holds a spray can in that hand before applying, and MUST
ignore the packet otherwise.

### 7.7 RLE encoding

A canvas is encoded as pairs `(count: unsigned byte 1..255, value: byte)`. The decoded length
MUST be exactly 256; anything else MUST be rejected as malformed (the payload dropped, the
canvas left unchanged, one log line). Worst case is 512 bytes, so a canvas is never sent raw.

### 7.8 Bandwidth bounds

* A continuous sprayer generates ≤ 4 × 14 bytes/s = 56 B/s per observer.
* Chunk sync for a fully-painted 2 048-canvas chunk is ≤ 1 MB uncompressed pre-RLE and MUST
  be spread over at least 4 payloads; the connection's own compression applies on top.

---

## 8. Storage format

```
<world>/<dimension>/simple_graffiti/r.<x>.<z>.mca
```

written through `SimpleRegionStorage` with `RegionStorageInfo(levelId, dimension,
"simple_graffiti")`. One NBT compound per chunk:

```
{
  Version: 1,
  Canvases: [
    { X: 7b, Z: 3b, Y: 71, F: 2b, D: [B; 256 bytes], O: [I; 4 ints], T: 1765200000000L }
  ]
}
```

* `X`, `Z` are chunk-local `0..15`; `Y` is absolute world Y; `F` is the 3D data value.
* `D` is the raw 256-byte canvas (region-file compression handles the redundancy).
* `O` is the last painter's UUID as vanilla's 4-int encoding; `T` is epoch millis. Both MAY
  be absent, in which case the canvas is still loaded.
* Reads are asynchronous and MUST NOT block the server thread. Writes happen on chunk unload,
  on autosave, and on world save.
* An unreadable or malformed chunk entry MUST log once, yield **no canvases** for that chunk,
  MUST NOT fail chunk loading, and MUST NOT be overwritten until something paints in that
  chunk.
* Entries whose `Y` is outside the dimension's build range, whose `X`/`Z` are out of range, or
  whose `D` is not exactly 256 bytes MUST be dropped individually, keeping the rest.

---

## 9. Server configuration and commands

### 9.1 `config/simple_graffiti/server.json`

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "permissionMode": "ANYONE",
  "spraysPerSecond": 6,
  "burstSprays": 12,
  "maxCanvasesPerChunk": 2048,
  "maxBrushSize": 2,
  "restrictToTag": false,
  "chargesPerCan": 64,
  "eraseCostsCharge": false,
  "wetSpongeClearsFace": true,
  "clearOnBlockBreak": true
}
```

* `permissionMode` ∈ `ANYONE` | `OPS_ONLY` | `BUILD_PERMISSION`. `BUILD_PERMISSION` MUST
  delegate to the server's own "may this player modify this block" check, so protection mods
  that hook block placement govern painting with no integration code.
* Loading MUST tolerate a missing file, empty file, malformed JSON, unknown fields and
  out-of-range values; each invalid field falls back to its default and the file is
  rewritten. A corrupt config MUST NOT prevent the server from starting.
* Changes take effect on `/graffiti reload`; `enabled = false` MUST stop all painting and all
  sync immediately, leaving stored canvases untouched.

### 9.2 Rate limiting

Per player: a token bucket of capacity `burstSprays`, refilled at `spraysPerSecond`. The
check MUST happen before any canvas lookup or allocation. Exceeding it drops the packet
silently (no correction, no message) — a legitimate client cannot exceed it, since it sprays
at 4/s.

### 9.3 `config/simple_graffiti/client.json`

```json
{
  "schemaVersion": 1,
  "renderGraffiti": true,
  "brushSize": 1,
  "showPaintParticles": true,
  "paletteKeyOpensOnHoldOnly": false
}
```

No client setting may affect what the server accepts. `renderGraffiti = false` MUST stop
rendering without disconnecting and MUST trigger a remesh of loaded sections.

### 9.4 Commands

Registered under `/graffiti`, permission level 2 unless stated.

```
/graffiti clear radius <blocks>            # 1..128, centred on the sender
/graffiti clear player <name>              # every canvas last painted by that player, in loaded chunks
/graffiti clear chunk                      # the sender's chunk
/graffiti stats                            # canvases loaded, chunks with paint, bytes in memory
/graffiti enable | disable
/graffiti reload
```

* Every clear MUST report the number of canvases removed and MUST broadcast the resulting
  `clear` payloads to tracking players.
* `clear player` operates on loaded chunks only; this MUST be stated in its output.

---

## 10. Degradation

Client capability is a two-state machine: `NONE` (default) → `READY` on a compatible
`hello` → `NONE` on disconnect or world unload.

| Situation | Required behaviour |
| :--- | :--- |
| Singleplayer | Full function over the memory connection. |
| Modded client, server without the mod | Capability stays `NONE`. No rendering, no local canvas state, no packet ever sent. Using a can shows `message.simple_graffiti.no_server` on the action bar **once per session** and does nothing else. No exception, no disconnect, no log spam. |
| Modded server, client without the mod | The client is never sent any payload (checked per player, per payload). It is never disconnected because of this mod. It sees unpainted blocks and can play normally. |
| Protocol version mismatch | Identical to "server without the mod", plus one log line on each side. |
| `enabled = false` server-side | `hello` is still sent with the painting-enabled flag clear; the client renders existing graffiti but refuses to paint, showing `message.simple_graffiti.disabled`. |

The mod MUST NOT alter the behaviour of any vanilla item, block, recipe or packet, and MUST
NOT send any non-vanilla packet while in `NONE`.

---

## 11. Performance requirements

* Graffiti MUST be emitted through the block model pipeline, as part of the painted block's
  own geometry: Fabric `WrapperBlockStateModel.emitQuads`, NeoForge
  `DynamicBlockStateModel.collectParts`. It MUST NOT be drawn by a per-frame pass, and MUST
  NOT be injected into the vanilla section compiler.
* Rendering MUST therefore cost **zero per-frame work per canvas**; geometry is produced only
  when a section is rebuilt.
* The model wrapper MUST early-out for an unpainted block in one O(1) map lookup with no
  allocation, and MUST delegate unconditionally to the wrapped model so that other
  model-modifying mods keep working.
* Position-dependent geometry MUST be cacheable: Fabric implementations MUST return a stable
  `createGeometryKey` derived from canvas identity, and NeoForge implementations MUST cache
  the built `BakedQuad` list per canvas.
* A canvas change MUST dirty at most the containing section and its neighbours.
* Meshing a canvas MUST greedy-merge equal-value texels into rectangles; a single-colour tag
  MUST produce fewer than 16 quads, and no canvas may produce more than 256.
* A section rebuild containing 256 painted faces MUST add < 1 ms to that rebuild.
* A paint operation MUST cost < 100 µs of server tick time, including validation, and MUST
  allocate no more than one canvas copy.
* Canvas lookup MUST be O(1) with no allocation (`Long2ObjectMap` keyed by packed pos+face).
* No file I/O on the render thread, the network thread, or the server thread.
* Incoming payload handling MUST bound every length before allocating.
* Diagnostics MUST be gated behind `-Dsimple_graffiti.debug=true` via a `static final boolean`
  guard, following RedFX.

---

## 12. Known limitations

These are specified behaviour, not defects:

1. Rendering depends on the loader's block-model pipeline. Mods that replace terrain meshing
   (Sodium/Embeddium class) consume that pipeline and are expected to work, but this MUST be
   verified against a real build before release (criterion 27). A mod that bypasses the model
   pipeline entirely would render no graffiti; painting, storage and sync are unaffected in
   that case.
2. Paint is destroyed by breaking the block and by piston movement; it is never dropped or
   recoverable.
3. Only sturdy full-block faces can be painted. Slabs, stairs, fences and glass panes cannot.
4. Resolution is one texel (1/16 block) and the palette is the 16 dye colours. There is no
   sub-texel or arbitrary-RGB paint.
5. `/graffiti clear player` sees loaded chunks only.
6. Graffiti is invisible to players without the mod; it is not rendered via maps, signs, or
   any vanilla channel.

---

## 13. Acceptance criteria

A build satisfies v1.0 when all of the following pass on **both** loaders, verified with
`:fabric:runClient -PtestJar` / `:neoforge:runClient -PtestJar` and with a dedicated server
run (`:fabric:runServer`, `:neoforge:runServer`) plus at least two clients.

**Painting**

1. Craft the can from the supplied recipe; the bucket is returned; the can is white with 64
   charges.
2. Paint a legible mark on a stone wall in survival; charges decrease by one per stamp;
   holding use draws a continuous line at 4 stamps/second.
3. All six faces of a block paint correctly, with the mark appearing where the crosshair is
   for every face and every player facing.
4. Sneak-use erases without consuming charges; a wet sponge clears the whole face and becomes
   a dry sponge.
5. An empty can paints nothing and reports it; a creative can never depletes.
6. Non-paintable targets (glass pane, slab, chest front, fluid, occluded face) consume
   nothing and paint nothing, and the chest still opens.
7. Sneak-use on blue wool sets the can blue; the palette screen sets any of the 16 colours;
   the item model colour follows.

**Persistence and sync**

8. Paint, relog, and the graffiti is byte-identical.
9. Player A paints; player B sees it within one tick; both see the same result after both
   relog and after a server restart.
10. Walking out of and back into render distance re-syncs the chunk with no visual difference.
11. Breaking a painted block removes its paint for every observer, immediately.
12. `kill -9` on the server mid-painting loses at most the last autosave, and the world loads
    with no error.
13. Deleting the `simple_graffiti/` region directory starts the world with no graffiti and no
    error.
14. A truncated region entry logs once, yields no graffiti for that chunk, does not fail chunk
    loading, and is not overwritten until something paints there.

**Compatibility**

15. A vanilla client joins the modded dedicated server, plays 5 minutes around painted chunks,
    and is never disconnected; the server log shows no graffiti payload sent to it.
16. A modded client joins a vanilla server, is given a can in creative, right-clicks a wall:
    one action-bar message, no paint, no packet sent (verified by packet capture or log), no
    exception.
17. A modded client with a mismatched protocol version behaves exactly as in 16.
18. Vanilla item ids still decode correctly on a modded client connected to a vanilla server
    (no item desync from the added registry entry).
19. `enabled = false` blocks painting but still renders existing graffiti.

**Server operation**

20. `spraysPerSecond` is enforced against a packet-spamming client with no tick-time impact
    and no correction storm.
21. `permissionMode = OPS_ONLY` and `BUILD_PERMISSION` each block painting where expected;
    a claim-protection mod that cancels block placement also blocks painting under
    `BUILD_PERMISSION`.
22. `maxCanvasesPerChunk` refuses new faces at the cap while existing faces stay paintable.
23. `/graffiti clear radius 16`, `/graffiti clear player <name>`, `/graffiti stats` and
    `/graffiti reload` all behave as specified and report counts.

**Performance**

24. 2 048 painted faces in one chunk: no measurable frame-time regression (< 0.2 ms/frame),
    no server tick warning, section rebuilds under 1 ms of added cost.
25. Two players spraying continuously for one minute produce no measurable bandwidth
    or tick-time anomaly.
26. Graffiti is correctly lit, fogged, occluded and culled, and does not z-fight at any
    distance from 0 to the far plane or at any grazing angle.
27. **Renderer compatibility.** With Sodium (Fabric) and Embeddium/Sodium (NeoForge)
    installed, graffiti renders identically to vanilla rendering, at both `Fancy` and `Fast`
    graphics. If no compatible build exists for 26.2 at release time, this MUST be recorded
    as untested in the release notes rather than claimed.
28. **Model compatibility.** With a connected-textures or other model-wrapping mod installed,
    both the block and its graffiti render correctly, in either mod-load order.
