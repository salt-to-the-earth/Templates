# Templates

**Templates is a standalone Paper plugin for saving, organizing, previewing, and placing reusable Minecraft structures.** It does not require GateWorks to be installed.

## Current implementation

- One tagged Blaze Rod wand for selection, creation-anchor, and placement interactions.
- Individual block selection, inclusive cuboid selection, and removal of individual selected blocks.
- Per-player particle outlines with bounded particle output, adapted from the GateWorks selection renderer.
- A paginated Master Template Book and a separate book for each saved template. Book links invoke server commands; the books are interfaces, not the source of truth.
- Chat-based template naming, an explicit save confirmation, and a front/anchor strike.
- Durable YAML template files stored under plugins/Templates/templates/.
- Capture of block material, Bukkit block-data strings, supported container inventories, and sign text. Ender Chest inventories are deliberately excluded.
- Cross-world placement using relative anchor coordinates and quarter-turn orientation transforms.
- A particle preview and fixed nine-position ground control panel. Blue moves X, red moves Z, green moves Y, yellow rotates in 90-degree increments, and white applies/finalizes.
- Preview movement does not change world blocks. Center apply stages the structure and stores the original footprint for rollback.
- Repositioning restores the previous footprint before capturing the new one. A preflight check prevents re-apply from overwriting blocks changed by another source.
- Cancel and disconnect roll back unchanged staged blocks. A durable transaction file supports recovery after a server restart or crash. Finalize keeps the current staged structure.
- GitHub Actions compiles, tests, and publishes the JAR as a workflow artifact.

## Commands

| Command | Purpose |
|---|---|
| /templates wand | Get the single Templates wand |
| /templates book | Open the Master Template Book |
| /templates create | Name and save the current selection |
| /templates confirm | Confirm save and begin front/anchor selection |
| /templates list | List saved templates |
| /templates open <id> | Open an individual Template Book |
| /templates place <id> | Start a placement preview |
| /templates finalize | Keep the currently staged placement |
| /templates cancel | Roll back an active placement |
| /templates rename <id> | Rename a saved template |
| /templates delete <id> | Delete a saved template |
| /templates selection | Show selected block count |
| /templates clear | Clear the current selection |

## Creation workflow

1. Run /templates wand.
2. Left-click blocks to select them. Shift-left-click two corners to add a cuboid. Right-click a selected block to remove it.
3. Open /templates book and choose **Create from current selection**, or run /templates create.
4. Enter a name in chat, then choose the confirmation action.
5. Walk to the intended front of the structure and strike the ground with the wand. The block immediately above the struck block is the saved anchor.

## Placement workflow

1. Open the template's book and choose **Place this template**, or run /templates place <id>.
2. Strike the ground with the wand at the intended destination. The block above it is the initial anchor target.
3. Inspect the particle outline. The control panel remains at the initial ground strike while the preview moves.
4. Click a colored control position on the ground with the wand to move or rotate the preview.
5. Click the white center once to stage the template. Click directional/rotation controls to preview a revised position, then click center to apply it.
6. Double-click the white center to finalize, or use /templates finalize. Use /templates cancel to roll back.

The nine panel positions are:
- Left/right blue: X − / X +
- Back/front red: Z − / Z +
- Lower-left/upper-right green: Y − / Y +
- Remaining yellow corners: rotate left/right
- White center: apply; a deliberate second activation finalizes

## Compatibility and build

- Paper API / Minecraft 26.3
- Java 25
- Gradle 9.2.1

Build with: gradle clean test build

The JAR is written to build/libs/Templates-0.2.0.jar.

## Important fidelity and safety notes

This implementation preserves block data and the supported tile information explicitly handled by Bukkit container and sign APIs. It does **not** yet guarantee complete NBT fidelity for every tile entity type (for example, every banner, skull, lectern, decorated pot, or modded block). Validate those block types on a test server before relying on them.

The plugin has no universal protection-plugin adapter. It cannot independently know every WorldGuard or third-party protection rule; use a test area where the placing administrator is authorized. The rollback conflict check compares block material/data plus the supported container inventory and sign text snapshots. Other tile-entity-specific fields are not yet covered. Do not treat this early implementation as a substitute for backups.

Templates is separate from GateWorks. GateWorks supplied reusable interaction, selection, book, and particle-rendering patterns; the template storage and placement transaction system are Templates-specific.

## License

MIT.
