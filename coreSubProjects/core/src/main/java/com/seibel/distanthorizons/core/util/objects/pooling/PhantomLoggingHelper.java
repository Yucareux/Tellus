package com.seibel.distanthorizons.core.util.objects.pooling;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

public class PhantomLoggingHelper
{
	/**
	 * This was separated out so it could be used for other string pair lists.
	 * James originally had an idea to add a shorter static string
	 * ID to each allocated {@link PhantomArrayListCheckout} as a simpler version of the stack trace,
	 * however it became a bit more difficult and messy than he wanted to deal with, so for now we just
	 * have the stack trace.
	 */
	public static void putAndIncrementTrackingString(
		String key,
		ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList)
	{
		// sequential search, for the number of elements we're dealing with (less than 20)
		// this should be sufficiently fast
		boolean pairFound = false;
		for (int i = 0; i < allocationStackTraceCountPairList.size(); i++)
		{
			Pair<String, AtomicInteger> possiblePair = allocationStackTraceCountPairList.get(i);
			if (possiblePair.first.equals(key))
			{
				possiblePair.second.getAndIncrement();
				pairFound = true;
				break;
			}
		}
		
		if (!pairFound)
		{
			allocationStackTraceCountPairList.add(new Pair<>(key, new AtomicInteger(1)));
		}
	}
	
	public static void LogAllocationStackTracePairCounts(DhLogger logger, String prefix, ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList)
	{
		// high numbers first
		allocationStackTraceCountPairList.sort((a, b) -> Integer.compare(b.second.get(), a.second.get()));
		
		StringBuilder stringBuilder = new StringBuilder();
		for (int j = 0; j < allocationStackTraceCountPairList.size(); j++)
		{
			int count = allocationStackTraceCountPairList.get(j).second.get();
			String stack = allocationStackTraceCountPairList.get(j).first;
			
			stringBuilder.append(count).append(". ").append(stack).append("\n");
		}
		logger.warn(prefix+" Stacks: ["+ allocationStackTraceCountPairList.size()+"]\n" + stringBuilder.toString());
	}
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	/**
	 * Can be quickly added to a {@link AutoCloseable} implementing
	 * class to confirm it's being properly closed.
	 */
	public static class BasicPhantomReference implements AutoCloseable
	{
		private static final DhLogger LOGGER = new DhLoggerBuilder().build();
		
		/** if enabled the number of GC'ed buffers will be logged */
		private static final boolean LOG_PHANTOM_RECOVERY = true;
		/**
		 * If enabled the GC'ed buffers allocation/upload stacks will be logged. 
		 * Note: due to how the buffers are often run on the render thread,
		 * these stacks will likely only be of limited use.
		 * For more robust debugging it would likely be best to somehow track
		 * the stacks of where these calls are happening before they're queued
		 * for the render thread.
		 */
		private static final boolean LOG_PHANTOM_ALLOCATION_STACKS = true;
		
		private static final int PHANTOM_REF_CHECK_TIME_IN_MS = 5 * 1000;
		private static final ReferenceQueue<BasicPhantomReference> PHANTOM_REFERENCE_QUEUE = new ReferenceQueue<>();
		private static final ConcurrentHashMap<PhantomReference<? extends BasicPhantomReference>, Class<?>> PHANTOM_TO_PARENT_CLASS = new ConcurrentHashMap<>();
		
		private static final ThreadPoolExecutor CLEANUP_THREAD = ThreadUtil.makeSingleDaemonThreadPool("BasicPhantom Cleanup");
		
		
		private final Class<?> parentClass;
		private final PhantomReference<? extends BasicPhantomReference> phantomReference; 
		
		
		
		//==============//
		// constructors //
		//==============//
		//region
		
		static { CLEANUP_THREAD.execute(() -> runPhantomReferenceCleanupLoop()); }
		
		public BasicPhantomReference(Class<?> parentClass)
		{
			this.parentClass = parentClass;
			this.phantomReference = new PhantomReference<>(this, PHANTOM_REFERENCE_QUEUE);
			PHANTOM_TO_PARENT_CLASS.put(this.phantomReference, this.parentClass);
		}
		
		//endregion
		
		
		
		//================//
		// base overrides //
		//================//
		//region
		
		@Override 
		public void close()
		{
			this.phantomReference.clear();
			PHANTOM_TO_PARENT_CLASS.remove(this.phantomReference);
		}
		
		//endregion
		
		
		
		//================//
		// static cleanup //
		//================//
		//region
		
		private static void runPhantomReferenceCleanupLoop()
		{
			// these arrays are stored here so they don't have to be re-allocated each loop
			ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList = new ArrayList<>();
			ArrayList<Pair<String, AtomicInteger>> parentClassNameCountPairList = new ArrayList<>();
			
			while (true)
			{
				allocationStackTraceCountPairList.clear();
				parentClassNameCountPairList.clear();
				
				try
				{
					try
					{
						Thread.sleep(PHANTOM_REF_CHECK_TIME_IN_MS);
					}
					catch (InterruptedException ignore) { }
					
					int collectedCount = 0;
					
					Reference<? extends BasicPhantomReference> phantomRef = PHANTOM_REFERENCE_QUEUE.poll();
					while (phantomRef != null)
					{
						// destroy the buffer if it hasn't been cleared yet
						Class<?> parentClass = PHANTOM_TO_PARENT_CLASS.remove((PhantomReference<? extends BasicPhantomReference>)phantomRef); // cast to make IntelliJ happy
						{
							String parentClassName = "NULL";
							if (parentClass != null)
							{
								parentClassName = parentClass.getSimpleName();
							}
							
							PhantomLoggingHelper.putAndIncrementTrackingString(parentClassName, parentClassNameCountPairList);
							//LOGGER.info("Phantom collected for class: [" + parentClassName + "]");
						}
							
						
						//if (LOG_PHANTOM_ALLOCATION_STACKS) // stack trace shouldn't be null, but just in case
						//{
						//	String stack = BUFFER_ID_TO_ALLOCATION_STRING.get(idRef);
						//	if (stack != null)
						//	{
						//		PhantomLoggingHelper.putAndIncrementTrackingString(stack, allocationStackTraceCountPairList);
						//	}
						//}
						
						
						collectedCount++;
						phantomRef = PHANTOM_REFERENCE_QUEUE.poll();
					}
					
					
					
					if (LOG_PHANTOM_RECOVERY)
					{
						// we only want to log when something has been returned
						if (collectedCount != 0)
						{
							LOGGER.warn("Phantoms collected: ["+ F3Screen.NUMBER_FORMAT.format(collectedCount)+"].");
							
							PhantomLoggingHelper.LogAllocationStackTracePairCounts(LOGGER, "Parent class", parentClassNameCountPairList);
							
							//// log stack traces if present
							//if (LOG_PHANTOM_ALLOCATION_STACKS)
							//{
							//	PhantomLoggingHelper.LogAllocationStackTracePairCounts(LOGGER, allocationStackTraceCountPairList);
							//}
						}
					}
					
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected error in buffer cleanup thread: [" + e.getMessage() + "].", e);
				}
			}
		}
		
		//endregion
		
		
	}
	
	//endregion
	
	
	
}
