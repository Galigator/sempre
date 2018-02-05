package edu.stanford.nlp.sempre.tables.alter;

import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.FuzzyMatchFn;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.tables.TableCell;
import edu.stanford.nlp.sempre.tables.TableCellProperties;
import edu.stanford.nlp.sempre.tables.TableColumn;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import edu.stanford.nlp.sempre.tables.lambdadcs.DenotationUtils;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Alter the given table. Given a table, the corresponding Example, and a seed (alteredTableIndex), return an altered table.
 *
 * @author ppasupat
 */
public class TableAlterer
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 0;
		@Option(gloss = "parameter for the geometric distribution used to cut the number of rows")
		public double altererGeomDistParam = 0.75;
		@Option
		public int maxNumRows = 50;
	}

	public static Options opts = new Options();

	public final Example ex;
	public final TableKnowledgeGraph oldGraph;

	public TableAlterer(final Example ex)
	{
		this.ex = ex;
		oldGraph = (TableKnowledgeGraph) ex.context.graph;
	}

	/**
	 * For each column, perform random draws with replacement until all rows are filled. Exceptions: - If the column has distinct cells, then just permute. - If
	 * the column is sorted, keep it sorted.
	 */
	public TableKnowledgeGraph constructAlteredGraph(final int alteredTableIndex)
	{
		Random altererRandom = new Random();
		int numRows = Math.min(oldGraph.numRows(), opts.maxNumRows);
		numRows -= getGeometricRandom(numRows / 2, altererRandom);
		final List<List<TableCellProperties>> cellsByColumn = new ArrayList<>();
		// Fuzzy Matching
		final Set<Value> fuzzyMatchedValues = new HashSet<>();
		for (int i = 0; i < ex.numTokens(); i++)
			for (int j = i + 1; j < ex.numTokens(); j++)
				for (final Formula formula : oldGraph.getFuzzyMatchedFormulas(ex.getTokens(), i, j, FuzzyMatchFn.FuzzyMatchFnMode.ENTITY))
					if (formula instanceof ValueFormula)
						fuzzyMatchedValues.add(((ValueFormula<?>) formula).value);
		if (opts.verbose >= 2)
			LogInfo.logs("Fuzzy matched: %s", fuzzyMatchedValues);
		// Go over each column
		for (int j = 0; j < oldGraph.numColumns(); j++)
		{
			altererRandom = new Random();
			final TableColumn oldColumnCells = oldGraph.getColumn(j);
			final List<TableCellProperties> oldColumn = new ArrayList<>(), newColumn = new ArrayList<>();
			for (final TableCell cell : oldColumnCells.children)
				oldColumn.add(cell.properties);
			// Keep the entries that are fuzzy matched
			final Set<TableCellProperties> fuzzyMatchedValuesInColumn = new HashSet<>();
			for (final TableCellProperties properties : oldColumn)
				if (fuzzyMatchedValues.contains(properties.nameValue))
					fuzzyMatchedValuesInColumn.add(properties);
			newColumn.addAll(fuzzyMatchedValuesInColumn);
			while (newColumn.size() > numRows)
				newColumn.remove(newColumn.size() - 1);
			// Sample the cells
			final boolean isAllDistinct = isAllDistinct(oldColumn);
			if (isAllDistinct)
			{
				// Go from top to bottom, ignoring the ones already added
				final List<TableCellProperties> nonFuzzyMatched = new ArrayList<>(oldColumn);
				for (final TableCellProperties properties : newColumn)
					nonFuzzyMatched.remove(properties);
				for (int i = 0; newColumn.size() < numRows; i++)
					newColumn.add(nonFuzzyMatched.get(i));
			}
			else
				// Sample with replacement
				while (newColumn.size() < numRows)
					newColumn.add(oldColumn.get(altererRandom.nextInt(numRows)));
			Collections.shuffle(newColumn, altererRandom);
			// Sort?
			String sorted = "";
			for (final Pair<String, Comparator<TableCellProperties>> pair : COMPS)
				if (isSorted(oldColumn, pair.getSecond()))
				{
					sorted = pair.getFirst();
					newColumn.sort(pair.getSecond());
					break;
				}
			// Done!
			cellsByColumn.add(newColumn);
			if (opts.verbose >= 2)
				LogInfo.logs("Column %3s%4s %s", isAllDistinct ? "[!]" : "", sorted, oldColumnCells.relationNameValue);
		}
		if (opts.verbose >= 1)
			LogInfo.logs("numRows = %d | final size = %d columns x %d rows", numRows, cellsByColumn.size(), cellsByColumn.get(0).size());
		return new TableKnowledgeGraph(null, oldGraph.columns, cellsByColumn, true);
	}

	// ============================================================
	// Helper Functions
	// ============================================================

	int getGeometricRandom(final int limit, final Random random)
	{
		int geometricRandom = 0;
		while (geometricRandom < limit && random.nextDouble() < opts.altererGeomDistParam)
			geometricRandom++;
		return geometricRandom;
	}

	private boolean isAllDistinct(final List<TableCellProperties> properties)
	{
		final Set<String> ids = new HashSet<>();
		for (final TableCellProperties x : properties)
		{
			if (ids.contains(x.id))
				return false;
			ids.add(x.id);
		}
		return true;
	}

	private static final Comparator<TableCellProperties> NUMBER_COMP = (o1, o2) ->
	{
		final Collection<Value> v1 = o1.metadata.get(TableTypeSystem.CELL_NUMBER_VALUE), v2 = o2.metadata.get(TableTypeSystem.CELL_NUMBER_VALUE);
		try
		{
			return DenotationUtils.NumberProcessor.singleton.compareValues(v1.iterator().next(), v2.iterator().next());
		}
		catch (final Exception e)
		{
			throw new ClassCastException();
		}
	};
	private static final Comparator<TableCellProperties> NUMBER_COMP_REV = Collections.reverseOrder(NUMBER_COMP);

	private static final Comparator<TableCellProperties> DATE_COMP = (o1, o2) ->
	{
		final Collection<Value> v1 = o1.metadata.get(TableTypeSystem.CELL_DATE_VALUE), v2 = o2.metadata.get(TableTypeSystem.CELL_DATE_VALUE);
		try
		{
			return DenotationUtils.DateProcessor.singleton.compareValues(v1.iterator().next(), v2.iterator().next());
		}
		catch (final Exception e)
		{
			throw new ClassCastException();
		}
	};
	private static final Comparator<TableCellProperties> DATE_COMP_REV = Collections.reverseOrder(DATE_COMP);

	private static final List<Pair<String, Comparator<TableCellProperties>>> COMPS = Arrays.asList(new Pair<>("[N+]", NUMBER_COMP), new Pair<>("[N-]", NUMBER_COMP_REV), new Pair<>("[D+]", DATE_COMP), new Pair<>("[D-]", DATE_COMP_REV));

	private boolean isSorted(final List<TableCellProperties> properties, final Comparator<TableCellProperties> comparator)
	{
		try
		{
			for (int i = 0; i < properties.size() - 1; i++)
				if (comparator.compare(properties.get(i), properties.get(i + 1)) > 0)
					return false;
			return true;
		}
		catch (final ClassCastException e)
		{
			return false;
		}
	}

}
