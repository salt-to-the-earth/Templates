package dev.templates.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateTransformTest {
    @Test void quarterTurnsRotateCoordinatesClockwise() {
        assertArrayEquals(new int[]{2, 1}, CoordinateTransform.rotate(1, -2, 1));
        assertArrayEquals(new int[]{-1, 2}, CoordinateTransform.rotate(1, -2, 2));
        assertArrayEquals(new int[]{-2, -1}, CoordinateTransform.rotate(1, -2, 3));
        assertArrayEquals(new int[]{1, -2}, CoordinateTransform.rotate(1, -2, 4));
    }

    @Test void rotationNormalizesNegativeAndLargeTurnCounts() {
        assertArrayEquals(CoordinateTransform.rotate(3, 7, 3),
                CoordinateTransform.rotate(3, 7, -1));
        assertArrayEquals(CoordinateTransform.rotate(3, 7, 1),
                CoordinateTransform.rotate(3, 7, 5));
    }

    @Test void calculatesRelativeFacingRotation() {
        assertEquals(1, CoordinateTransform.rotationBetween(0, 90));
        assertEquals(3, CoordinateTransform.rotationBetween(90, 0));
        assertEquals(0, CoordinateTransform.rotationBetween(-90, 270));
    }
}
