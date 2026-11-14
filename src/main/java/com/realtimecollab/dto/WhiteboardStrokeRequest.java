package com.realtimecollab.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

public record WhiteboardStrokeRequest(
        Tool tool,
        @NotNull @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
        @NotNull @Min(1) @Max(24) Integer width,
        @NotNull @Size(min = 1, max = 512) @Valid List<Point> points) {

    public WhiteboardStrokeRequest {
        // Strokes saved before tools were introduced are pen strokes.
        if (tool == null) tool = Tool.PEN;
        // Cascaded validation checks each point's coordinates and skips null elements.
        if (points != null && points.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Whiteboard stroke points cannot be null.");
        }
        if (tool != Tool.PEN && tool != Tool.PENCIL && (points == null || points.size() != 2)) {
            throw new IllegalArgumentException("A whiteboard shape needs a start and an end point.");
        }
    }

    public enum Tool { PEN, PENCIL, LINE, RECTANGLE, ELLIPSE, ARROW }

    public record Point(
            @NotNull @Min(0) @Max(1000) Integer x,
            @NotNull @Min(0) @Max(600) Integer y) {}
}
