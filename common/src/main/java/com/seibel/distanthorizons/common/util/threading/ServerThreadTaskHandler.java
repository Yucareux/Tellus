package com.seibel.distanthorizons.common.util.threading;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

// TODO move to core
//  Although only currently called in MC 1.7.10.
//  Maybe move into Forge17 instead?
/**
 * Queues work that must run on the Minecraft server thread. The platform's
 * server-tick callback is responsible for calling {@link #onTickStart()} at the
 * start of each server tick and {@link #runTasks()} at the end of it.
 */
public class ServerThreadTaskHandler
{
	public static final ServerThreadTaskHandler INSTANCE = new ServerThreadTaskHandler();

	/** How long a server tick is allowed to take before the server falls below 20 TPS. */
	private static final long TICK_TARGET_NANO = 50_000_000L;
	/**
	 * Never claimed, even on a completely empty tick. Covers work that happens outside
	 * the window we can measure: whatever the server did before the tick-start event
	 * fired, and whatever runs after we hand the tick back (other mods' end-of-tick
	 * handlers, world saving, the next tick's network drain).
	 */
	private static final long TICK_RESERVE_NANO = 25_000_000L;
	/**
	 * Upper bound on a single tick's budget.
	 */
	private static final long MAX_BUDGET_NANO = 35_000_000L;
	/**
	 * Budget used when the platform doesn't report tick starts.
	 */
	private static final long FALLBACK_BUDGET_NANO = 15_000_000L;

	private static final long TICK_START_NOT_SET = Long.MIN_VALUE;

	private final ConcurrentLinkedQueue<QueuedTask<?>> deferrableTaskQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<QueuedTask<?>> essentialTaskQueue = new ConcurrentLinkedQueue<>();
	private IMinecraftSharedWrapper mcSharedWrapper = null;
	private volatile boolean isShutdown;
	/**
	 * When the current server tick started, or {@link #TICK_START_NOT_SET}. <br>
	 * Written by {@link #onTickStart()} and consumed by {@link #runTasks()}, both of which
	 * only run on the server thread; volatile so {@link #reset()} can clear it from elsewhere.
	 */
	private volatile long tickStartNano = TICK_START_NOT_SET;



	private ServerThreadTaskHandler() { }



	/**
	 * Queues a task that may be deferred to a later tick while the server thread is unhealthy. <br>
	 * Use this for work that only adds load, like requesting a chunk.
	 */
	public <T> CompletableFuture<T> queueTask(Supplier<T> task)
	{ return addTask(this.deferrableTaskQueue, task); }

	/**
	 * Queues a task that runs even while the server thread is unhealthy. <br>
	 * Use this for work that removes load, like releasing a chunk. Deferring that
	 * work would keep chunks loaded exactly when memory pressure is at its worst.
	 */
	public <T> CompletableFuture<T> queueEssentialTask(Supplier<T> task)
	{
		return addTask(this.essentialTaskQueue, task);
	}

	private <T> CompletableFuture<T> addTask(ConcurrentLinkedQueue<QueuedTask<?>> taskQueue, Supplier<T> task)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		if (this.isShutdown)
		{
			cancelFuture(future);
			return future;
		}

		QueuedTask<T> queuedTask = new QueuedTask<>(task, future);
		taskQueue.add(queuedTask);

		// Close the race where shutdown drains the queue immediately before this add.
		if (this.isShutdown && taskQueue.remove(queuedTask))
		{
			cancelFuture(future);
		}

		return future;
	}

	/** Records the start of a server tick so {@link #runTasks()} can tell how much of it is left. */
	public void onTickStart()
	{
		this.tickStartNano = System.nanoTime();
	}

	/**
	 * Runs queued tasks on the server thread using whatever time is left in the current tick.
	 */
	public void runTasks()
	{
		long deadlineNano = this.deadlineForTickNano(this.tickStartNano);
		this.tickStartNano = TICK_START_NOT_SET;

		// note: if essential tasks keep using up the whole budget then deferrable
		// tasks will starve. That's acceptable, since essential tasks only exist
		// because deferrable ones already ran, so the backlog drains instead of deadlocking.
		if (!runQueueUntilDeadline(this.essentialTaskQueue, deadlineNano))
		{
			return;
		}

		if (this.deferrableTaskQueue.isEmpty())
		{
			return;
		}

		if (!this.deferrableTasksCanRun())
		{
			return;
		}

		runQueueUntilDeadline(this.deferrableTaskQueue, deadlineNano);
	}

	/** @return the deadline for this tick's queued work. */
	private long deadlineForTickNano(long currentTickStartNano)
	{
		long nowNano = System.nanoTime();
		if (currentTickStartNano == TICK_START_NOT_SET)
		{
			return nowNano + FALLBACK_BUDGET_NANO;
		}

		long tickDeadlineNano = currentTickStartNano + TICK_TARGET_NANO - TICK_RESERVE_NANO;
		long maxBudgetDeadlineNano = nowNano + MAX_BUDGET_NANO;
		return Math.min(tickDeadlineNano, maxBudgetDeadlineNano);
	}
	
	private boolean deferrableTasksCanRun()
	{
		if (!Config.Common.WorldGenerator.limitIfServerUnhealthy.get())
		{
			return true;
		}

		return mcSharedWrapper == null || mcSharedWrapper.isServerThreadHealthy();
	}

	/** @return true if the queue was emptied, false if the deadline was reached first. */
	private static boolean runQueueUntilDeadline(ConcurrentLinkedQueue<QueuedTask<?>> taskQueue, long deadlineNano)
	{
		QueuedTask<?> queuedTask;
		while ((queuedTask = taskQueue.poll()) != null)
		{
			queuedTask.run();
			if (System.nanoTime() >= deadlineNano)
			{
				return false;
			}
		}

		return true;
	}

	/** Completes queued tasks exceptionally without running them. */
	public void cancelPendingTasks()
	{
		// Set this before draining so concurrent submissions either get drained or
		// observe shutdown themselves after entering the queue.
		this.isShutdown = true;
		cancelQueuedTasks(this.essentialTaskQueue);
		cancelQueuedTasks(this.deferrableTaskQueue);
	}

	/** Prepares this singleton handler for a new Minecraft server session. */
	public void reset()
	{
		this.isShutdown = false;
		this.tickStartNano = TICK_START_NOT_SET;
		if (mcSharedWrapper == null) {
			mcSharedWrapper = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
		}
	}

	private static void cancelQueuedTasks(ConcurrentLinkedQueue<QueuedTask<?>> taskQueue)
	{
		QueuedTask<?> queuedTask;
		while ((queuedTask = taskQueue.poll()) != null)
		{
			cancelFuture(queuedTask.future);
		}
	}
	private static void cancelFuture(CompletableFuture<?> future)
	{
		future.completeExceptionally(
			new CancellationException("The Minecraft server stopped before the queued task could run."));
	}

	private static class QueuedTask<T>
	{
		private final Supplier<T> task;
		private final CompletableFuture<T> future;

		private QueuedTask(Supplier<T> task, CompletableFuture<T> future)
		{
			this.task = task;
			this.future = future;
		}

		private void run()
		{
			try
			{
				this.future.complete(this.task.get());
			}
			catch (Throwable throwable)
			{
				this.future.completeExceptionally(throwable);
			}
		}
	}
}
