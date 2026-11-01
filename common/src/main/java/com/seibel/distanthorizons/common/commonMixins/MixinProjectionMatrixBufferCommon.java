package com.seibel.distanthorizons.common.commonMixins;

#if MC_VER <= MC_26_2_0

public class MixinProjectionMatrixBufferCommon {}

#else

import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import org.joml.Matrix4f;

public class MixinProjectionMatrixBufferCommon
{
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	
	
	/** if set to true we will record the next MVM matrix passed in */
	public static boolean getNewMvmMatrix = false;
	
	
	public static void onMatrixWrite(Matrix4f matrix)
	{
		
		if (IRIS_ACCESSOR != null
			&& IRIS_ACCESSOR.isRenderingShadowPass())
		{
			// getting the projection matrix during the shadow map will 
			// cause the frustum culling to run incorrectly, culling everything
			return;
		}
		
		// only get a new matrix if requested
		if (!getNewMvmMatrix)
		{
			return;
		}
		
		
		
		DhMat4f dhMatrix = McObjectConverter.convert(matrix);
		if (dhMatrix.equals(DhMat4f.IDENTITY))
		{
			// it's possible Mojang may pass in the identity matrix
			// to clear the old one,
			// we don't want that
			return;
		}
		
		
		ClientApi.RENDER_STATE.mcProjectionMatrix = dhMatrix;
		// Only get the first MVM matrix after requested.
		// This is done to prevent issues with getting subsequent MVM matrices
		// due to vanilla post-processing passes (ie glow). 
		getNewMvmMatrix = false;
	}
	
}
#endif
