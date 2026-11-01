package com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass;

import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

/**
 * Contains anything that's shared between
 * render passes or doesn't cleanly fit into another render pass interface.
 */
public interface IDhMetaRenderer extends IBindable
{
	void runRenderPassSetup(RenderParams renderParams);
	void runRenderPassCleanup(RenderParams renderParams);
	void copyToMcTexture(RenderParams renderParams);
	void clearDhDepthAndColorTextures(RenderParams renderParams);
	
}
