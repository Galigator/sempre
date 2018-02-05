package edu.stanford.nlp.sempre.interactive;

import edu.stanford.nlp.sempre.Json;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.OptionsParser;
import fig.exec.Execution;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * utilites for simulating a session through the server
 * 
 * @author sidaw
 */

class GZIPFiles
{
	/**
	 * Get a lazily loaded stream of lines from a gzipped file, similar to {@link Files#lines(java.nio.file.Path)}.
	 *
	 * @param path The path to the gzipped file.
	 * @return stream with lines.
	 */
	public static Stream<String> lines(final Path path)
	{
		InputStream fileIs = null;
		BufferedInputStream bufferedIs = null;
		GZIPInputStream gzipIs = null;
		try
		{
			fileIs = Files.newInputStream(path);
			// Even though GZIPInputStream has a buffer it reads individual bytes
			// when processing the header, better add a buffer in-between
			bufferedIs = new BufferedInputStream(fileIs, 65535);
			gzipIs = new GZIPInputStream(bufferedIs);
		}
		catch (final IOException e)
		{
			closeSafely(gzipIs);
			closeSafely(bufferedIs);
			closeSafely(fileIs);
			throw new UncheckedIOException(e);
		}
		final BufferedReader reader = new BufferedReader(new InputStreamReader(gzipIs));
		return reader.lines().onClose(() -> closeSafely(reader));
	}

	private static void closeSafely(final Closeable closeable)
	{
		if (closeable != null)
			try
			{
				closeable.close();
			}
			catch (final IOException e)
			{
				// Ignore
			}
	}
}

public class Simulator implements Runnable
{

	@Option
	public static String serverURL = "http://localhost:8410";
	@Option
	public static int numThreads = 1;
	@Option
	public static int verbose = 1;
	@Option
	public static boolean useThreads = false;
	@Option
	public static long maxQueries = Long.MAX_VALUE;
	@Option
	public static String reqParams = "grammar=0&cite=0&learn=0";
	@Option
	public static List<String> logFiles = null;

	public void readQueries()
	{
		// T.printAllRules();
		// A.assertAll();
		for (final String fileName : logFiles)
		{
			final long startTime = System.nanoTime();
			Stream<String> stream;
			try
			{
				if (fileName.endsWith(".gz"))
					stream = GZIPFiles.lines(Paths.get(fileName));
				else
					stream = Files.lines(Paths.get(fileName));

				final List<String> lines = stream.collect(Collectors.toList());
				LogInfo.logs("Reading %s (%d lines)", fileName, lines.size());
				int numLinesRead = 0;
				// ExecutorService executor = new ThreadPoolExecutor(numThreads,
				// numThreads,
				// 15000, TimeUnit.MILLISECONDS,
				// new LinkedBlockingQueue<Runnable>());
				final ExecutorService executor = Executors.newSingleThreadExecutor();

				for (final String l : lines)
				{
					numLinesRead++;
					if (numLinesRead > maxQueries)
						break;
					LogInfo.logs("Line %d", numLinesRead);
					if (!useThreads)
						executeLine(l);
					else
					{
						final Future<?> future = executor.submit(() -> executeLine(l));
						try
						{
							future.get(10, TimeUnit.MINUTES);
						}
						catch (final Throwable t)
						{
							t.printStackTrace();
						}
						finally
						{
							future.cancel(true); // may or may not desire this
							final long endTime = System.nanoTime();
							LogInfo.logs("Took %d ns or %.4f s", endTime - startTime, (endTime - startTime) / 1.0e9);
						}
					}
				}
			}
			catch (final IOException e)
			{
				e.printStackTrace();
			}

		}
		SimulationAnalyzer.flush();
	}

	static void executeLine(final String l)
	{
		Map<String, Object> json = null;
		try
		{
			json = Json.readMapHard(l);
		}
		catch (final RuntimeException e)
		{
			LogInfo.logs("Json cannot be read from %s: %s", l, e.toString());
			return;
		}
		Object command = json.get("q");
		if (command == null) // to be backwards compatible
			command = json.get("log");
		Object sessionId = json.get("sessionId");
		if (sessionId == null) // to be backwards compatible
			sessionId = json.get("id");

		try
		{
			final String response = sempreQuery(command.toString(), sessionId.toString());
			SimulationAnalyzer.addStats(json, response);
		}
		catch (final Throwable t)
		{
			t.printStackTrace();
		}
	}

	public static String sempreQuery(final String query, final String sessionId) throws UnsupportedEncodingException
	{
		String params = "q=" + URLEncoder.encode(query, "UTF-8");
		params += String.format("&sessionId=%s&%s", sessionId, reqParams);
		// params = URLEncoder.encode(params);
		final String url = String.format("%s/sempre?", serverURL);
		// LogInfo.log(params);
		// LogInfo.log(query);
		final String response = executePost(url + params, "");
		// LogInfo.log(response);
		return response;
	}

	public static String executePost(final String targetURL, final String urlParameters)
	{
		HttpURLConnection connection = null;

		try
		{
			// Create connection
			final URL url = new URL(targetURL);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

			connection.setRequestProperty("Content-Length", Integer.toString(urlParameters.getBytes().length));
			connection.setRequestProperty("Content-Language", "en-US");

			connection.setUseCaches(false);
			connection.setDoOutput(true);

			// Send request
			final DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
			wr.writeBytes(urlParameters);
			wr.close();

			// Get Response
			final InputStream is = connection.getInputStream();
			final BufferedReader rd = new BufferedReader(new InputStreamReader(is));
			final StringBuilder response = new StringBuilder(); // or StringBuffer if Java
			// version 5+
			String line;
			while ((line = rd.readLine()) != null)
			{
				response.append(line);
				response.append('\r');
			}
			rd.close();
			return response.toString();
		}
		catch (final Exception e)
		{
			e.printStackTrace();
			return null;
		}
		finally
		{
			if (connection != null)
				connection.disconnect();
		}
	}

	public static void main(final String[] args)
	{
		final OptionsParser parser = new OptionsParser();
		final Simulator simulator = new Simulator();
		// parser.register("", opts);
		Execution.run(args, "Simulator", simulator, parser);
	}

	@Override
	public void run()
	{
		LogInfo.logs("setting numThreads %d", numThreads);
		readQueries();
	}
}
