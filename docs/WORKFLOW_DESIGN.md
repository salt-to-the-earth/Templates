# Templates workflow and interaction specification

This document records the agreed user experience and is the implementation reference for the next development milestones.

## Product boundary

Templates is a standalone Paper plugin and repository. It is independent of GateWorks. It provides reusable structure capture and placement.

## Tools and books

- One tagged wand is reused throughout the workflow. The wand changes internal mode as the player advances; players do not need to carry separate selection and placement wands.
- The Master Template Book is the library index.
- Each saved template has its own Template Book.
- Books are an interface, not the authoritative data store.
- Book content must be deliberately paginated. The master library must remain navigable with dozens or hundreds of entries. Individual books must split long descriptions and action lists across pages. Every action must remain visible and reachable; no control may be clipped or pushed beyond the page.
- Keep page text concise. Put long details on additional pages rather than shrinking or crowding the interface.
- Provide clear page numbering and navigation, and never rely on a single crowded page.

## Template creation

1. Player uses the selection wand to select a cuboid.
2. Player can remove individual blocks from the selection.
3. Player opens the Master Template Book, chooses the create/name action, and enters a name such as Farmhouse.
4. The player confirms Save.
5. The plugin captures the selected block positions, block data, and supported block-entity data.
6. Supported container inventories are captured with their blocks. Ender Chest contents are excluded because they are player-linked rather than location-owned.
7. The player establishes the structure's front reference by walking to the intended front and striking the ground.
8. The strike location defines the saved anchor/front reference. The player confirms it in the book; the template is then complete.

## Placement preview and control panel

1. Player opens the relevant Template Book and chooses Place.
2. Player walks to the approximate destination and strikes the ground. The block immediately above the struck block is the initial anchor target.
3. A particle outline previews the structure at the proposed destination. No world blocks are captured or modified during this preview phase.
4. A compact nine-position control panel is shown on the ground plane:
   - white center: anchor/apply/finalize interaction
   - blue indicators: X movement, positive and negative
   - red indicators: Z movement, positive and negative
   - green indicators: Y movement, positive and negative
   - yellow corner indicators: rotate left and rotate right
5. Directional controls move the proposed structure preview one block per activation. The control panel itself remains stationary.
6. Yellow controls rotate the preview in 90-degree increments. Rotation is preview-only until applied.
7. The preview is recalculated when the player changes position or rotation, not by repeatedly changing world blocks.
8. The player can walk around and inspect the particle outline before applying it.

## Apply, revise, finalize, cancel

- The first center activation captures the original blocks in the current footprint and temporarily stages the actual template blocks.
- After staging, directional movement and rotation only change the particle preview. The staged structure remains in place until the player activates the center again.
- On apply, restore the previous staged footprint from its original snapshot, capture the original world state at the newly proposed footprint, and stage the template at the new position/orientation.
- The previous and new footprints may overlap. The implementation must restore the previous footprint before capturing the new one, so staged template blocks are never mistaken for original world blocks.
- Keep only the rollback snapshot for the currently staged footprint, not a history of every position the player previewed.
- The original snapshot must not be overwritten by a snapshot of the plugin's own staged blocks.
- The placement remains temporary until finalized.
- Finalize can be performed by a deliberate center double-smack or through a clearly labeled action in the Template Book.
- Cancel restores the original world state for the active staged footprint.
- A failed apply, disconnect, or server restart must not silently leave a partial placement. Add transaction recovery and conflict checks before production use.
- Avoid overwriting protected areas and detect concurrent world changes before restoring snapshots.

## Particle and performance rules

- Use vanilla particles for selection and placement visualization; do not depend on translucent ghost blocks or client mods.
- Selection highlights should be visible only to the player performing the selection.
- Preview updates should be event-driven and rate-limited.
- Do not emit one particle for every block of a large structure without bounds. Use an efficient outline/edge strategy and sensible particle caps.
- The ground control panel is a particle-based interaction surface; it must not place permanent wool or other control blocks in the world.

## Storage and fidelity

- Store templates independently of the books.
- Preserve block materials and block data, including orientation and supported block-entity information.
- Preserve supported container contents. Explicitly exclude Ender Chest inventories.
- Template data must remain independent of any individual placement transaction.
- Cross-world placement should work when the destination server has the same plugin and the template data is present.

## Implementation sequencing

1. Buildable plugin foundation, command registration, selection wand, per-player selection particles, and paginated book layout.
2. Template capture, durable storage, naming, and individual/master book navigation.
3. Saved front/anchor coordinate model and orientation transforms.
4. Particle placement preview and ground-plane movement/rotation controls.
5. Reversible staged placement transaction, apply/finalize/cancel, and recovery.
6. Automated tests, compatibility checks, documentation, and downloadable GitHub Actions artifacts.

This specification records the agreed design; features should be implemented in the sequence above rather than represented as complete before they exist.
