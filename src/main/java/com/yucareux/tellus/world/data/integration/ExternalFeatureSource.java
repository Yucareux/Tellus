package com.yucareux.tellus.world.data.integration;

import java.io.IOException;
import java.util.List;

public interface ExternalFeatureSource extends AutoCloseable {
   List<ExternalRoadFeature> roadsForBounds(GeoBounds bounds) throws IOException;

   List<ExternalBuildingFeature> buildingsForBounds(GeoBounds bounds) throws IOException;

   default List<ExternalAreaFeature> areasForBounds(GeoBounds bounds) throws IOException {
      return List.of();
   }

   default List<ExternalLineFeature> linesForBounds(GeoBounds bounds) throws IOException {
      return List.of();
   }

   default List<ExternalPointFeature> pointsForBounds(GeoBounds bounds) throws IOException {
      return List.of();
   }

   @Override
   default void close() throws IOException {
   }
}
