package com.seibel.distanthorizons.core.util.threading;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * This handles dividing work DH needs to do across
 * DH's thread pool.
 */
public class PriorityTaskPicker
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE = Executors.newScheduledThreadPool(1, new DhThreadFactory("Task Picker Re-queue Schedule", Thread.NORM_PRIORITY, true));
	/** 
	 * If a thread is paused we can end up in a situation where
	 * all the other pools are done, causing {@link PriorityTaskPicker#tryStartNextTask()}
	 * to queue nothing, preventing the paused pools from running until
	 * more work is queued. <br><br>
	 * 
	 * This way we can check on those paused threads at a future time
	 * to queue their work.
	 */
	private static final int MS_TO_CHECK_ON_PAUSED_THREADS = 1_000;
	
	private final AtomicReference<ScheduledFuture<?>> scheduledFutureRef = new AtomicReference<>();
	private final Runnable startNextTaskBlockingRunnable = () -> this.startNextTask(true);
	
	
	/** the list of currently registered executors */
	private final ArrayList<Executor> executors = new ArrayList<>();
	
	/** Lock to ensure task picking logic is thread-safe */
	private final ReentrantLock taskPickerLock = new ReentrantLock();
	/** Tracks the number of active threads */
	private final AtomicInteger occupiedThreadsRef = new AtomicInteger(0);
	
	private final AtomicBoolean isShutDownRef = new AtomicBoolean(false);
	
	
	
	//==========//
	// executor //
	//==========//
	///region
	
	public Executor createExecutor(String name) { return this.createExecutor(name, Thread.NORM_PRIORITY, null); }
	public Executor createExecutor(String name, int priority) { return this.createExecutor(name, priority, null); }
	public Executor createExecutor(String name, int priority, @Nullable PriorityTaskPicker.IExecutorCanRunFunc canRunFunc)
	{
		Executor executor = new Executor(this, name, priority, canRunFunc);
		this.executors.add(executor);
		return executor;
	}
	
	/**
	 * Tries to start the next queued task
	 * for one of the available executors.
	 */
	private void tryStartNextTask() { this.startNextTask(false); }
	
	private void startNextTask(boolean waitForLock)
	{
		// only let one thread start the next task to prevent concurrency errors
		if (waitForLock)
		{
			// generally this will be done if an executor is paused,
			// and we want to check up on it later
			this.taskPickerLock.lock();
		}
		else
		{
			// most threads won't need to try queuing the next 
			// task since that means someone else is already working on it
			if (!this.taskPickerLock.tryLock())
			{
				return;
			}
		}
		
		
		try
		{
			boolean executorPaused = false;
			
			// fill up executors that have run for less time first,
			// this prevents long-running tasks from taking up all the CPU time
			Iterator<Executor> iterator = this.getExecutorIteratorSortedByShortestTotalRunTime();
			while (iterator.hasNext())
			{
				Executor executor = iterator.next();
				
				// skip executors that are paused
				if (!executor.canRun())
				{
					executorPaused = true;
					continue;
				}
				
				TrackedRunnable task;
				
				// start tasks until we're running as many threads as acceptable by the config,
				// or until this executor is empty,
				// or until we should move on to the next executor
				while (this.occupiedThreadsRef.get() < Config.Common.MultiThreading.numberOfThreads.get()
						&& executor.canTakeAnotherThread()
						&& (task = executor.taskQueue.poll()) != null)
				{
					try
					{
						executor.runTask(task);
						this.occupiedThreadsRef.getAndIncrement();
						executor.occupiedThreadsRef.getAndIncrement();
					}
					catch (RejectedExecutionException e)
					{
						if (this.isShutDownRef.get())
						{
							// Clear this executor's tasks since we no longer expect anything to execute.
							executor.taskQueue.clear();
						}
						else
						{
							// This executor is still running, there must have been a glitch with the
							// underlying thread pool.
							// Re-queue the task so we don't lose any tasks
							// (failing to re-queue tasks can cause LODs to fail to load due
							// to completable futures becoming orphaned).
							executor.taskQueue.add(task);
						}
					}
				}
			}
			
			
			// if an executor is paused then we'll
			// need to check on it again sometime in the future
			// otherwise we may not start the next task for a while
			ScheduledFuture<?> newScheduledFuture = null;
			if (executorPaused)
			{
				newScheduledFuture = SCHEDULED_EXECUTOR_SERVICE.schedule(
					this.startNextTaskBlockingRunnable,
					MS_TO_CHECK_ON_PAUSED_THREADS, TimeUnit.MILLISECONDS);
			}
			
			ScheduledFuture<?> oldScheduledFuture = this.scheduledFutureRef.getAndSet(newScheduledFuture);
			if (oldScheduledFuture != null)
			{
				// stop the last scheduled check,
				// we just checked the queue and will want to wait the full
				// timeout first
				oldScheduledFuture.cancel(false);
			}
		}
		finally
		{
			this.taskPickerLock.unlock();
		}
	}
	private Iterator<Executor> getExecutorIteratorSortedByShortestTotalRunTime()
	{
		Stream<Executor> stream = this.executors.stream();
		// returns smaller numbers first
		//stream = stream.sorted(Comparator.comparingLong((executor) -> executor.totalRuntimeNanos.get()));
		stream = stream.sorted((Executor a, Executor b) ->
		{
			int priorityCompare = Integer.compare(b.priority, a.priority); // higher number first
			if (priorityCompare != 0)
			{
				return priorityCompare;
			}
			
			return Long.compare(a.totalRuntimeNanos.get(), b.totalRuntimeNanos.get()); // lower number first
		});
		return stream.iterator();
	}
	
	/** Blocking, shuts down the thread pool immediately, stopping all tasks. */
	public void shutdownNow()
	{
		LOGGER.info("Shutting down PriorityTaskPicker thread pool...");
		this.isShutDownRef.set(true);
		
		// signal all executors to shutdown
		for (int i = 0; i < this.executors.size(); i++)
		{
			Executor executor = this.executors.get(i);
			if (executor != null)
			{
				executor.shutdown();
			}
		}
		
		// since they're all already shutting down concurrently, this is bounded by the slowest one
		try
		{
			for (int i = 0; i < this.executors.size(); i++)
			{
				Executor executor = this.executors.get(i);
				if (executor != null)
				{
					if (!executor.awaitTermination(5, TimeUnit.SECONDS))
					{
						executor.shutdownNow();
					}
				}
			}
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	///endregion
	
	
	
	//================//
	// helper classes //
	//================//
	///region
	
	/**
	 * Each executor handles a specific type of work that DH needs done.
	 * By separating out task into its own executor it allows for easier performance monitoring
	 * via a tool like Visual VM and fairly spreading out CPU time between tasks.
	 */
	public static class Executor extends AbstractExecutorService implements IConfigListener
	{
		private final PriorityTaskPicker parentTaskPicker;
		private final String name;
		
		private final Queue<TrackedRunnable> taskQueue = new ConcurrentLinkedQueue<>();
		
		private final AtomicInteger runningTasksRef = new AtomicInteger(0);
		private final AtomicInteger completedTasksRef = new AtomicInteger(0);
		/** tasks handed to {@link #threadPoolExecutor} that haven't finished yet */
		private final AtomicInteger occupiedThreadsRef = new AtomicInteger(0);
		
		/** 
		 * The share of the threads this executor may occupy while another executor has tasks waiting. <br>
		 * Without it a higher priority executor with a steady stream of tasks never leaves a thread to the others.
		 */
		private double maxThreadShareWhileOthersWait = 1.0;
		
		/** 
		 * a higher number means this executor will run before others. <br> 
		 * 0 = default
		 */
		private final int priority;
		
		private final AtomicLong totalRuntimeNanos = new AtomicLong(0);
		/** used for performance logging */
		private final RollingAverage runTimeInMsRollingAverage = new RollingAverage(200);
		
		@Nullable
		private final PriorityTaskPicker.IExecutorCanRunFunc canRunFunc;
		
		/** holds the threads this {@link Executor} can run */
		private RateLimitedThreadPoolExecutor threadPoolExecutor;
		
		
		
		//=============//
		// constructor //
		//=============//
		///region
		
		public Executor(PriorityTaskPicker parentTaskPicker, String name, int priority, @Nullable PriorityTaskPicker.IExecutorCanRunFunc canRunFunc)
		{
			this.parentTaskPicker = parentTaskPicker;
			this.name = name;
			this.priority = priority;
			this.canRunFunc = canRunFunc;
			
			this.threadPoolExecutor = this.createThreadPool();
			
			Config.Common.MultiThreading.numberOfThreads.addListener(this);
		}
		
		private RateLimitedThreadPoolExecutor createThreadPool()
		{
			return new RateLimitedThreadPoolExecutor(
					Config.Common.MultiThreading.numberOfThreads.get(),
					new DhThreadFactory(this.name, Config.Common.MultiThreading.threadPriority.get(), false),
					new ArrayBlockingQueue<>(Runtime.getRuntime().availableProcessors())
			);
		}
		
		///endregion
		
		
		
		//=================//
		// config handling //
		//=================//
		///region
		
		@Override
		public void onConfigValueSet() 
		{
			RateLimitedThreadPoolExecutor oldExecutor = this.threadPoolExecutor;
			this.threadPoolExecutor = this.createThreadPool();
			
			// shut down the old executor after replacing it with the new one
			// to make sure no tasks are lost in the transfer
			if (oldExecutor != null)
			{
				oldExecutor.shutdown();
			}
		}
		
		///endregion
		
		
		
		//=====================//
		// task queue handling //
		//=====================//
		///region
		
		@Override
		public void execute(@NotNull Runnable command)
		{
			// needed so we don't try queuing tasks that will never be completed
			if (this.threadPoolExecutor.isShutdown())
			{
				throw new RejectedExecutionException("Thread pool ["+this.name+"] shutdown.");
			}
			
			
			this.taskQueue.add(new TrackedRunnable(this.parentTaskPicker, this, command));
			
			// Attempt to start the task immediately
			this.parentTaskPicker.tryStartNextTask();
		}
		
		/** The passed in {@link Runnable} must be exactly the same as the one passed into {@link PriorityTaskPicker.Executor#execute(Runnable)} */
		public void remove(@NotNull Runnable command) { this.taskQueue.removeIf(trackedRunnable -> trackedRunnable.command == command); }
		
		/** Lets a paused executor resume as soon as it can run again instead of at the next periodic check. */
		public void wakeUp() { this.parentTaskPicker.tryStartNextTask(); }
		
		/** @see #maxThreadShareWhileOthersWait */
		public Executor limitThreadShareWhileOthersWait(double share)
		{
			this.maxThreadShareWhileOthersWait = share;
			return this;
		}
		
		private boolean canTakeAnotherThread()
		{
			if (this.maxThreadShareWhileOthersWait >= 1.0)
			{
				return true;
			}
			
			int maxThreads = Math.max(1, (int) (Config.Common.MultiThreading.numberOfThreads.get() * this.maxThreadShareWhileOthersWait));
			if (this.occupiedThreadsRef.get() < maxThreads)
			{
				return true;
			}
			
			// the limit only matters while someone else is waiting for a thread
			for (Executor executor : this.parentTaskPicker.executors)
			{
				if (executor != this
					&& !executor.taskQueue.isEmpty()
					&& executor.canRun())
				{
					return false;
				}
			}
			return true;
		}
		
		
		public void runTask(@NotNull Runnable command) { this.threadPoolExecutor.execute(command); }
		
		
		public int getQueueSize() { return this.taskQueue.size(); }
		/** number of threads */
		public int getPoolSize() { return Config.Common.MultiThreading.numberOfThreads.get(); }
		
		public int getRunningTaskCount() { return this.runningTasksRef.get(); }
		public int getCompletedTaskCount() { return this.completedTasksRef.get(); }
		/** Will return NaN if nothing has been submitted yet */
		public double getAverageRunTimeInMs() { return this.runTimeInMsRollingAverage.getAverage(); }
		
		public void clearQueue() { this.taskQueue.clear(); }
		
		public boolean canRun()
		{
			if (this.canRunFunc == null)
			{
				return true;
			}
			
			return this.canRunFunc.canRun();
		}
		
		
		//endregion
		
		
		
		//==========//
		// shutdown //
		//==========//
		///region
		
		@Override
		public void shutdown() { this.threadPoolExecutor.shutdown(); }
		
		@Override
		public @NotNull List<Runnable> shutdownNow() { return this.threadPoolExecutor.shutdownNow(); }
		
		@Override
		public boolean isShutdown() { return this.threadPoolExecutor.isShutdown(); }
		@Override
		public boolean isTerminated() { return this.threadPoolExecutor.isTerminated(); }
		
		@Override
		public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException 
		{ return this.threadPoolExecutor.awaitTermination(timeout, unit); }
		
		///endregion
		
		
		
		//===========//
		// debugging //
		//===========//
		///region
		
		public static String getThreadPoolStatString(String displayName, PriorityTaskPicker.Executor pool)
		{
			String o = MinecraftTextFormat.ORANGE;
			String g = MinecraftTextFormat.GREEN;
			String b = MinecraftTextFormat.DARK_BLUE;
			String y = MinecraftTextFormat.YELLOW;
			String cf = MinecraftTextFormat.CLEAR_FORMATTING;
			
			
			
			NumberFormat numberFormat = F3Screen.NUMBER_FORMAT;
			
			String queueSize = (pool != null) ? numberFormat.format(pool.getQueueSize()) : "-";
			String completedCount = (pool != null) ? numberFormat.format(pool.getCompletedTaskCount()) : "-";
			
			String message = displayName+", Tasks: "+o+queueSize+cf+", Done: "+g+completedCount+cf;
			
			if (pool != null)
			{
				// active threads
				int activeThreadCount = pool.getRunningTaskCount();
				int threadCount = pool.getPoolSize();
				
				boolean threadPoolActive = pool.canRun();
				String poolActiveString = threadPoolActive ? ("Active") : (o+"Paused"+cf);
				
				message += ", "+poolActiveString+": "+y+activeThreadCount+cf+"/"+threadCount;
				
				// thread runtime
				String runTimeAvgStr;
				double runTimeAvgInMs = pool.getAverageRunTimeInMs();
				if (!Double.isNaN(runTimeAvgInMs))
				{
					runTimeAvgStr = numberFormat.format(runTimeAvgInMs);
				}
				else
				{
					runTimeAvgStr = "<0";
				}
				
				message += ", Avg: "+b+runTimeAvgStr+"ms"+cf;
			}
			
			
			return message;
		}
		
		///endregion
		
		
		
		//================//
		// base overrides //
		//================//
		///region
		
		@Override 
		public String toString() { return getThreadPoolStatString(this.name, this); }
		
		///endregion
		
	}
	
	/** used so we can {@link PriorityTaskPicker.Executor#remove(Runnable)} using the original {@link Runnable} */
	private static class TrackedRunnable implements Runnable
	{
		private final PriorityTaskPicker parentTaskPicker;
		private final Executor executor;
		
		/** the runnable passed into {@link PriorityTaskPicker.Executor#execute(Runnable)} */
		public final Runnable command;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		public TrackedRunnable(PriorityTaskPicker parentTaskPicker, Executor executor, Runnable command)
		{
			this.parentTaskPicker = parentTaskPicker;
			this.executor = executor;
			this.command = command;
		}
		
		
		
		//=========//
		// running //
		//=========//
		
		@Override
		public void run()
		{
			this.executor.runningTasksRef.getAndIncrement();
			
			long startTime = System.nanoTime();
			try
			{
				this.command.run();
			}
			finally
			{
				long timeElapsed = System.nanoTime() - startTime;
				this.executor.runTimeInMsRollingAverage.add(TimeUnit.NANOSECONDS.toMillis(timeElapsed));
				
				// Update variables related to task status
				this.parentTaskPicker.occupiedThreadsRef.getAndDecrement();
				this.executor.occupiedThreadsRef.getAndDecrement();
				this.executor.runningTasksRef.getAndDecrement();
				this.executor.completedTasksRef.getAndIncrement();
				this.executor.totalRuntimeNanos.addAndGet(timeElapsed);
				
				this.parentTaskPicker.tryStartNextTask();
			}
		}
		
	}
	
	/**
	 * Provides a way to dynamically enable/disable
	 * certain {@link PriorityTaskPicker.Executor}'s.
	 */
	@FunctionalInterface
	public interface IExecutorCanRunFunc
	{
		boolean canRun();
	}
	
	///endregion
	
	
	
}
