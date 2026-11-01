package testItems.wrappers;

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;

import java.awt.*;

public class TestBlockStateWrapper implements IBlockStateWrapper
{
	private final String name;
	
	
	public TestBlockStateWrapper(String name)
	{ this.name = name; }
	
	
	
	@Override public boolean isAir() { return false; }
	@Override public boolean isSolid() { return true; }
	@Override public boolean isLiquid() { return false; }
	@Override public String getSerialString() { return this.name; }
	@Override public int getOpacity() { return 15; }
	@Override public int getLightEmission() { return 0; }
	@Override public byte getMaterialId() { return 0; }
	@Override public boolean isBeaconBlock() { return false; }
	@Override public boolean isBeaconTintBlock() { return false; }
	@Override public boolean allowsBeaconBeamPassage() { return false; }
	@Override public boolean isBeaconBaseBlock() { return false; }
	@Override public boolean isIceBlock() { return false; }
	@Override public boolean allowApiColorOverride() { return false; }
	@Override public boolean allowApiTextureOverride() { return false; }
	@Override public Color getMapColor() { return Color.MAGENTA; }
	@Override public Color getBeaconTintColor() { return Color.MAGENTA; }
	@Override public boolean renderTexture() { return true; }
	@Override public boolean useBottomTextureForSides() { return false; }
	@Override public boolean alwaysRasterizeTexture() { return false; }
	
	@Override public Object getWrappedMcObject() { return this; }
	
	
	@Override 
	public int hashCode()
	{ return this.name.hashCode(); }
	@Override 
	public boolean equals(Object obj)
	{
		if (!(obj instanceof TestBlockStateWrapper))
		{
			return false;
		}
		
		return this.name.equals(((TestBlockStateWrapper)obj).name);
	}
	
}
