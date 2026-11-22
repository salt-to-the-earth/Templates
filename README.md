# Templates

A standalone Paper plugin for saving, organizing, previewing, and placing Minecraft structures.

## Design goals

- A paginated Master Template Book and a paginated individual book for every template.
- Reuse one tagged selection wand, with workflow-specific modes rather than requiring multiple tools.
- Save selected block data and supported container inventories; Ender Chest contents are intentionally excluded.
- Establish a saved front/anchor reference when creating a template.
- Preview placement with particles before changing the world.
- Use a compact, ground-plane particle control panel for movement and 90-degree rotation.
- Separate preview from apply: movement and rotation change only the preview; the center control applies the proposed staging.
- Keep placement reversible until the player finalizes. Cancel restores the original world.
- Keep book pages readable and ensure every entry and action remains reachable as the library grows.

## Compatibility

Paper API / Minecraft 26.3, Java 25. This target follows the current GateWorks development environment.

## Build

GitHub Actions runs the Gradle clean, test, and build tasks on pushes and pull requests to main, and uploads the plugin JAR as a workflow artifact.

## Development status

Initial repository scaffold. The first build establishes the plugin bootstrap, command surface, selection wand, particle selection feedback, and book pagination foundation. The full template capture and transactional placement workflow will be implemented in subsequent milestones.
