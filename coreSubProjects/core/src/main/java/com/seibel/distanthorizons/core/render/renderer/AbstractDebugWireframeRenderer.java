package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.PriorityBlockingQueue;

public abstract class AbstractDebugWireframeRenderer implements IBindable
{
	protected static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder()
		.maxCountPerSecond(1)
		.build();
	
	protected static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	
	protected final RendererLists rendererLists = new RendererLists();
	protected final PriorityBlockingQueue<BoxParticle> particles = new PriorityBlockingQueue<>();
	
	// used when rendering
	protected DhMat4f dhMvmProjMatrixThisFrame;
	protected DhVec3f camPosFloatThisFrame;
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public void render(RenderParams renderParams)
	{
		this.dhMvmProjMatrixThisFrame = new DhMat4f(renderParams.dhMvmProjMatrix);
		this.camPosFloatThisFrame = new DhVec3f(renderParams.exactCameraPosition);
		
		
		// Allows Blaze3D to batch every renderBox() call made during 
		// this frame into vs one render pass per box.
		// Does nothing on OpenGL.
		this.beginRenderBatch();
		
		
		this.rendererLists.render(this);
		
		
		// particle cleanup		
		BoxParticle head = null;
		while ((head = this.particles.poll()) != null && head.isDead())
		{ /* remove dead particles */ }
		if (head != null)
		{
			// re-add the popped off head
			this.particles.add(head);
		}
		
		
		// particle rendering
		for (BoxParticle particle : this.particles)
		{
			// a new box is created each time since the height will be different based on the time it's lived
			this.renderBox(particle.createNewRenderBox());
		}
		
		
		// render any remaining queued boxes
		this.endRenderBatch();
		
	}
	
	public abstract void renderBox(Box box);
	
	/** Called once at the start of {@link #render(RenderParams)}, before any {@link #renderBox(Box)} calls. Override to reset/prepare a batch. */
	protected void beginRenderBatch() { }
	/** Called once at the end of {@link #render(RenderParams)}, after all {@link #renderBox(Box)} calls for the frame. Override to flush a batch. */
	protected void endRenderBatch() { }
	
	//endregion
	
	
	
	//==============//
	// registration //
	//==============//
	//region

	public void makeParticle(BoxParticle particle)
	{
		if (Config.Client.Advanced.Debugging.DebugWireframe.enableRendering.get())
		{
			this.particles.add(particle);
		}
	}

	public void register(IDebugRenderable renderable, ConfigEntry<Boolean> config) { this.addRenderer(renderable, config); }
	public void addRenderer(IDebugRenderable renderable, ConfigEntry<Boolean> config) { this.rendererLists.addRenderable(renderable, config); }

	public void unregister(IDebugRenderable renderable, ConfigEntry<Boolean> config) { this.removeRenderer(renderable, config); }
	public void removeRenderer(IDebugRenderable renderable, ConfigEntry<Boolean> config) { this.rendererLists.removeRenderable(renderable, config); }

	public void clearRenderables() { this.rendererLists.clearRenderables(); }
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	public static final class Box
	{
		public DhVec3f minPos;
		public DhVec3f maxPos;
		public Color color;
		
		
		
		public Box(long pos, float minY, float maxY, float marginPercent, Color color)
		{
			float edgeOffset = DhSectionPos.getBlockWidth(pos) * marginPercent;
			
			int minBlockPosX = DhSectionPos.getMinCornerBlockX(pos);
			int minBlockPosZ = DhSectionPos.getMinCornerBlockZ(pos);
			int maxBlockPosX = minBlockPosX + DhSectionPos.getBlockWidth(pos);
			int maxBlockPosZ = minBlockPosZ + DhSectionPos.getBlockWidth(pos);
			
			this.minPos = new DhVec3f(minBlockPosX + edgeOffset, minY, minBlockPosZ + edgeOffset);
			this.maxPos = new DhVec3f(maxBlockPosX - edgeOffset, maxY, maxBlockPosZ - edgeOffset);
			this.color = color;
		}
		
		/** only used for */
		public Box(DhVec3f minPos, DhVec3f maxPos, Color color)
		{
			this.minPos = minPos;
			this.maxPos = maxPos;
			this.color = color;
		}
		
	}
	
	public static final class BoxParticle implements Comparable<BoxParticle>
	{
		public Box box;
		public long startMsTime;
		public long durationInMs;
		public float yChange;
		
		
		private BoxParticle(Box box, long startMsTime, long durationInMs, float yChange)
		{
			this.box = box;
			this.startMsTime = startMsTime;
			this.durationInMs = durationInMs;
			this.yChange = yChange;
		}
		
