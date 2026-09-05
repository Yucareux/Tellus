package com.yucareux.tellus.client.preview;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.mixin.client.GuiGraphicsAccessor;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** CPU-projects preview quads into Minecraft 1.21.11's extracted GUI renderer. */
final class TerrainPreviewRenderer implements AutoCloseable {
   private static final float PREVIEW_CAMERA_FOV_DEGREES = 36.0F;
   private static final float Z_NEAR = 0.05F;
   private static final int PREVIEW_SKY_TOP_COLOR = 0xFF83B4CC;
   private static final int PREVIEW_SKY_HORIZON_COLOR = 0xFFC5D3C2;
   private static final int PREVIEW_SKY_GROUND_COLOR = 0xFF66745F;
   private static final Object PREVIEW_SUN_TEXTURE = Objects.requireNonNull(
      ClientMinecraftCompat.resourceLocation("minecraft", "textures/environment/celestial/sun.png"), "previewSunTexture"
   );
   private static final float PREVIEW_SUN_X = 0.0F;
   private static final float PREVIEW_SUN_Y = -0.1F;
   private static final float PREVIEW_SUN_Z = -1.0F;

   void render(
      GuiGraphics graphics,
      TerrainPreview.PreviewMesh preview,
      int x,
      int y,
      int width,
      int height,
      float rotationX,
      float rotationY,
      float cameraDistance,
      TerrainPreviewWidget.RenderMode renderMode,
      boolean cloudsVisible
   ) {
      if (width <= 0 || height <= 0) {
         return;
      }
      renderPreviewSky(graphics, x, y, width, height);
      renderPreviewSun(graphics, x, y, width, height, rotationX, rotationY);
      if (preview == null) {
         return;
      }

      TerrainPreview.PreviewGeometry terrain = preview.geometryFor(renderMode);
      TerrainPreview.PreviewGeometry clouds = cloudsVisible ? preview.cloudGeometry() : null;
      if (terrain.vertexCount() == 0 && (clouds == null || clouds.vertexCount() == 0)) {
         return;
      }

      Matrix4f modelView = buildModelView(rotationX, rotationY, cameraDistance);
      Matrix4f projection = buildProjection(width, height);
      Matrix3x2f pose = new Matrix3x2f(graphics.pose());
      ScreenRectangle rawBounds = new ScreenRectangle(x, y, width, height);
      ScreenRectangle bounds = rawBounds.transformAxisAligned(pose);
      GuiRenderState renderState = ((GuiGraphicsAccessor)graphics).tellus$getGuiRenderState();
      renderState.submitGuiElement(
         new PreviewRenderState(terrain, clouds, modelView, projection, pose, rawBounds, bounds)
      );
   }

   private static void renderPreviewSky(GuiGraphics graphics, int x, int y, int width, int height) {
      int horizonY = y + Math.max(1, Math.round(height * 0.72F));
      graphics.fillGradient(x, y, x + width, horizonY, PREVIEW_SKY_TOP_COLOR, PREVIEW_SKY_HORIZON_COLOR);
      graphics.fillGradient(x, horizonY, x + width, y + height, PREVIEW_SKY_HORIZON_COLOR, PREVIEW_SKY_GROUND_COLOR);
   }

   private static void renderPreviewSun(
      GuiGraphics graphics, int x, int y, int width, int height, float rotationX, float rotationY
   ) {
      Vector3f viewDirection = new Vector3f(PREVIEW_SUN_X, PREVIEW_SUN_Y, PREVIEW_SUN_Z).normalize();
      new Matrix4f().identity().rotateX(rotationX).rotateY(rotationY).transformDirection(viewDirection);
      float depth = -viewDirection.z;
      if (depth <= 0.01F) {
         return;
      }

      float tanHalfFov = (float)Math.tan(Math.toRadians(PREVIEW_CAMERA_FOV_DEGREES * 0.5F));
      float aspect = (float)width / height;
      float normalizedX = viewDirection.x / (depth * tanHalfFov * aspect);
      float normalizedY = viewDirection.y / (depth * tanHalfFov);
      int sunSize = Math.max(
         1, Math.min(Math.min(width, height), Mth.clamp(Math.round(Math.min(width, height) * 0.11F), 24, 72))
      );
      int sunX = x + Math.round((normalizedX * 0.5F + 0.5F) * width) - sunSize / 2;
      int sunY = y + Math.round((0.5F - normalizedY * 0.5F) * height) - sunSize / 2;
      if (sunX + sunSize <= x || sunX >= x + width || sunY + sunSize <= y || sunY >= y + height) {
         return;
      }
      graphics.enableScissor(x, y, x + width, y + height);
      ClientMinecraftCompat.blit(
         graphics, PREVIEW_SUN_TEXTURE, sunX, sunY, 0.0F, 0.0F, sunSize, sunSize, 32, 32
      );
      graphics.disableScissor();
   }

   private static Matrix4f buildProjection(int width, int height) {
      float aspect = (float)width / height;
      return new Matrix4f().setPerspective((float)Math.toRadians(PREVIEW_CAMERA_FOV_DEGREES), aspect, Z_NEAR, 100.0F);
   }

