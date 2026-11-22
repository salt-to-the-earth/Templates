package dev.templates.model;

import java.util.List;
import java.util.UUID;

public record TemplateData(String id, String name, UUID sourceWorld, int anchorX, int anchorY,
                            int anchorZ, float frontYaw, long createdAt, List<TemplateBlock> blocks) {
    public TemplateData {
        blocks = List.copyOf(blocks);
    }
}
