package dev.templates.model;

public final class CoordinateTransform {
    private CoordinateTransform() {}

    public static int[] rotate(int x, int z, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    public static int yawQuarter(float yaw) {
        return Math.floorMod(Math.round(yaw / 90.0f), 4);
    }

    public static int rotationBetween(float sourceYaw, float destinationYaw) {
        return Math.floorMod(yawQuarter(destinationYaw) - yawQuarter(sourceYaw), 4);
    }
}
