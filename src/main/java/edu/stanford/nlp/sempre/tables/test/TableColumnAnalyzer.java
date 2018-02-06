package edu.stanford.nlp.sempre.tables.test;

import edu.stanford.nlp.sempre.Dataset;
import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import edu.stanford.nlp.sempre.tables.TableCell;
import edu.stanford.nlp.sempre.tables.TableColumn;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.Pair;
import fig.exec.Execution;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyze table columns and print out any hard-to-process column.
 *
 * @author ppasupat
 */
public class TableColumnAnalyzer implements Runnable
{
	public static class Options
	{
		@Option(gloss = "Maximum number of tables to process (for debugging)")
		public int maxNumTables = Integer.MAX_VALUE;
		@Option(gloss = "Load Wikipedia article titles from this file")
		public String wikiTitles = null;
	}

	public static Options opts = new Options();

	public static void main(final String[] args)
	{
		Execution.run(args, "TableColumnAnalyzerMain", new TableColumnAnalyzer(), Master.getOptionsParser());
	}

	PrintWriter out;
	PrintWriter outCompact;

	@Override
	public void run()
	{
		out = IOUtils.openOutHard(Execution.getFile("column-stats.tsv"));
		outCompact = IOUtils.openOutHard(Execution.getFile("column-compact.tsv"));
		final Map<String, List<String>> tableIdToExIds = getTableIds();
		int tablesProcessed = 0;
		for (final Map.Entry<String, List<String>> entry : tableIdToExIds.entrySet())
		{
			Execution.putOutput("example", tablesProcessed);
			final String tableId = entry.getKey(), tableIdAbbrev = tableId.replaceAll("csv/(\\d+)-csv/(\\d+)\\.csv", "$1-$2");
			LogInfo.begin_track("Processing %s ...", tableId);
			final TableKnowledgeGraph graph = TableKnowledgeGraph.fromFilename(tableId);
			out.printf("%s\tIDS\t%s\n", tableIdAbbrev, String.join(" ", entry.getValue()));
			out.printf("%s\tCOLUMNS\t%d\n", tableIdAbbrev, graph.numColumns());
			for (int i = 0; i < graph.numColumns(); i++)
				analyzeColumn(graph, graph.columns.get(i), tableIdAbbrev + "\t" + i);
			LogInfo.end_track();
			if (tablesProcessed++ >= opts.maxNumTables)
				break;
		}
		out.close();
		outCompact.close();
	}

	protected Map<String, List<String>> getTableIds()
	{
		final Map<String, List<String>> tableIdToExIds = new LinkedHashMap<>();
		LogInfo.begin_track_printAll("Collect table IDs");
		for (final Pair<String, String> pathPair : Dataset.opts.inPaths)
		{
			final String group = pathPair.getFirst();
			final String path = pathPair.getSecond();
			Execution.putOutput("group", group);
			LogInfo.begin_track("Reading %s", path);
			final Iterator<LispTree> trees = LispTree.proto.parseFromFile(path);
			while (trees.hasNext())
			{
				final LispTree tree = trees.next();
				if ("metadata".equals(tree.child(0).value))
					continue;
				String exId = null, tableId = null;
				for (int i = 1; i < tree.children.size(); i++)
				{
					final LispTree arg = tree.child(i);
					final String label = arg.child(0).value;
					if ("id".equals(label))
						exId = arg.child(1).value;
					else
						if ("context".equals(label))
							tableId = arg.child(1).child(2).value;
				}
				if (exId != null && tableId != null)
				{
					List<String> exIdsForTable = tableIdToExIds.get(tableId);
					if (exIdsForTable == null)
						tableIdToExIds.put(tableId, exIdsForTable = new ArrayList<>());
					exIdsForTable.add(exId);
				}
			}
			LogInfo.end_track();
		}
		LogInfo.end_track();
		LogInfo.logs("Got %d IDs", tableIdToExIds.size());
		return tableIdToExIds;
	}

