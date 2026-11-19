package com.hydroguard.backend.dtos;

import java.util.List;

public record SimulationGeometryDTO(
        String citySlug,
        double[] center,
        List<WaterwayDTO> waterways,
        List<StreetWayDTO> streets
) {
    public record WaterwayDTO(
            String id,
            String name,
            String kind,
            int channelWidthMeters,
            List<double[]> points
    ) {}

    public record StreetWayDTO(
            String id,
            String name,
            String neighborhood,
            String highwayType,
            List<double[]> points
    ) {}
}