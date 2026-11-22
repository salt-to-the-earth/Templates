package dev.templates.model;

public record TemplateBlock(int x, int y, int z, String material, String blockData,
                            String inventoryData, String[] signLines) {
}