		public BoxParticle(Box box, double secondDuration, float yChange)
		{ this(box, System.currentTimeMillis(), (long) (secondDuration * 1_000), yChange); }
		
		
		@Override
		public int compareTo(@NotNull BoxParticle particle)
		{ return Long.compare(this.startMsTime + this.durationInMs, particle.startMsTime + particle.durationInMs); }
		
		/** will change each time it's called based on the yChange value and time */
		public Box createNewRenderBox()
		{
			long nowMs = System.currentTimeMillis();
			
			float percent = (nowMs - this.startMsTime) / (float) this.durationInMs;
			percent = (float) Math.pow(percent, 4);
			float yDiff = this.yChange * percent;
			
			return new Box(
				new DhVec3f(this.box.minPos.x, this.box.minPos.y + yDiff, this.box.minPos.z),
				new DhVec3f(this.box.maxPos.x, this.box.maxPos.y + yDiff, this.box.maxPos.z),
				this.box.color);
		}
		
		public boolean isDead() { return (System.currentTimeMillis() - this.startMsTime) > this.durationInMs; }
		
	}
	
	protected static class RendererLists
	{
		public final LinkedList<WeakReference<IDebugRenderable>> generalRenderableList = new LinkedList<>();
		
		private final HashMap<ConfigEntry<Boolean>, LinkedList<WeakReference<IDebugRenderable>>> renderableListByConfig = new HashMap<>();
		
		
		
		//==============//
		// registration //
		//==============//
		//region
		
		public void addRenderable(IDebugRenderable renderable, @Nullable ConfigEntry<Boolean> config)
		{
			synchronized (this)
			{
				if (config != null)
				{
					if (!this.renderableListByConfig.containsKey(config))
					{
						this.renderableListByConfig.put(config, new LinkedList<>());
					}
					
					LinkedList<WeakReference<IDebugRenderable>> renderableList = this.renderableListByConfig.get(config);
					renderableList.add(new WeakReference<>(renderable));
				}
				else
				{
					this.generalRenderableList.add(new WeakReference<>(renderable));
				}
			}
		}
		
		public void removeRenderable(IDebugRenderable renderable, @Nullable ConfigEntry<Boolean> config)
		{
			synchronized (this)
			{
				if (config != null)
				{
					if (this.renderableListByConfig.containsKey(config))
					{
						LinkedList<WeakReference<IDebugRenderable>> renderableList = this.renderableListByConfig.get(config);
						this.removeRenderableFromInternalList(renderableList, renderable);
					}
				}
				else
				{
					this.removeRenderableFromInternalList(this.generalRenderableList, renderable);
				}
			}
		}
		private void removeRenderableFromInternalList(LinkedList<WeakReference<IDebugRenderable>> rendererList, IDebugRenderable renderable)
		{
			Iterator<WeakReference<IDebugRenderable>> iterator = rendererList.iterator();
			while (iterator.hasNext())
			{
				WeakReference<IDebugRenderable> renderableRef = iterator.next();
				if (renderableRef.get() == null)
				{
					iterator.remove();
					continue;
				}
				
				if (renderableRef.get() == renderable)
				{
					iterator.remove();
					return;
				}
			}
		}
		
		public void clearRenderables()
		{
			for (ConfigEntry<Boolean> config : this.renderableListByConfig.keySet())
			{
				LinkedList<WeakReference<IDebugRenderable>> renderableList = this.renderableListByConfig.get(config);
				if (config.get() && renderableList != null)
				{
					renderableList.clear();
				}
			}
		}
		
		//endregion
		
		
		
		//===========//
		// rendering //
		//===========//
		//region
		
		public void render(AbstractDebugWireframeRenderer debugRenderer)
		{
			this.renderList(debugRenderer, this.generalRenderableList);
			
			for (ConfigEntry<Boolean> config : this.renderableListByConfig.keySet())
			{
				LinkedList<WeakReference<IDebugRenderable>> renderableList = this.renderableListByConfig.get(config);
				if (config.get() && renderableList != null && renderableList.size() != 0)
				{
					this.renderList(debugRenderer, renderableList);
				}
			}
		}
		private void renderList(AbstractDebugWireframeRenderer debugRenderer, LinkedList<WeakReference<IDebugRenderable>> rendererList)
		{
			synchronized (this)
			{
				try
				{
					Iterator<WeakReference<IDebugRenderable>> iterator = rendererList.iterator();
					while (iterator.hasNext())
					{
						WeakReference<IDebugRenderable> ref = iterator.next();
						IDebugRenderable renderable = ref.get();
						if (renderable == null)
						{
							iterator.remove();
							continue;
						}
						
						renderable.debugRender(debugRenderer);
					}
				}
				catch (Exception e)
				{
					RATE_LIMITED_LOGGER.error("Unexpected Debug renderer error, Error: "+e.getMessage(), e);
				}
			}
		}
		
		//endregion
	}
	
	//endregion
	
	
	
}
