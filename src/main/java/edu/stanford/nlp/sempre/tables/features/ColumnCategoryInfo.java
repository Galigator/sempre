package edu.stanford.nlp.sempre.tables.features;

import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ColumnCategoryInfo
{
	public static class Options
	{
		@Option(gloss = "Read category information from this file")
		public String tableCategoryInfo = null;
	}

	public static Options opts = new Options();

	// ============================================================
	// Singleton access
	// ============================================================

	private static ColumnCategoryInfo singleton;

	public static ColumnCategoryInfo getSingleton()
	{
		if (opts.tableCategoryInfo == null)
			return null;
		else
			if (singleton == null)
				singleton = new ColumnCategoryInfo();
		return singleton;
	}

	// ============================================================
	// Read data from file
	// ============================================================

	// tableId -> columnIndex -> list of (category, weight)
	protected static Map<String, List<List<Pair<String, Double>>>> allCategoryInfo = null;

	private ColumnCategoryInfo()
	{
		LogInfo.begin_track("Loading category information from %s", opts.tableCategoryInfo);
		allCategoryInfo = new HashMap<>();
		try
		{
			final BufferedReader reader = IOUtils.openIn(opts.tableCategoryInfo);
			String line;
			while ((line = reader.readLine()) != null)
			{
				final String[] tokens = line.split("\t");
				final String tableId = tokens[0];
				List<List<Pair<String, Double>>> categoryInfoForTable = allCategoryInfo.get(tableId);
				if (categoryInfoForTable == null)
					allCategoryInfo.put(tableId, categoryInfoForTable = new ArrayList<>());
				final int columnIndex = Integer.parseInt(tokens[1]);
				// Assume that the columns are ordered
				assert categoryInfoForTable.size() == columnIndex;
				// Read the category-weight pairs
				final List<Pair<String, Double>> categories = new ArrayList<>();
				for (int i = 2; i < tokens.length; i++)
				{
					final String[] pair = tokens[i].split(":");
					categories.add(new Pair<>(pair[0], Double.parseDouble(pair[1])));
				}
				categoryInfoForTable.add(categories);
			}
			reader.close();
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
		LogInfo.end_track();
	}

	// ============================================================
	// Getters
	// ============================================================

	public List<Pair<String, Double>> get(final String tableId, final int columnIndex)
	{
		return allCategoryInfo.get(tableId).get(columnIndex);
	}

	public List<Pair<String, Double>> get(final Example ex, final String columnId)
	{
		final TableKnowledgeGraph graph = (TableKnowledgeGraph) ex.context.graph;
		final String tableId = graph.filename;
		final int columnIndex = graph.getColumnIndex(columnId);
		if (columnIndex == -1)
			return null;
		return allCategoryInfo.get(tableId).get(columnIndex);
	}

}