	protected void analyzeColumn(final TableKnowledgeGraph graph, final TableColumn column, final String printPrefix)
	{
		final List<String> escapedCells = new ArrayList<>();
		// Print the header
		final String h = column.originalString, escapedH = StringNormalizationUtils.escapeTSV(h);
		out.printf("%s\t0\t%s\n", printPrefix, escapedH);
		escapedCells.add(escapedH);
		// Print the cells
		final Map<String, Integer> typeCounts = new HashMap<>();
		for (int j = 0; j < column.children.size(); j++)
		{
			final TableCell cell = column.children.get(j);
			final String c = cell.properties.originalString, escapedC = StringNormalizationUtils.escapeTSV(c);
			escapedCells.add(escapedC);
			// Infer the type
			final List<String> types = analyzeCell(c);
			for (final String type : types)
				MapUtils.incr(typeCounts, type);
			out.printf("%s\t%d\t%s\t%s\n", printPrefix, j + 1, String.join("|", types), escapedC);
		}
		// Analyze the common types
		final List<String> commonTypes = new ArrayList<>();
		for (final Map.Entry<String, Integer> entry : typeCounts.entrySet())
			if (entry.getValue() == column.children.size())
				commonTypes.add(entry.getKey());
			else
				if (entry.getValue() == column.children.size() - 1)
					commonTypes.add("ALMOST-" + entry.getKey());
		outCompact.printf("%s\t%s\t%s\n", String.join("|", commonTypes), printPrefix, String.join("\t", escapedCells));
	}

	// ============================================================
	// Cell analysis
	// ============================================================

	public static final Pattern ORDINAL = Pattern.compile("^(\\d+)(st|nd|rd|th)$");

	protected List<String> analyzeCell(final String c)
	{
		final List<String> types = new ArrayList<>();
		final LanguageInfo languageInfo = LanguageAnalyzer.getSingleton().analyze(c);
		{
			// Integer
			final NumberValue n = StringNormalizationUtils.parseNumberStrict(c);
			if (n != null)
			{
				// Number
				types.add("num");
				// Integer
				final double value = n._value;
				if (Math.abs(value - Math.round(value)) < 1e-9)
				{
					types.add("int");
					if (c.matches("^[12]\\d\\d\\d$"))
						// Year?
						types.add("year");
				}
			}
		}
		{
			// Ordinal
			final Matcher m = ORDINAL.matcher(c);
			if (m.matches())
				types.add("ordinal");
		}
		{
			// Integer-Integer
			final String[] splitted = StringNormalizationUtils.STRICT_DASH.split(c);
			if (splitted.length == 2 && splitted[0].matches("^[0-9]+$") && splitted[1].matches("^[0-9]+$"))
				types.add("2ints");
		}
		{
			// Date
			final DateValue date = StringNormalizationUtils.parseDateWithLanguageAnalyzer(languageInfo);
			if (date != null)
			{
				types.add("date");
				// Also more detailed date type
				types.add("date-" + (date.year != -1 ? "Y" : "") + (date.month != -1 ? "M" : "") + (date.day != -1 ? "D" : ""));
			}
		}
		{
			// Quoted text
			if (c.matches("^[“”\"].*[“”\"]$"))
				types.add("quoted");
		}
		if (opts.wikiTitles != null)
		{
			// Wikipedia titles
			final WikipediaTitleLibrary library = WikipediaTitleLibrary.getSingleton();
			if (library.contains(c))
				types.add("wiki");
		}
		{
			// POS and NER
			types.add("POS=" + String.join("-", languageInfo.posTags));
			types.add("NER=" + String.join("-", languageInfo.nerTags));
		}
		return types;
	}

	// ============================================================
	// Helper class: Wikipedia titles
	// ============================================================

	public static class WikipediaTitleLibrary
	{

		private static WikipediaTitleLibrary _singleton = null;

		public static WikipediaTitleLibrary getSingleton()
		{
			if (_singleton == null)
				_singleton = new WikipediaTitleLibrary();
			return _singleton;
		}

		Set<String> titles = new HashSet<>();

		private WikipediaTitleLibrary()
		{
			assert opts.wikiTitles != null;
			LogInfo.begin_track("Reading Wikipedia article titles from %s ...", opts.wikiTitles);
			try
			{
				final BufferedReader reader = IOUtils.openIn(opts.wikiTitles);
				String line;
				while ((line = reader.readLine()) != null)
				{
					titles.add(line);
					if (titles.size() <= 10)
						LogInfo.logs("Example title: %s", line);
				}
			}
			catch (final IOException e)
			{
				throw new RuntimeException(e);
			}
			LogInfo.logs("Read %d titles", titles.size());
			LogInfo.end_track();
		}

		public boolean contains(final String c)
		{
			return titles.contains(c.toLowerCase().trim());
		}
	}

}
