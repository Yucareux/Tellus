import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class NativeRelocator
{
	private final Path rootDirectory;
	private final Path cacheRoot;
	
	/**
	 * Initializes the NativeRelocator by preparing the environment if necessary.
	 * Executes the appropriate preparation script based on the OS.
	 */
	NativeRelocator(Path rootDirectory)
	{
		this.rootDirectory = rootDirectory;
		this.cacheRoot = this.rootDirectory.resolve("cache");
	}
	
	private void prepare() throws Exception
	{
		// a venv left behind by a failed prepare run is unusable, so probe it rather than trusting the directory
		if (this.venvPythonPath().toFile().exists() && this.venvHasDependencies())
		{
			return;
		}
		
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.directory(this.rootDirectory.toFile());
		
		String os = System.getProperty("os.name").toLowerCase();
		if (os.contains("win"))
		{
			processBuilder.command("powershell", "-ExecutionPolicy", "Bypass", "./prepare.ps1");
		}
		else if (os.contains("nix") 
				|| os.contains("nux") 
				|| os.contains("mac")
				|| os.contains("freebsd"))
		{
			processBuilder.command("./prepare.sh");
		}
		else
		{
			throw new IllegalStateException("Unsupported operating system: " + os);
		}
		
		Process process = processBuilder.start();
		CompletableFuture<Void> outputFuture = readOutputStreams(process);
		
		int exitCode = process.waitFor();
		outputFuture.get();
		
		if (exitCode != 0)
		{
			throw new Exception("Prepare failed: " + exitCode
					+ " (see the pip output above; the native relocation venv could not be created)");
		}
	}
	
	private Path venvPythonPath()
	{
		Path windowsPython = this.rootDirectory.resolve(".venv/Scripts/python.exe");
		return windowsPython.toFile().exists() ? windowsPython : this.rootDirectory.resolve(".venv/bin/python");
	}
	
	private boolean venvHasDependencies()
	{
		try
		{
			ProcessBuilder processBuilder = new ProcessBuilder(this.venvPythonPath().toString(), "-c", "import lief");
			processBuilder.directory(this.rootDirectory.toFile());
			processBuilder.redirectErrorStream(true);
			
			Process process = processBuilder.start();
			process.getInputStream().readAllBytes();
			return process.waitFor() == 0;
		}
		catch (Exception e)
		{
			return false;
		}
	}
	
	/**
	 * Reads and prints the output and error streams of a process asynchronously.
	 *
	 * @param process The process whose streams should be read.
	 * @return A CompletableFuture that completes once all output has been processed.
	 */
	private static CompletableFuture<Void> readOutputStreams(Process process)
	{
		return CompletableFuture.runAsync(() -> {
			try
			{
				while (process.isAlive() || process.getInputStream().available() > 0 || process.getErrorStream().available() > 0)
				{
					if (process.getInputStream().available() > 0)
					{
						byte[] data = new byte[process.getInputStream().available()];
						//noinspection ResultOfMethodCallIgnored
						process.getInputStream().read(data);
						System.out.write(data);
					}
					if (process.getErrorStream().available() > 0)
					{
						byte[] data = new byte[process.getErrorStream().available()];
						//noinspection ResultOfMethodCallIgnored
						process.getErrorStream().read(data);
						System.err.write(data);
					}
					
					//noinspection BusyWait
					Thread.sleep(100);
				}
			}
			catch (Throwable ignored)
			{
			}
		});
	}
	
	/**
	 * Replaces occurrences of a target string in a byte array, ensuring null termination.
	 *
	 * @param byteArray The byte array where replacements should occur.
	 * @param target The string to replace.
	 * @param replacement The replacement string (must not be longer than the target).
	 * @throws IllegalArgumentException if the replacement is longer than the target.
	 */
	private void replaceInNullTerminatedStrings(byte[] byteArray, String target, String replacement)
	{
		if (target.length() < replacement.length())
		{
			throw new IllegalArgumentException("Replacement must be the same length or shorter than the target.");
		}
		
		byte[] targetBytes = target.getBytes(StandardCharsets.US_ASCII);
		byte[] replacementBytes = replacement.getBytes(StandardCharsets.US_ASCII);
		
		byte nullByte = 0;
		
		for (int endPos = 0; endPos < byteArray.length - targetBytes.length - 1; endPos++)
		{
			int startPos = endPos;
			int targetPos = 0;
			while (targetPos < targetBytes.length && byteArray[endPos] == targetBytes[targetPos])
			{
				targetPos++;
				endPos++;
			}
			
			if (targetPos == targetBytes.length)
			{
				System.arraycopy(replacementBytes, 0, byteArray, startPos, replacementBytes.length);
				
				startPos = startPos + replacementBytes.length;
				while (byteArray[endPos] != nullByte)
				{
					byteArray[startPos] = byteArray[endPos];
					endPos++;
					startPos++;
				}
				byteArray[startPos] = nullByte;
			}
		}
	}
	
	/**
	 * Runs an external script to fix a modified binary and returns the processed content.
	 *
	 * @param outputFilePath Path to store the processed binary.
	 * @param content The original binary content.
	 * @return The modified binary content.
	 * @throws Exception if the process execution fails.
	 */
	public byte[] fixModifiedBinary(Path outputFilePath, byte[] content) throws Exception
	{
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.directory(this.rootDirectory.toFile());
		
		processBuilder.command(
				this.venvPythonPath().toString(),
				"./fix_modified_binary.py",
				outputFilePath.toString()
		);
		
		Process process = processBuilder.start();
		CompletableFuture<Void> outputFuture = readOutputStreams(process);
		
		process.getOutputStream().write(content);
		process.getOutputStream().close();
		
		int exitCode = process.waitFor();
		outputFuture.get();
		
		if (exitCode != 0)
		{
			throw new Exception("Process failed: " + exitCode);
		}
		
		return Files.readAllBytes(outputFilePath);
	}
	
	/**
	 * Processes a binary file, applying string replacements and fixing modifications.
	 *
	 * @param outputPath The output file path relative to the cache directory.
	 * @param content The binary content to process.
	 * @param replacements A map of string replacements to apply.
	 * @return The modified binary content.
	 * @throws Exception if processing fails.
	 */
	public byte[] processBinary(String outputPath, byte[] content, Map<String, String> replacements) throws Exception
	{
		Path outputFilePath = this.cacheRoot.resolve(outputPath);
		//noinspection ResultOfMethodCallIgnored
		outputFilePath.getParent().toFile().mkdirs();
		
		if (outputFilePath.toFile().exists())
		{
			return Files.readAllBytes(outputFilePath);
		}
		
		System.out.println("Relocating to " + outputPath + "...");
		this.prepare();
		
		for (Map.Entry<String, String> replacement : replacements.entrySet())
		{
			this.replaceInNullTerminatedStrings(content, replacement.getKey(), replacement.getValue());
		}
		
		return this.fixModifiedBinary(outputFilePath, content);
	}
	
}
