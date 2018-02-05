package edu.stanford.nlp.sempre.tables.test;

import edu.stanford.nlp.sempre.Builder;
import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.Dataset;
import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.DescriptionValue;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.FuzzyMatchFn.FuzzyMatchFnMode;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.MergeFormula.Mode;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import edu.stanford.nlp.sempre.tables.test.CustomExample.ExampleProcessor;
import fig.basic.Evaluation;
import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.ValueComparator;
import fig.exec.Execution;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compute various statistics about the dataset. - Table size (rows, columns, unique cells) - Answer type - Whether the answer is in the table Also aggregate
 * column strings and cell word shapes.
 *
 * @author ppasupat
 */
public class TableStatsComputer implements Runnable
{
	public static class Options
	{
		@Option(gloss = "Maximum string length to consider")
		public int statsMaxStringLength = 70;
	}

	public static Options opts = new Options();

	public static void main(final String[] args)
	{
		Execution.run(args, "TableStatsComputerMain", new TableStatsComputer(), Master.getOptionsParser());
	}

	@Override
	public void run()
	{
		final PrintWriter out = IOUtils.openOutHard(Execution.getFile("table-stats.tsv"));
		final TableStatsComputerProcessor processor = new TableStatsComputerProcessor(out);
		CustomExample.getDataset(Dataset.opts.inPaths, processor);
		processor.analyzeTables();
		out.close();
	}

	static class TableStatsComputerProcessor implements ExampleProcessor
	{
		PrintWriter out;
		Evaluation evaluation = new Evaluation();
		Map<TableKnowledgeGraph, Integer> tableCounts = new HashMap<>();
		Map<String, Integer> columnStrings = new HashMap<>(), cellStrings = new HashMap<>();
		Builder builder;

		public TableStatsComputerProcessor(final PrintWriter out)
		{
			builder = new Builder();
			builder.build();
			this.out = out;
			out.println(String.join("\t", new String[] { "id", "context", "rows", "columns", "uniqueCells", "targetType", "inTable", }));
		}

		@Override
		public void run(final CustomExample ex)
		{
			final List<String> outputFields = new ArrayList<>();
			outputFields.add(ex.id);
			final TableKnowledgeGraph graph = (TableKnowledgeGraph) ex.context.graph;
			MapUtils.incr(tableCounts, graph);
			outputFields.add(graph.toLispTree().child(2).value);
			outputFields.add("" + graph.numRows());
			outputFields.add("" + graph.numColumns());
			outputFields.add("" + graph.numUniqueCells());
			// Answer type. For convenience, just use the first answer from the list
			final Value value = ((ListValue) ex.targetValue).values.get(0);
			evaluation.add("value-number", value instanceof NumberValue);
			evaluation.add("value-date", value instanceof DateValue);
			evaluation.add("value-text", value instanceof DescriptionValue);
			evaluation.add("value-partial-number", value instanceof DescriptionValue && ((DescriptionValue) value).value.matches(".*[0-9].*"));
			// Check if the value is in the table
			boolean inTable = false;
			if (value instanceof DescriptionValue)
			{
				outputFields.add("text");
				final Collection<Formula> formulas = graph.getFuzzyMatchedFormulas(((DescriptionValue) value).value, FuzzyMatchFnMode.ENTITY);
				inTable = !formulas.isEmpty();
				evaluation.add("value-text-in-table", inTable);
			}
			else
				if (value instanceof NumberValue)
				{
					outputFields.add("number");
					// (and (@type @cell) (@p.num ___))
					final Formula formula = new MergeFormula(Mode.and, new JoinFormula(Formula.fromString(CanonicalNames.TYPE), Formula.fromString(TableTypeSystem.CELL_GENERIC_TYPE)), new JoinFormula(Formula.fromString(TableTypeSystem.CELL_NUMBER_VALUE.id), new ValueFormula<>(value)));
					final Value result = builder.executor.execute(formula, ex.context).value;
					inTable = result instanceof ListValue && !((ListValue) result).values.isEmpty();
					evaluation.add("value-number-in-table", inTable);
				}
				else
					if (value instanceof DateValue)
					{
						outputFields.add("date");
						// (and (@type @cell) (@p.num ___))
						final Formula formula = new MergeFormula(Mode.and, new JoinFormula(Formula.fromString(CanonicalNames.TYPE), Formula.fromString(TableTypeSystem.CELL_GENERIC_TYPE)), new JoinFormula(Formula.fromString(TableTypeSystem.CELL_DATE_VALUE.id), new ValueFormula<>(value)));
						final Value result = builder.executor.execute(formula, ex.context).value;
						inTable = result instanceof ListValue && !((ListValue) result).values.isEmpty();
						evaluation.add("value-number-in-table", inTable);
					}
					else
						outputFields.add("unknown");
			evaluation.add("value-any-in-table", inTable);
			outputFields.add("" + inTable);
			out.println(String.join("\t", outputFields));
		}

		public void analyzeTables()
		{
			for (final Map.Entry<TableKnowledgeGraph, Integer> entry : tableCounts.entrySet())
			{
				final TableKnowledgeGraph table = entry.getKey();
				evaluation.add("count", entry.getValue());
				table.populateStats(evaluation);
				for (final String columnString : table.getAllColumnStrings())
					addIfOK(columnString, columnStrings);
				for (final String cellString : table.getAllCellStrings())
					addIfOK(cellString, cellStrings);
			}
			for (final Map.Entry<String, Integer> entry : columnStrings.entrySet())
				evaluation.add("column-strings", entry.getKey(), entry.getValue());
			for (final Map.Entry<String, Integer> entry : cellStrings.entrySet())
				evaluation.add("cell-strings", entry.getKey(), entry.getValue());
			evaluation.logStats("tables");
			dumpCollection(columnStrings, "columns");
			dumpCollection(cellStrings, "cells");
		}

		void addIfOK(String x, final Map<String, Integer> collection)
		{
			x = StringNormalizationUtils.characterNormalize(x).toLowerCase();
			if (x.length() <= TableStatsComputer.opts.statsMaxStringLength)
				MapUtils.incr(collection, x);
		}

		void dumpCollection(final Map<String, Integer> collection, final String filename)
		{
			final List<Map.Entry<String, Integer>> entries = new ArrayList<>(collection.entrySet());
			Collections.sort(entries, new ValueComparator<String, Integer>(true));
			final String path = Execution.getFile(filename);
			LogInfo.begin_track("Writing to %s (%d entries)", path, entries.size());
			final PrintWriter out = IOUtils.openOutHard(path);
			for (final Map.Entry<String, Integer> entry : entries)
				out.printf("%6d : %s\n", entry.getValue(), entry.getKey());
			out.close();
			LogInfo.end_track();
		}

	}
}
