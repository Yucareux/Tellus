package com.seibel.distanthorizons.forge17.modAccessor;

import net.coderbot.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

public class IrisAccessor implements IIrisAccessor 
{

    @Override
    public String getModName() { return Iris.MODNAME; }

    @Override
    public boolean isShaderPackInUse() 
    {
        return IrisApi.getInstance()
            .isShaderPackInUse();
    }

    @Override
    public boolean isRenderingShadowPass() 
    {
        return IrisApi.getInstance()
            .isRenderingShadowPass();
    }
	
	@Override
	public boolean isReverseZDuringShaders()
	{
		return false;
	}
	
	@Override
	public int getFramebufferDepthTextureId(Object framebuffer)
	{
		return -1;
	}
	
	
	
}
