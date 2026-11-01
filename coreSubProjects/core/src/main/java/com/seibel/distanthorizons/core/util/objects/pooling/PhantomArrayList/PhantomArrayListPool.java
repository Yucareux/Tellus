package com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomLoggingHelper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.chars.CharArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DH uses a lot of potentially large arrays of {@link Byte}s and {@link Long}s.
 * In order to reduce Garbage Collector (GC) stuttering and array allocation overhead
 * we pool these arrays when possible. <br><br>
 * 
 * How pooled arrays can be returned: <br>
 * 1. <b> Closing the {@link AbstractPhantomArrayList} </b> <br>
 * The fastest and most efficient method of returning pooled arrays
 * is to call {@link AutoCloseable#close()}. <br><br>
 * 
 * 2. <b> {@link AbstractPhantomArrayList} Garbage Collection </b> <br>
 * Some objects are used across many different threads and
 * cleanly closing them is impossible, so when the {@link AbstractPhantomArrayList}
 * is automatically garbage collected we recover and recycle any
 * arrays it checked out.
 * This is less efficient since it may allow a lot of additional arrays to
 * be created while we wait for the garbage collector to run, but 
 * does prevent any leaks from {@link AbstractPhantomArrayList} that weren't closed.
 * 
 * <br><br>
 * <strong>Use Notes: </strong><br>
 * If possible all checkouts for a given pool should be the same size,
 * since {@link PhantomArrayListCheckout}'s are shared, getting the same size checkout each time
 * prevents accidentally returning a larger checkout than necessary, which wastes memory.
 */
public class PhantomArrayListPool
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** 
	 * the recycler thread needs to be triggered relatively frequently to prevent
	 * build up of GC'ed arrays.
	 * However, some JVM's will wait a while before collecting lost objects,
	 * so in general it is still much better to use the try-finally.
	 */
	private static final int PHANTOM_REF_CHECK_TIME_IN_MS = 5_000;
	private static final ThreadPoolExecutor RECYCLER_THREAD = ThreadUtil.makeSingleDaemonThreadPool("Phantom Array Recycler");
	private static final ArrayList<PhantomArrayListPool> POOL_LIST = new ArrayList<>();
	
	/** if enabled the number of GC'ed arrays will be logged */
	private static final boolean LOG_ARRAY_RECOVERY = ModInfo.IS_DEV_BUILD;
	
	
	private static boolean lowMemoryWarningLogged = false; 
	
	
	
	/** used for debugging and tracking what the pool contains */
	public final String name;
	/** 
	 * Getting stack traces is very slow.
	 * If we know which pool is leaking objects we can enable tracking for that specific
	 * pool and prevent slow-downs in other pools.
	 */
	public final boolean logGarbageCollectedStacks;
	
	public final ConcurrentHashMap<Reference<? extends AbstractPhantomArrayList>, PhantomArrayListCheckout>
			phantomRefToCheckout = new ConcurrentHashMap<>();
	public final ReferenceQueue<AbstractPhantomArrayList> phantomRefQueue = new ReferenceQueue<>();
	
	
	private final ConcurrentLinkedQueue<SoftReference<PhantomArrayListCheckout>> pooledCheckoutsRefs = new ConcurrentLinkedQueue<>();
	
	// stat tracking //
	//region
	
	/** 
	 * If a {@link PhantomArrayListCheckout} was garbage collected that means some backing objects will be missing
	 * and we'll need to update our counts.
	 */
	private boolean clearLastPoolSizes = false;
	
	public final PhantomArrayListPoolStatTracker<ByteArrayList> bytePoolStatTracker = new PhantomArrayListPoolStatTracker<>("byte[]",    Byte.BYTES,      () -> new ByteArrayList(0));
	public final PhantomArrayListPoolStatTracker<ShortArrayList> shortPoolStatTracker = new PhantomArrayListPoolStatTracker<>("short[]", Short.BYTES,     () -> new ShortArrayList(0));
	public final PhantomArrayListPoolStatTracker<LongArrayList> longPoolStatTracker = new PhantomArrayListPoolStatTracker<>("long[]",    Long.BYTES,      () -> new LongArrayList(0));
	public final PhantomArrayListPoolStatTracker<CharArrayList> charPoolStatTracker = new PhantomArrayListPoolStatTracker<>("char[]",    Character.BYTES, () -> new CharArrayList(0));
	public final PhantomArrayListPoolStatTracker<ByteBufferCheckoutWrapper> byteBufferPoolStatTracker = new PhantomArrayListPoolStatTracker<>("ByteBuffer",    Byte.BYTES, () -> new ByteBufferCheckoutWrapper());
	
	public final PhantomArrayListPoolStatTracker<?>[] poolStatTrackers = new PhantomArrayListPoolStatTracker[]
	{
		this.bytePoolStatTracker,
		this.shortPoolStatTracker,
		this.longPoolStatTracker,
		this.charPoolStatTracker,
		this.byteBufferPoolStatTracker,
	};
	public static final String[] POOL_STAT_TYPE_NAMES = new String[]
	{
		"byte[]",
		"short[]",
		"long[]",
		"char[]",
		"ByteBuffer",
	};
	/** how many different types our pool supports */
	public static final int TYPE_COUNT = POOL_STAT_TYPE_NAMES.length;
	
	//endregion
	
	
	
	//==============//
	// constructors //
	//==============//
	
	// shared setup used by all pools
	static { RECYCLER_THREAD.execute(() -> runPhantomReferenceCleanupLoop()); }
	
	
	public PhantomArrayListPool(String name) { this(name, false); }
	public PhantomArrayListPool(String name, boolean logGarbageCollectedStacks)
	{
		POOL_LIST.add(this);
		this.name = name;
		this.logGarbageCollectedStacks = logGarbageCollectedStacks;
	}
	
	
	
	//==============//
	// get checkout //
	//==============//
	
	public PhantomArrayListCheckout checkoutByteArrays(int count) { return this.checkoutArrays(count, 0, 0, 0, 0); }
	public PhantomArrayListCheckout checkoutShortArrays(int count) { return this.checkoutArrays(0, count, 0, 0, 0); }
	public PhantomArrayListCheckout checkoutLongArrays(int count) { return this.checkoutArrays(0, 0, count, 0, 0); }
	public PhantomArrayListCheckout checkoutCharArrays(int count) { return this.checkoutArrays(0, 0, 0, count, 0); }
	public PhantomArrayListCheckout checkoutByteBuffers(int count) { return this.checkoutArrays(0, 0, 0, 0, count); }
	
	/** 
	 * If possible all checkouts for a given pool should be the same size,
	 * since {@link PhantomArrayListCheckout}'s are shared, returning the same size
	 * prevents accidentally returning a larger checkout than necessary, which wastes memory.
	 */
	public PhantomArrayListCheckout checkoutArrays(
		int byteArrayCount, int shortArrayCount, int longArrayCount, 
		int charArrayCount, int byteBufferCount)
	{
		PhantomArrayListCheckout checkout = null;
		while (checkout == null)
		{
			SoftReference<PhantomArrayListCheckout> checkoutRef = this.pooledCheckoutsRefs.poll();
			if (checkoutRef == null)
			{
				// pool is empty, create new checkout
				checkout = new PhantomArrayListCheckout(this);
				checkout.onCheckout();
			}
			else
			{
				checkout = checkoutRef.get();
				if (checkout != null)
				{
					// use pooled checkout
					checkout.onCheckout();
				}
				else
				{
					// this reference is pointing to null,
					// the checkout was garbage collected
					if (!lowMemoryWarningLogged)
					{
						// Complain if there isn't much free ram.
						// Some garbage collectors may remove our soft references unnecessarily
						// even when there is free space, and this should prevent the warning from
						// popping up unnecessarily.
						
						long freeMemoryBytes = Runtime.getRuntime().freeMemory();
						
						long expectedFreeMemoryBytes = 1_073_741_824 /* 1 Gibibyte */ / 8; // 128 MibiBytes
						if (freeMemoryBytes < expectedFreeMemoryBytes)
						{
							lowMemoryWarningLogged = true;
							
							String message = MinecraftTextFormat.ORANGE + "Distant Horizons: Insufficient memory detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								"This may cause stuttering or crashing. \n" +
								"Potential causes: \n" +
								"1. your allocated memory isn't high enough \n" +
								"2. your DH CPU preset is too high \n" +
								"3. your DH quality preset is too high \n" +
								"4. you have other memory hungry mod(s)";
							
							LOGGER.warn(message);
							if (Config.Common.Logging.Warning.showPoolInsufficientMemoryWarning.get())
							{
								ClientApi.INSTANCE.queueSlowChatMessage(message);
							}
						}
					}
					
					this.clearLastPoolSizes = true;
				}
			}
		}
		
		
		// get any missing arrays
		this.bytePoolStatTracker.fillCheckout(byteArrayCount, checkout::getByteArrayCount, checkout::addByteArrayList);
		this.shortPoolStatTracker.fillCheckout(shortArrayCount, checkout::getShortArrayCount, checkout::addShortArrayList);
		this.longPoolStatTracker.fillCheckout(longArrayCount, checkout::getLongArrayCount, checkout::addLongArrayList);
		this.charPoolStatTracker.fillCheckout(charArrayCount, checkout::getCharArrayCount, checkout::addCharArrayList);
		this.byteBufferPoolStatTracker.fillCheckout(byteBufferCount, checkout::getByteBufferWrapperCount, checkout::addByteBufferWrapper);
		
		return checkout;
	}
	
	
	
	//==================//
	// phantom recovery //
	//==================//
	//region
	
	private static void runPhantomReferenceCleanupLoop()
	{
		while (true)
		{
			// these arrays are stored here so they don't have to be re-allocated each loop
			ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList = new ArrayList<>();
			ArrayList<Pair<String, AtomicInteger>> lastSeenStackTraceCountPairList = new ArrayList<>();
			
			try
			{
				try
				{
					Thread.sleep(PHANTOM_REF_CHECK_TIME_IN_MS);
				}
				catch (InterruptedException ignore) { }
				
				
				for (int poolIndex = 0; poolIndex < POOL_LIST.size(); poolIndex++)
				{
					PhantomArrayListPool pool = POOL_LIST.get(poolIndex);
					
					int returnedByteArrayCount = 0;
					int returnedShortArrayCount = 0;
					int returnedLongArrayCount = 0;
					int returnedCharArrayCount = 0;
					int checkoutCount = 0;
					
					allocationStackTraceCountPairList.clear();
					lastSeenStackTraceCountPairList.clear();
					
					Reference<? extends AbstractPhantomArrayList> phantomRef = pool.phantomRefQueue.poll();
					while (phantomRef != null)
					{
						// return the pooled arrays
						PhantomArrayListCheckout checkout = pool.phantomRefToCheckout.remove(phantomRef);
						if (checkout != null)
						{
							returnedByteArrayCount += checkout.getByteArrayCount();
							returnedShortArrayCount += checkout.getShortArrayCount();
							returnedLongArrayCount += checkout.getLongArrayCount();
							returnedCharArrayCount += checkout.getCharArrayCount();
							checkoutCount++;
							pool.returnCheckout(checkout);
							
							if (pool.logGarbageCollectedStacks
								&& checkout.allocationStackTrace != null) // stack trace shouldn't be null, but just in case
							{
								PhantomLoggingHelper.putAndIncrementTrackingString(checkout.allocationStackTrace, allocationStackTraceCountPairList);
								PhantomLoggingHelper.putAndIncrementTrackingString(checkout.lastSeenStackTrace, lastSeenStackTraceCountPairList);
							}
						}
						else
						{
							// shouldn't happen, but just in case
							LOGGER.warn("Pool: ["+pool.name+"]. Unable to find checkout for phantom reference ["+phantomRef+"], arrays will need to be recreated.");
						}
						
						phantomRef = pool.phantomRefQueue.poll();
					}
					
					if (LOG_ARRAY_RECOVERY || pool.logGarbageCollectedStacks)
					{
						// we only want to log when something has been returned
						if (checkoutCount != 0
							|| returnedByteArrayCount != 0
							|| returnedShortArrayCount != 0
							|| returnedLongArrayCount != 0
							|| returnedCharArrayCount != 0)
						{
							LOGGER.warn("Pool: ["+ pool.name+"] phantom recovery. " +
								"Returned checkouts:["+F3Screen.NUMBER_FORMAT.format(checkoutCount)+"], " +
								"byte:["+F3Screen.NUMBER_FORMAT.format(returnedByteArrayCount)+"], " +
								"short:["+F3Screen.NUMBER_FORMAT.format(returnedShortArrayCount)+"], " +
								"long:["+F3Screen.NUMBER_FORMAT.format(returnedLongArrayCount)+"]," +
								"char:["+F3Screen.NUMBER_FORMAT.format(returnedCharArrayCount)+"]."
								);
							
							// log stack traces if present
							if (pool.logGarbageCollectedStacks)
							{
								LOGGER.info("\n==== GC stack start ====\n");
								PhantomLoggingHelper.LogAllocationStackTracePairCounts(LOGGER, "Allocation", allocationStackTraceCountPairList);
								LOGGER.info("\n========\n");
								PhantomLoggingHelper.LogAllocationStackTracePairCounts(LOGGER, "Last seen", lastSeenStackTraceCountPairList);
								LOGGER.info("\n==== GC stack end ====\n");
							}
						}
					}
					
					// since this is just for debugging it only needs to be recalculated once in a while
					pool.recalculateSizeForDebugging();
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in phantom pool return thread, error: [" + e.getMessage() + "].", e);
			}
		}
	}
	
	//endregion
	
	
	
	//=================//
	// return checkout //
	//=================//
	//region
	
	public void returnParentPhantomRef(@NotNull PhantomReference<AbstractPhantomArrayList> parentRef)
	{
		try
		{
			parentRef.clear();
			// will be null if the this parent has already been returned
			PhantomArrayListCheckout checkout = this.phantomRefToCheckout.remove(parentRef);
			this.returnCheckout(checkout);
		}
		catch (Exception e)
		{
			LOGGER.error("Unable to close Phantom Array, error: ["+e.getMessage()+"].", e);
		}
	}
	public void returnCheckout(@Nullable PhantomArrayListCheckout checkout)
	{ 
		if (checkout == null)
		{
			throw new IllegalArgumentException("Null phantom checkout, object is being closed multiple times.");
		}
		
		SoftReference<PhantomArrayListCheckout> checkoutRef = checkout.ownerSoftReference;
		this.pooledCheckoutsRefs.add(checkoutRef);
		
		//LOGGER.info("Returned ["+checkout.byteArrayLists.size()+"/"+this.pooledByteArrays.size()+"] bytes and ["+checkout.longArrayLists.size()+"/"+this.pooledLongArrays.size()+"] longs.");
	}
	
	//endregion
	
	
	
	//===============//
	// debug methods //
	//===============//
	//region
	
	public static void addDebugMenuStringsToListForCombinedPools(List<String> messageList)
	{
		// Aggregate totals across all pools
		int[] totalCounts = new int[TYPE_COUNT];
		int[] pooledCounts = new int[TYPE_COUNT];
		long[] poolByteSize = new long[TYPE_COUNT];
		
		for (int poolIndex = 0; poolIndex < POOL_LIST.size(); poolIndex++)
		{
			PhantomArrayListPool pool = POOL_LIST.get(poolIndex);
			PhantomArrayListPoolStatTracker<?>[] statTrackers = pool.poolStatTrackers;
			for (int typeIndex = 0; typeIndex < statTrackers.length; typeIndex++)
			{
				PhantomArrayListPoolStatTracker<?> statTracker = statTrackers[typeIndex];
				
				totalCounts[typeIndex] += statTracker.totalArrayCountRef.get();
				pooledCounts[typeIndex] += statTracker.lastPoolCount;
				poolByteSize[typeIndex] += statTracker.lastPoolSizeInBytes;
			}
		}
		
		addDebugMenuStringsToList(messageList,
			"Combined",
			totalCounts, pooledCounts, poolByteSize);
	}
	
	public static void addDebugMenuStringsToListForSeparatePools(List<String> messageList)
	{
		for (int i = 0; i < POOL_LIST.size(); i++)
		{
			PhantomArrayListPool pool = POOL_LIST.get(i);
			pool.addDebugMenuStringsToList(messageList);
		}
	}
	private void addDebugMenuStringsToList(List<String> messageList)
	{
		PhantomArrayListPoolStatTracker<?>[] statTrackers = this.poolStatTrackers;
		int[] totalCounts = new int[statTrackers.length];
		int[] pooledCounts = new int[statTrackers.length];
		long[] poolSizesBytes = new long[statTrackers.length];
		
		for (int i = 0; i < statTrackers.length; i++)
		{
			PhantomArrayListPoolStatTracker<?> statTracker = statTrackers[i];
			
			totalCounts[i] = statTracker.totalArrayCountRef.get();
			pooledCounts[i] = statTracker.lastPoolCount;
			poolSizesBytes[i] = statTracker.lastPoolSizeInBytes;
		}
		
		addDebugMenuStringsToList(
			messageList, 
			this.name, 
			totalCounts, pooledCounts, poolSizesBytes);
	}
	
	
	private static void addDebugMenuStringsToList(
		List<String> messageList,
		String name,
		int[] totalCounts,
		int[] pooledCounts,
		long[] poolSizesBytes)
	{
		String a  = MinecraftTextFormat.AQUA;
		String y  = MinecraftTextFormat.YELLOW;
		String o  = MinecraftTextFormat.ORANGE;
		String cf = MinecraftTextFormat.CLEAR_FORMATTING;
		
		
		// total pool
		long totalByteSize = 0L;
		for (int i = 0; i < POOL_STAT_TYPE_NAMES.length; i++)
		{
			totalByteSize += poolSizesBytes[i];
		}
		String totalByteSizeStr = StringUtil.convertBytesToHumanReadable(totalByteSize);
		
		
		// header
		messageList.add(a + name + cf + " - Pools");
		
		// individual pools
		int nonEmptyPools = 0;
		for (int i = 0; i < POOL_STAT_TYPE_NAMES.length; i++)
		{
			if (totalCounts[i] == 0)
			{
				continue;
			}
			
			String pooledFormatted = F3Screen.NUMBER_FORMAT.format(pooledCounts[i]);
			String totalFormatted  = F3Screen.NUMBER_FORMAT.format(totalCounts[i]);
			nonEmptyPools++;
			
			String sizeStr = "";
			if (poolSizesBytes[i] != -1)
			{
				sizeStr = StringUtil.convertBytesToHumanReadable(poolSizesBytes[i]);
			}
			
			messageList.add(POOL_STAT_TYPE_NAMES[i] + ": " + pooledFormatted + "/" + totalFormatted + " " + y + sizeStr + cf);
		}
		
		// only include a total if there's more than 1 item to sum up
		if (nonEmptyPools > 1)
		{
			messageList.add("Total: " + o + totalByteSizeStr + cf);
		}
	}
	
	
	/**
	 * Shouldn't be called on the render thread as it can
	 * take 10's of milliseconds to complete.
	 */
	public void recalculateSizeForDebugging()
	{
		for (SoftReference<PhantomArrayListCheckout> pooledCheckoutRef : this.pooledCheckoutsRefs)
		{
			PhantomArrayListCheckout pooledCheckout = pooledCheckoutRef.get();
			if (pooledCheckout == null)
			{
				continue;
			}
			
			bytePoolStatTracker.debugAddPoolByteSize(pooledCheckout.getAllByteArrays());
			shortPoolStatTracker.debugAddPoolByteSize(pooledCheckout.getAllShortArrays());
			longPoolStatTracker.debugAddPoolByteSize(pooledCheckout.getAllLongArrays());
			charPoolStatTracker.debugAddPoolByteSize(pooledCheckout.getAllCharArrays());
			byteBufferPoolStatTracker.debugAddPoolByteSize(pooledCheckout.getAllByteBufferWrappers());
		}
		
		
		// clear the old count if something was garbage collected
		// so the size can shrink
		boolean clearLastPoolSize = this.clearLastPoolSizes;
		if (clearLastPoolSize)
		{
			this.clearLastPoolSizes = false;
		}
		
		bytePoolStatTracker.updateDebugValues(clearLastPoolSize);
		shortPoolStatTracker.updateDebugValues(clearLastPoolSize);
		longPoolStatTracker.updateDebugValues(clearLastPoolSize);
		charPoolStatTracker.updateDebugValues(clearLastPoolSize);
		byteBufferPoolStatTracker.updateDebugValues(clearLastPoolSize);
	}
	
	//endregion
	
	
	
}