   private static Matrix4f buildModelView(float rotationX, float rotationY, float cameraDistance) {
      return new Matrix4f().identity().translate(0.0F, 0.0F, -cameraDistance).rotateX(rotationX).rotateY(rotationY);
   }

   @Override
   public void close() {
   }

   private static final class PreviewRenderState implements GuiElementRenderState {
      private final TerrainPreview.PreviewGeometry terrain;
      private final TerrainPreview.PreviewGeometry clouds;
      private final Matrix4f modelView;
      private final Matrix4f projection;
      private final Matrix3x2fc pose;
      private final ScreenRectangle rawBounds;
      private final ScreenRectangle bounds;

      private PreviewRenderState(
         TerrainPreview.PreviewGeometry terrain,
         TerrainPreview.PreviewGeometry clouds,
         Matrix4f modelView,
         Matrix4f projection,
         Matrix3x2fc pose,
         ScreenRectangle rawBounds,
         ScreenRectangle bounds
      ) {
         this.terrain = terrain;
         this.clouds = clouds;
         this.modelView = modelView;
         this.projection = projection;
         this.pose = pose;
         this.rawBounds = rawBounds;
         this.bounds = bounds;
      }

      @Override
      public RenderPipeline pipeline() {
         return RenderPipelines.GUI;
      }

      @Override
      public TextureSetup textureSetup() {
         return TextureSetup.noTexture();
      }

      @Override
      public ScreenRectangle scissorArea() {
         return this.bounds;
      }

      @Override
      public ScreenRectangle bounds() {
         return this.bounds;
      }

      @Override
      public void buildVertices(VertexConsumer consumer) {
         int terrainQuads = this.terrain.vertexCount() / 4;
         int cloudQuads = this.clouds == null ? 0 : this.clouds.vertexCount() / 4;
         int quadCount = terrainQuads + cloudQuads;
         int[] quadIds = new int[quadCount];
         float[] depths = new float[quadCount];
         int visibleCount = 0;
         Vector3f view = new Vector3f();

         for (int quad = 0; quad < quadCount; quad++) {
            boolean cloud = quad >= terrainQuads;
            TerrainPreview.PreviewGeometry geometry = cloud ? this.clouds : this.terrain;
            int localQuad = cloud ? quad - terrainQuads : quad;
            int firstVertex = localQuad * 4;
            float depth = 0.0F;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
               int positionIndex = (firstVertex + corner) * 3;
               this.modelView.transformPosition(
                  geometry.positions()[positionIndex],
                  geometry.positions()[positionIndex + 1],
                  geometry.positions()[positionIndex + 2],
                  view
               );
               depth += view.z;
               maxZ = Math.max(maxZ, view.z);
            }
            if (maxZ <= -Z_NEAR) {
               quadIds[visibleCount] = cloud ? -(localQuad + 1) : localQuad;
               depths[visibleCount] = depth * 0.25F;
               visibleCount++;
            }
         }

         if (visibleCount > 1) {
            sortQuads(quadIds, depths, 0, visibleCount - 1);
         }
         Vector3f projected = new Vector3f();
         for (int index = 0; index < visibleCount; index++) {
            int quadId = quadIds[index];
            boolean cloud = quadId < 0;
            int quad = cloud ? -quadId - 1 : quadId;
            TerrainPreview.PreviewGeometry geometry = cloud ? this.clouds : this.terrain;
            int firstVertex = quad * 4;
            for (int corner = 0; corner < 4; corner++) {
               emitVertex(consumer, geometry, firstVertex + corner, view, projected);
            }
         }
      }

      private void emitVertex(
         VertexConsumer consumer,
         TerrainPreview.PreviewGeometry geometry,
         int vertex,
         Vector3f view,
         Vector3f projected
      ) {
         int positionIndex = vertex * 3;
         this.modelView.transformPosition(
            geometry.positions()[positionIndex],
            geometry.positions()[positionIndex + 1],
            geometry.positions()[positionIndex + 2],
            view
         );
         this.projection.transformProject(view, projected);
         float screenX = this.rawBounds.left() + (projected.x + 1.0F) * 0.5F * this.rawBounds.width();
         float screenY = this.rawBounds.top() + (1.0F - projected.y) * 0.5F * this.rawBounds.height();
         consumer.addVertexWith2DPose(this.pose, screenX, screenY).setColor(geometry.colors()[vertex]);
      }

      private static void sortQuads(int[] quadIds, float[] depths, int left, int right) {
         int i = left;
         int j = right;
         float pivot = depths[(left + right) >>> 1];
         while (i <= j) {
            while (depths[i] < pivot) i++;
            while (depths[j] > pivot) j--;
            if (i <= j) {
               int quad = quadIds[i];
               quadIds[i] = quadIds[j];
               quadIds[j] = quad;
               float depth = depths[i];
               depths[i] = depths[j];
               depths[j] = depth;
               i++;
               j--;
            }
         }
         if (left < j) sortQuads(quadIds, depths, left, j);
         if (i < right) sortQuads(quadIds, depths, i, right);
      }
   }
}
