package edu.stanford.nlp.sempre.freebase;

import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.exec.Execution;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Motivation: the Freebase dump uses a mix of mids and ids. The mapping computed by this program will be used to standardize everything to a nice looking id or
 * a mid.
 * <p/>
 * Input: raw RDF Freebase dump (ttl format) downloaded from the Freebase website.
 * <p/>
 * Output: tab-separated file containing mapping from mid/id to canonical id. fb:m.02mjmr fb:en.barack_obama fb:m.07t65 fb:en.united_nations fb:en.united_staff
 * fb:en.united_nations ... To save space, only output mid/id which are mids or acceptable ids.
 * <p/>
 * Acceptable ids include: - Something that starts with fb:en. - Something that occurs in the arg1 position (for types and properties, excluding fb:m.* and
 * fb:g.*). If there are multiple ids, just take the first one in the file. If there are no acceptable ids, then just take the mid.
 *
 * @author Percy Liang
 */
public class BuildCanonicalIdMap implements Runnable
{
	@Option
	public int maxInputLines = Integer.MAX_VALUE;
	@Option(required = true)
	public String rawPath;
	@Option(required = true)
	public String canonicalIdMapPath;

	Set<String> allowableIds = new HashSet<>();

	PrintWriter out;
	int numMids = 0;

	void computeAllowableIds()
	{
		// Compute allowable ids
		LogInfo.begin_track("Compute allowable ids");
		try
		{
			final BufferedReader in = IOUtils.openIn(rawPath);
			String line;
			int numInputLines = 0;
			while (numInputLines < maxInputLines && (line = in.readLine()) != null)
			{
				numInputLines++;
				if (numInputLines % 10000000 == 0)
					LogInfo.logs("Read %s lines, %d allowable ids", numInputLines, allowableIds.size());
				final String[] tokens = Utils.parseTriple(line);
				if (tokens == null)
					continue;
				final String arg1 = tokens[0];
				if (!arg1.startsWith("fb:g.") && !arg1.startsWith("fb:m."))
					allowableIds.add(arg1);
			}
			LogInfo.logs("%d allowable ids", allowableIds.size());
			in.close();
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
		LogInfo.end_track();
	}

	void flush(final String mid, final List<String> ids)
	{
		if (ids.size() == 0)
			return;
		numMids++;

		// Find the best id for this entity and put it first to use as the canonical id.
		String bestId = mid;
		for (final String id : ids)
			if (id.startsWith("fb:en.") || allowableIds.contains(id))
			{
				bestId = id;
				break;
			}

		if (!bestId.equals(mid))
			out.println(mid + "\t" + bestId);
		for (final String id : ids)
		{
			if (id.equals(bestId))
				continue;
			if (!(allowableIds.contains(id) || id.startsWith("fb:en.")))
				continue; // To save space, only map ids which look reasonable
			out.println(id + "\t" + bestId);
		}
	}

	public void run()
	{
		computeAllowableIds();

		// Map to ids
		out = IOUtils.openOutHard(canonicalIdMapPath);
		try
		{
			final BufferedReader in = IOUtils.openIn(rawPath);
			String line;
			int numInputLines = 0;

			// Current block of triples corresponds to a single mid.
			String mid = null;
			final List<String> ids = new ArrayList<>();

			while (numInputLines < maxInputLines && (line = in.readLine()) != null)
			{
				numInputLines++;
				if (numInputLines % 10000000 == 0)
					LogInfo.logs("Read %s lines, %d entities", numInputLines, numMids);
				final String[] tokens = Utils.parseTriple(line);
				if (tokens == null)
					continue;
				final String arg1 = tokens[0];
				final String property = tokens[1];
				String arg2 = tokens[2];

				if (!arg1.startsWith("fb:m."))
					continue;
				if (!property.equals("fb:type.object.key"))
					continue;

				// Flush last block
				if (!arg1.equals(mid))
				{
					flush(mid, ids);

					// Reset
					mid = arg1;
					ids.clear();
				}

				// Record information
				arg2 = Utils.stringToRdf(arg2);
				ids.add(arg2);
			}
			in.close();
			flush(mid, ids);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}

		out.close();
	}

	public static void main(final String[] args)
	{
		Execution.run(args, new BuildCanonicalIdMap());
	}
}
