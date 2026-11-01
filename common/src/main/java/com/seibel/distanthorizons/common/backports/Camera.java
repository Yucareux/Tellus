package com.seibel.distanthorizons.common.backports;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import net.minecraft.entity.EntityLivingBase;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
#endif

/**
 * Recreation of MC's 1.12+ camera object
 * so 1.7.10 can use similar code to 1.12.
 */
public class Camera
{
	#if MC_VER <= MC_1_7_10
	
	public static final Camera INSTANCE = new Camera();
	
    private final Vector3d pos = new Vector3d();

	public void update(EntityLivingBase entity, float partialTicks)
	{
		final Vector4f offset = new Vector4f(); // third person offset
		final Matrix4f inverseModelView = ClientApi.RENDER_STATE.mcModelViewMatrix
			.createJomlMatrix()
			.invert();
		inverseModelView.transform(offset);
		
		final double camX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks + offset.x;
		final double camY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks + offset.y;
		final double camZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks + offset.z;
		this.pos.set(camX, camY, camZ);
	}
	
	public Vector3d getPos() { return this.pos; }
	
	#endif
}
