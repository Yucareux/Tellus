package com.yucareux.tellus.client.preview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** OpenGL-backed preview renderer used before Minecraft's extracted GUI renderer. */
final class TerrainPreviewRenderer implements AutoCloseable {
   private static final float PREVIEW_CAMERA_FOV_DEGREES = 36.0F;
   private static final int PREVIEW_SKY_TOP_COLOR = 0xFF83B4CC;
   private static final int PREVIEW_SKY_HORIZON_COLOR = 0xFFC5D3C2;
   private static final int PREVIEW_SKY_GROUND_COLOR = 0xFF66745F;
   private static final ResourceLocation PREVIEW_SUN_TEXTURE = ClientMinecraftCompat.resourceLocation(
      "minecraft", "textures/environment/sun.png"
   );
   private static final float PREVIEW_SUN_X = 0.0F;
   private static final float PREVIEW_SUN_Y = -0.1F;
   private static final float PREVIEW_SUN_Z = -1.0F;

   private TerrainPreview.PreviewMesh uploadedPreviewMesh;
   private VertexBuffer terrainVertexBuffer;
   private VertexBuffer detailVertexBuffer;
   private VertexBuffer cloudVertexBuffer;

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

      VertexBuffer vertexBuffer = this.previewVertexBuffer(preview, renderMode);
      if (vertexBuffer == null || vertexBuffer.isInvalid()) {
         return;
      }
      VertexBuffer clouds = cloudsVisible ? this.previewCloudVertexBuffer(preview) : null;
      Matrix4f modelView = buildModelView(rotationX, rotationY, cameraDistance);
      Matrix4f projection = buildProjection(width, height);
      Minecraft minecraft = Minecraft.getInstance();
      double guiScale = minecraft.getWindow().getGuiScale();
      int viewportX = (int)Math.floor(x * guiScale);
      int viewportY = (int)Math.floor((graphics.guiHeight() - y - height) * guiScale);
      int viewportWidth = Math.max(1, (int)Math.ceil(width * guiScale));
      int viewportHeight = Math.max(1, (int)Math.ceil(height * guiScale));
      graphics.flush();
      graphics.enableScissor(x, y, x + width, y + height);

      try {
         RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.depthFunc(GL11.GL_LEQUAL);
         RenderSystem.clearDepth(1.0);
         RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
         RenderSystem.disableCull();
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         vertexBuffer.bind();
         vertexBuffer.drawWithShader(
            modelView, projection, Objects.requireNonNull(GameRenderer.getPositionColorShader(), "positionColorShader")
         );
         if (clouds != null && !clouds.isInvalid()) {
            clouds.bind();
            clouds.drawWithShader(
               modelView, projection, Objects.requireNonNull(GameRenderer.getPositionColorShader(), "positionColorShader")
            );
         }
      } finally {
         VertexBuffer.unbind();
         RenderSystem.disableBlend();
         RenderSystem.enableCull();
         RenderSystem.depthMask(false);
         RenderSystem.disableDepthTest();
         RenderSystem.viewport(0, 0, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
         graphics.disableScissor();
      }
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
      graphics.flush();
      boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
      RenderSystem.enableBlend();
      RenderSystem.blendFuncSeparate(
         GlStateManager.SourceFactor.SRC_ALPHA,
         GlStateManager.DestFactor.ONE,
         GlStateManager.SourceFactor.ONE,
         GlStateManager.DestFactor.ZERO
      );

      try {
         graphics.blit(PREVIEW_SUN_TEXTURE, sunX, sunY, 0.0F, 0.0F, sunSize, sunSize, 32, 32);
      } finally {
         RenderSystem.defaultBlendFunc();
         if (!blendWasEnabled) {
            RenderSystem.disableBlend();
         }
         graphics.disableScissor();
      }
   }

   private VertexBuffer previewVertexBuffer(TerrainPreview.PreviewMesh mesh, TerrainPreviewWidget.RenderMode renderMode) {
      if (this.uploadedPreviewMesh != mesh) {
         this.releasePreviewVertexBuffers();
         this.uploadedPreviewMesh = mesh;
      }

      if (renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL) {
         if (this.detailVertexBuffer == null) {
            this.detailVertexBuffer = buildPreviewVertexBuffer(mesh.geometryFor(renderMode));
         }
         return this.detailVertexBuffer;
      }

      if (this.terrainVertexBuffer == null) {
         this.terrainVertexBuffer = buildPreviewVertexBuffer(mesh.geometryFor(renderMode));
      }
      return this.terrainVertexBuffer;
   }

   private VertexBuffer previewCloudVertexBuffer(TerrainPreview.PreviewMesh mesh) {
      if (this.uploadedPreviewMesh != mesh) {
         this.releasePreviewVertexBuffers();
         this.uploadedPreviewMesh = mesh;
      }
      if (this.cloudVertexBuffer == null) {
         this.cloudVertexBuffer = buildPreviewVertexBuffer(mesh.cloudGeometry());
      }
      return this.cloudVertexBuffer;
   }

   private static VertexBuffer buildPreviewVertexBuffer(TerrainPreview.PreviewGeometry geometry) {
      if (geometry.vertexCount() == 0) {
         return null;
      }
      BufferBuilder buffer = ClientMinecraftCompat.beginPositionColorQuads();
      for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
         int positionIndex = vertex * 3;
         ClientMinecraftCompat.emitVertex(
            buffer,
            geometry.positions()[positionIndex],
            geometry.positions()[positionIndex + 1],
            geometry.positions()[positionIndex + 2],
            geometry.colors()[vertex]
         );
      }
      VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
      vertexBuffer.bind();
      ClientMinecraftCompat.upload(vertexBuffer, buffer);
      VertexBuffer.unbind();
      return vertexBuffer;
   }

   private void releasePreviewVertexBuffers() {
      VertexBuffer terrain = this.terrainVertexBuffer;
      VertexBuffer detail = this.detailVertexBuffer;
      VertexBuffer clouds = this.cloudVertexBuffer;
      this.terrainVertexBuffer = null;
      this.detailVertexBuffer = null;
      this.cloudVertexBuffer = null;
      this.uploadedPreviewMesh = null;
      if (terrain == null && detail == null && clouds == null) {
         return;
      }
      Runnable release = () -> {
         if (terrain != null) terrain.close();
         if (detail != null && detail != terrain) detail.close();
         if (clouds != null && clouds != terrain && clouds != detail) clouds.close();
      };
      if (RenderSystem.isOnRenderThread()) {
         release.run();
      } else {
         RenderSystem.recordRenderCall(release::run);
      }
   }

   private static Matrix4f buildProjection(int width, int height) {
      float aspect = (float)width / height;
      return new Matrix4f().setPerspective((float)Math.toRadians(PREVIEW_CAMERA_FOV_DEGREES), aspect, 0.05F, 100.0F);
   }

   private static Matrix4f buildModelView(float rotationX, float rotationY, float cameraDistance) {
      return new Matrix4f().identity().translate(0.0F, 0.0F, -cameraDistance).rotateX(rotationX).rotateY(rotationY);
   }

   @Override
   public void close() {
      this.releasePreviewVertexBuffers();
   }
}
