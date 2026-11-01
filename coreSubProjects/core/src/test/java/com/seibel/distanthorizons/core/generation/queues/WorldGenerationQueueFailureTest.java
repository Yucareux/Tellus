package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WorldGenerationQueueFailureTest
{
	@BeforeClass
	public static void bindRenderer()
	{
		SingletonInjector.INSTANCE.bind(AbstractDebugWireframeRenderer.class, new AbstractDebugWireframeRenderer() {
			@Override public void renderBox(Box box) { }
		});
	}

	@Test
	public void failedFutureReleasesWorkerSlot() throws Exception { checkFailure(false); }

	@Test
	public void synchronousFailureReleasesWorkerSlot() throws Exception { checkFailure(true); }

	private void checkFailure(boolean synchronous) throws Exception
	{
		IDhApiWorldGenerator generator = new IDhApiWorldGenerator() {
			@Override public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }
			@Override public void preGeneratorTaskStart() { }
			@Override public void close() { }
			@Override public CompletableFuture<Void> generateLod(int x, int z, int lodX, int lodZ, byte detail,
				IDhApiFullDataSource source, EDhApiDistantGeneratorMode mode, ExecutorService executor,
				Consumer<IDhApiFullDataSource> consumer)
			{
				if (synchronous) { throw new IllegalStateException("Simulated source failure"); }
				CompletableFuture<Void> future = new CompletableFuture<>();
				future.completeExceptionally(new IllegalStateException("Simulated async failure"));
				return future;
			}
		};
		try (WorldGenerationQueue queue = new WorldGenerationQueue(generator, null))
		{
			Method start = WorldGenerationQueue.class.getDeclaredMethod("startWorldGenTaskGroup", DataSourceRetrievalTask.class);
			start.setAccessible(true);
			for (int x = 0; x < 4; x++)
			{
				DataSourceRetrievalTask task = new DataSourceRetrievalTask(DhSectionPos.encode((byte) 6, x, 0), (byte) 0);
				start.invoke(queue, task);
				Assert.assertTrue(task.future.isCompletedExceptionally());
				Assert.assertEquals("A failed task must not consume a generation slot", 0, queue.getInProgressTaskCount());
			}
		}
	}
}
