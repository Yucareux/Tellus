package testItems.wrappers;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;

public class TestBiomeWrapper implements IBiomeWrapper
{
	private final String name;
	
	
	public TestBiomeWrapper(String name)
	{ this.name = name; }
	
	
	@Override 
	public String getName()
	{ return this.name; }
	@Override 
	public String getSerialString()
	{ return this.name; }
	
	@Override public boolean isColdBiome()
	{ return false; }
	
	@Override 
	public Object getWrappedMcObject() 
	{ return this; }
	
	
	@Override
	public int hashCode()
	{ return this.name.hashCode(); }
	@Override
	public boolean equals(Object obj)
	{
		if (!(obj instanceof TestBiomeWrapper))
		{
			return false;
		}
		
		return this.name.equals(((TestBiomeWrapper)obj).name);
	}
	
}
