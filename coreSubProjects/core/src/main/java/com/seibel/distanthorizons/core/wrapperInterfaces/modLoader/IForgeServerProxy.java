package com.seibel.distanthorizons.core.wrapperInterfaces.modLoader;

import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface IForgeServerProxy extends IBindable
{
	/** Schedule a task that runs on the main thread and returns a CompletableFuture result */
	<T> CompletableFuture<T> scheduleTickTask(boolean limited, Supplier<T> task);
	
	
	
}
