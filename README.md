# VS: Anchors (vsanchors)

A Minecraft Forge mod for **1.20.1** that adds two Valkyrien Skies (VS2) utility blocks:

- **Anchor Block**: freezes/unfreezes the ship it is placed on (sets the ship to static).
- **Dock Block**: auto-detects nearby ships, **pulls them in smoothly**, then **snaps + locks** the ship in place.

Built for devs who want simple, readable examples of **VS2 ship control** (server tick + physics thread done correctly).

---

## Compatibility

- **Minecraft**: 1.20.1  
- **Forge**: 47.4.0  
- **Java**: 17  
- **Valkyrien Skies 2 (Forge 1.20.1)**: 2.3.0-beta.10  
- **VS Core**: 1.1.0+  
- **Kotlin for Forge**: 4.11.0 (required by VS stack)

---

## Features

### ✅ Anchor Block
- Right-click toggles anchored state.
- If placed on a VS ship, it calls `ship.setStatic(true/false)`.
- Persists anchored state via NBT (`anchored` boolean).

Player feedback messages:
- `Anchored!`
- `Anchor raised!`
- `This block is not on a ship!`

### ✅ Dock Block
- Server-side block entity tick scans for loaded ships within a **10 block radius**.
- Picks the closest ship and pulls it toward the dock target position.
- Uses a **ShipForcesInducer attachment** to apply force in VS2’s physics pipeline (correct threading model).
- When ship center reaches **snap distance (3.5)**:
  - stops pulling
  - calls `ship.setStatic(true)`
  - stores `dockedShipId` for later undock (persisted)

Right-click behavior:
- If docked → undock (set static false).
- If not docked → debug info (closest ship distance, loaded ship count).

---

## How it works (dev overview)

### 1) Server tick decides “intent”
`DockBlockEntity.serverTick(...)` runs each Minecraft server tick and does:

- Finds closest **loaded** ship in range using `ShipObjectServerWorld.getLoadedShips()`.
- Creates / updates a `DockPhysicsAttachment` on that ship:
  - `targetX/Y/Z`
  - `pullAccel` (dynamic: stronger farther away, weaker near snap)
  - `active = true/false`
- When close enough → snaps/locks:
  - `active = false`
  - `ship.setStatic(true)`
  - persists `docked = true` and `dockedShipId`

**Important detail:** VS2 has “all ships” vs “loaded ships”.
This mod uses `ShipObjectServerWorld.getLoadedShips()` because `getAllShips()` returns ship data for unloaded ships too.

### 2) Physics thread applies force (the correct place)
`DockPhysicsAttachment implements ShipForcesInducer`.

VS2 calls `applyForces()` during its own physics loop (NOT the MC tick thread).  
That method:

- Computes direction to target
- Applies **invariant force** via `PhysShipImpl.applyInvariantForce(...)`
- Applies **counter-torque damping** to reduce spin during pull-in

Fields are `volatile` so the physics thread always sees the latest values written by the server tick thread.

> TL;DR: Server tick sets goals, physics thread applies forces.

---

## Project structure

- `VSAnchors`  
  Main mod entrypoint, registers:
  - blocks (`ModBlocks`)
  - items (`ModItems`)
  - block entities (`ModBlockEntities`)
  - creative tab

- `AnchorBlockEntity`  
  Stores anchored state, toggles `ship.setStatic(...)`

- `DockBlock`  
  Right-click calls `DockBlockEntity.toggleDock(...)`  
  Registers server ticker for docking logic.

- `DockBlockEntity`  
  Ship scanning, pull logic, snap + lock, undock, NBT persistence.

- `DockPhysicsAttachment`  
  VS2 `ShipForcesInducer` applying invariant forces + torque damping.

---

## Build / Dev setup

### Requirements
- Java 17 installed
- A Forge 1.20.1 MDK environment
- Valkyrien Skies dependencies resolve via the VS Maven repo (already configured)

### Typical dev flow
1. Import Gradle project in IntelliJ
2. Run the Gradle tasks to setup runs (ForgeGradle)
3. Launch the `runClient` configuration

---

## Gameplay usage

### Anchor Block
1. Place it **on a ship**
2. Right-click to toggle anchored state

### Dock Block
1. Place dock block near where you want ships to “park”
2. Bring a ship within ~10 blocks
3. Dock will pull it in and lock it
4. Right-click to undock

---

## Notes / Known behaviors

- **Snap distance** is based on ship *center*, not hull edge.  
  That’s why it snaps at 3.5 blocks (ship visually looks adjacent earlier/later depending on size).
- If no ship is in range, any previously tracked ship attachment is deactivated.

---

## Extending (ideas for contributors)

- Add config:
  - dock range
  - accel min/max
  - snap distance
  - optional “only pull when powered by redstone”
- Visual feedback:
  - particles while pulling
  - blockstate “active/docked”
- Smarter docking:
  - align ship rotation (yaw) to dock orientation
  - pull to a specific “attachment point” rather than ship center

---

## License

Currently set as **All Rights Reserved** (see `gradle.properties` / `mods.toml`).

---

## Credits

Made by **Especialista104**.  
Built on top of **Minecraft Forge** + **Valkyrien Skies 2**.
