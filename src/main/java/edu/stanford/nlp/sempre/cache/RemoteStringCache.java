package edu.stanford.nlp.sempre.cache;

import fig.basic.LogInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Cache backed by a remote service (see StringCacheServer).
 *
 * @author Percy Liang
 */
public class RemoteStringCache implements StringCache
{
	public static final int NUM_TRIES = 5;

	private Socket socket;
	private PrintWriter out;
	private BufferedReader in;

	// Cache things locally.
	private final FileStringCache local = new FileStringCache();

	public RemoteStringCache(final String path, final String host, final int port)
	{
		try
		{
			LogInfo.begin_track("RemoteStringCache: connecting to %s:%s to access %s", host, port, path);
			socket = new Socket(host, port);
			out = new PrintWriter(socket.getOutputStream(), true);
			in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			final String response = makeRequest("open", path, null);
			LogInfo.logs("Using cache path=%s, host=%s, port=%s", path, host, port);
			if (!response.equals("OK"))
				throw new RuntimeException(response);
			LogInfo.end_track();
		}
		catch (final UnknownHostException e)
		{
			LogInfo.end_track();
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			LogInfo.end_track();
			throw new RuntimeException(e);
		}
	}

	public String makeRequest(final String method, final String key, final String value)
	{
		try
		{
			if (value == null)
				out.println(method + "\t" + key);
			else
				out.println(method + "\t" + key + "\t" + value);
			out.flush();
			for (int i = 0; i < NUM_TRIES; i++)
				try
				{
					String result = in.readLine();
					if (result.equals(StringCacheServer.nullString))
						result = null;
					return result;
				}
				catch (final NullPointerException e)
				{
					LogInfo.logs("RemoteStringCache.makeRequest(%s, %s, %s) failed", method, key, value);
				}
			throw new NullPointerException();
		}
		catch (final SocketTimeoutException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String get(final String key)
	{
		// First check the local cache.
		String value = local.get(key);
		if (value == null)
			value = makeRequest("get", key, null);
		return value;
	}

	public void put(final String key, final String value)
	{
		local.put(key, value);
		makeRequest("put", key, value);
	}

	public int size()
	{
		return local.size();
	}
}
