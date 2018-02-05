package edu.stanford.nlp.sempre.tables.alter;

import fig.basic.IOUtils;
import fig.basic.Option;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CachedSubsetChooser implements SubsetChooser
{
	public static class Options
	{
		@Option(gloss = "read the list of retained table from these files")
		public List<String> retainedTablesFilenames = new ArrayList<>();
	}

	public static Options opts = new Options();

	Map<String, Subset> cache = new HashMap<>();

	public CachedSubsetChooser()
	{
		for (final String filename : opts.retainedTablesFilenames)
			load(filename);
	}

	private void load(final String retainedTablesFilename)
	{
		try
		{
			final BufferedReader reader = IOUtils.openInHard(retainedTablesFilename);
			String line;
			while ((line = reader.readLine()) != null)
			{
				final Subset subset = Subset.fromString(line);
				if (subset.score > Double.NEGATIVE_INFINITY)
					cache.put(subset.id, subset);
			}
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public Subset chooseSubset(final String id, final DenotationData denotationData)
	{
		return cache.get(id);
	}

	@Override
	public Subset chooseSubset(final String id, final DenotationData denotationData, final Collection<Integer> forbiddenTables)
	{
		throw new RuntimeException("CachedSubsetChooser.chooseSubset cannot take forbiddenTables");
	}

}
