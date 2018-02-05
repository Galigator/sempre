package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.BadFormulaException;
import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.FuzzyMatchFn;
import edu.stanford.nlp.sempre.FuzzyMatchFn.FuzzyMatchFnMode;
import edu.stanford.nlp.sempre.FuzzyMatchable;
import edu.stanford.nlp.sempre.KnowledgeGraph;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.TableValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.lambdadcs.ExecutorCache;
import edu.stanford.nlp.sempre.tables.lambdadcs.InfiniteUnaryDenotation;
import edu.stanford.nlp.sempre.tables.lambdadcs.LambdaDCSException;
import edu.stanford.nlp.sempre.tables.lambdadcs.LambdaDCSException.Type;
import edu.stanford.nlp.sempre.tables.match.FuzzyMatcher;
import edu.stanford.nlp.sempre.tables.serialize.TableReader;
import edu.stanford.nlp.sempre.tables.serialize.TableWriter;
import fig.basic.Evaluation;
import fig.basic.Fmt;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import fig.basic.StopWatchSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A knowledge graph constructed from a table. - Each row becomes an entity - Each cell becomes an entity - Each column becomes a property between a row and a
 * cell e.g., (row5 nationality canada) - Rows have several special properties (next, index) === Special Row Properties === - name = fb:row.row.next | type =
 * (-> fb:type.row fb:type.row) - name = fb:row.row.index | type = (-> fb:type.int fb:type.row) === Special Cell Properties === - name = fb:cell.cell.number |
 * type = (-> fb:type.number fb:type.cell)
 *
 * @author ppasupat
 */
public class TableKnowledgeGraph extends KnowledgeGraph implements FuzzyMatchable
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
		@Option(gloss = "Base directory for CSV files")
		public String baseCSVDir = null;
		@Option(gloss = "Whether to cache TableKnowledgeGraph")
		public boolean cacheTableKnowledgeGraphs = true;
		@Option(gloss = "Forbid row.row.next on multiple rows")
		public boolean forbidNextOnManyRows = true;
		@Option(gloss = "Set up executor cache for each graph (must manually clear, or else will get memory overflow)")
		public boolean individualExecutorCache = false;
		@Option(gloss = "Have the row index starts at 1 instead of 0")
		public boolean rowIndexStartsAt1 = true;
	}

	public static Options opts = new Options();

	// ============================================================
	// Fields
	// ============================================================

	public List<TableRow> rows;
	public List<TableColumn> columns;
	public Set<TableCellProperties> cellProperties;
	public Set<NameValue> cellParts;
	public final String filename;

	// "fb:row.r5" --> TableRow object
	Map<String, TableRow> rowIdToTableRow;
	// "fb:row.row.population" --> TableColumn object
	Map<String, TableColumn> relationIdToTableColumn;
	// "fb:cell.palo_alto_ca" --> TableCellProperties object
	Map<String, TableCellProperties> cellIdToTableCellProperties;
	// "fb:part.palo_alto" --> String
	Map<String, String> partIdToOriginalString;

	FuzzyMatcher fuzzyMatcher;
	public ExecutorCache executorCache;

	@Override
	public void clean()
	{
		if (executorCache != null)
			executorCache.clearCache(this);
	}

	// ============================================================
	// Constructor
	// ============================================================

	/**
	 * Construct a new TableKnowledgeGraph from a String matrix. Does not cache the data.
	 */
	public TableKnowledgeGraph(final String filename, final Iterable<String[]> data)
	{
		this.filename = filename;
		// Used column names (no two columns have the same id)
		final Set<String> usedColumnNames = new HashSet<>();
		// Cells in the same column with the same string content gets the same id.
		final Map<Pair<TableColumn, String>, String> columnAndOriginalStringToCellId = new HashMap<>();
		// Go though the data
		for (final String[] record : data)
			if (columns == null)
			{
				// Initialize
				rows = new ArrayList<>();
				columns = new ArrayList<>();
				rowIdToTableRow = new HashMap<>();
				relationIdToTableColumn = new HashMap<>();
				cellIdToTableCellProperties = new HashMap<>();
				// Read the header row
				for (final String entry : record)
				{
					final String normalizedEntry = StringNormalizationUtils.characterNormalize(entry).toLowerCase();
					final String canonicalName = TableTypeSystem.canonicalizeName(normalizedEntry);
					final String columnName = TableTypeSystem.getUnusedName(canonicalName, usedColumnNames);
					final TableColumn column = new TableColumn(entry, columnName, columns.size());
					columns.add(column);
					usedColumnNames.add(columnName);
					relationIdToTableColumn.put(column.relationNameValue.id, column);
				}
			}
			else
			{
				// Read the content row
				if (record.length != columns.size())
					LogInfo.warnings("Table has %d columns but row has %d cells: %s | %s", columns.size(), record.length, columns, Fmt.D(record));
				final int rowIndex = opts.rowIndexStartsAt1 ? rows.size() + 1 : rows.size();
				final TableRow currentRow = new TableRow(rowIndex);
				rowIdToTableRow.put(currentRow.nameValue.id, currentRow);
				rows.add(currentRow);
				for (int i = 0; i < columns.size(); i++)
				{
					final TableColumn column = columns.get(i);
					final String cellName = i < record.length ? record[i] : "";
					// Create a NameValue
					final String normalizedCellName = StringNormalizationUtils.characterNormalize(cellName).toLowerCase();
					final Pair<TableColumn, String> columnAndOriginalString = new Pair<>(column, normalizedCellName);
					String id = columnAndOriginalStringToCellId.get(columnAndOriginalString);
					if (id == null)
					{
						final String canonicalName = TableTypeSystem.canonicalizeName(normalizedCellName);
						id = TableTypeSystem.getUnusedName(TableTypeSystem.getCellName(canonicalName, column.columnName), cellIdToTableCellProperties.keySet());
						columnAndOriginalStringToCellId.put(columnAndOriginalString, id);
						cellIdToTableCellProperties.put(id, new TableCellProperties(id, cellName));
					}
					final TableCellProperties properties = cellIdToTableCellProperties.get(id);
					TableCell.createAndAddTo(properties, column, currentRow);
				}
			}
		// Generate cell properties by analyzing cell content in each column
		for (final TableColumn column : columns)
			StringNormalizationUtils.analyzeColumn(column);
		// Collect cell properties for public access
		cellProperties = new HashSet<>(cellIdToTableCellProperties.values());
		cellParts = new HashSet<>();
		partIdToOriginalString = new HashMap<>();
		for (final TableCellProperties properties : cellProperties)
			for (final Value part : properties.metadata.get(TableTypeSystem.CELL_PART_VALUE))
			{
				final NameValue partNameValue = (NameValue) part;
				cellParts.add(partNameValue);
				partIdToOriginalString.put(partNameValue.id, partNameValue.description);
			}
		// Precompute normalized strings for fuzzy matching
		fuzzyMatcher = FuzzyMatcher.getFuzzyMatcher(this);
		executorCache = opts.individualExecutorCache ? new ExecutorCache() : null;
	}

	/**
	 * Read CSV or TSV file.
	 */
	TableKnowledgeGraph(final String filename) throws IOException
	{
		this(filename, new TableReader(filename));
	}

	// Cache (don't create multiple graphs for the same CSV or TSV file)
	static final Map<String, TableKnowledgeGraph> filenameToGraph = new HashMap<>();

	public static synchronized TableKnowledgeGraph fromRootedFilename(final String filename)
	{
		// Get from cache if possible
		TableKnowledgeGraph graph = filenameToGraph.get(filename);
		if (graph == null)
		{
			if (opts.verbose >= 1)
				LogInfo.logs("create new TableKnowledgeGraph from filename = %s", filename);
			StopWatchSet.begin("TableKnowledgeGraph.new");
			try
			{
				graph = new TableKnowledgeGraph(filename);
			}
			catch (final IOException e)
			{
				throw new RuntimeException(e);
			}
			StopWatchSet.end();
			if (opts.cacheTableKnowledgeGraphs)
				filenameToGraph.put(filename, graph);
		}
		return graph;
	}

	public static TableKnowledgeGraph fromFilename(final String filename)
	{
		return fromRootedFilename(new File(opts.baseCSVDir, filename).getPath());
	}

	public static TableKnowledgeGraph fromLispTree(final LispTree tree)
	{
		if (tree.children.size() > 3 && "rooted-path".equals(tree.child(3).value))
			return fromRootedFilename(tree.child(2).value);
		else
			return fromFilename(tree.child(2).value);

	}

	// ============================================================
	// Construct from existing cells
	// ============================================================

	/**
	 * Construct a new TableKnowledgeGraph using the same columns as an old table but with different cell ordering. Does not cache the data.
	 */
	public TableKnowledgeGraph(final String filename, final List<TableColumn> oldColumns, final List<List<TableCellProperties>> oldCells, final boolean cellsAreGroupedByColumn)
	{
		this.filename = filename;
		rows = new ArrayList<>();
		columns = new ArrayList<>();
		rowIdToTableRow = new HashMap<>();
		relationIdToTableColumn = new HashMap<>();
		cellIdToTableCellProperties = new HashMap<>();
		// Header row
		for (final TableColumn oldColumn : oldColumns)
		{
			final TableColumn column = new TableColumn(oldColumn);
			columns.add(column);
			relationIdToTableColumn.put(column.relationNameValue.id, column);
		}
		// Sanity check
		int numRows;
		final int numColumns = columns.size();
		if (cellsAreGroupedByColumn)
		{ // oldCells[column][row]
			if (oldCells.size() != numColumns)
				throw new RuntimeException("Mismatched sizes: oldCells has " + oldCells.size() + " != " + numColumns + " columns");
			numRows = oldCells.get(0).size();
			for (final List<TableCellProperties> oldCellsRow : oldCells)
				if (oldCellsRow.size() != numRows)
					throw new RuntimeException("Mismatched sizes: oldCells has " + oldCells.size() + " != " + numColumns + " rows");
		}
		else
		{ // oldCells[row][column]
			numRows = oldCells.size();
			for (final List<TableCellProperties> oldCellsColumn : oldCells)
				if (oldCellsColumn.size() != numColumns)
					throw new RuntimeException("Mismatched sizes: oldCells has " + oldCells.size() + " != " + numColumns + " columns");
		}
		// Content rows
		for (int i = 0; i < numRows; i++)
		{
			final TableRow currentRow = new TableRow(i);
			rows.add(currentRow);
			rowIdToTableRow.put(currentRow.nameValue.id, currentRow);
			for (int j = 0; j < numColumns; j++)
			{
				final TableColumn column = columns.get(j);
				final TableCellProperties properties = new TableCellProperties(cellsAreGroupedByColumn ? oldCells.get(j).get(i) : oldCells.get(i).get(j));
				cellIdToTableCellProperties.put(properties.id, properties);
				TableCell.createAndAddTo(properties, column, currentRow);
			}
		}
		// Finalize
		cellProperties = new HashSet<>(cellIdToTableCellProperties.values());
		cellParts = new HashSet<>();
		for (final TableCellProperties properties : cellProperties)
			for (final Value part : properties.metadata.get(TableTypeSystem.CELL_PART_VALUE))
				cellParts.add((NameValue) part);
		// Precompute normalized strings for fuzzy matching
		fuzzyMatcher = FuzzyMatcher.getFuzzyMatcher(this);
		executorCache = opts.individualExecutorCache ? new ExecutorCache() : null;
	}

	// ============================================================
	// Convert to other formats
	// ============================================================

	@Override
	public LispTree toLispTree()
	{
		if (filename != null)
		{
			// short version: just print the filename
			final LispTree tree = LispTree.proto.newList();
			tree.addChild("graph");
			tree.addChild("tables.TableKnowledgeGraph");
			if (filename.startsWith(opts.baseCSVDir))
				tree.addChild(Paths.get(opts.baseCSVDir).relativize(Paths.get(filename)).toString());
			else
			{
				tree.addChild(filename);
				tree.addChild("rooted-path");
			}
			return tree;
		}
		return toTableValue().toLispTree();
	}

	@Override
	public LispTree toShortLispTree()
	{
		return toLispTree();
	}

	public TableValue toTableValue()
	{
		final List<String> tableValueHeader = new ArrayList<>();
		final List<List<Value>> tableValueRows = new ArrayList<>();
		for (final TableColumn column : columns)
			tableValueHeader.add(column.originalString);
		for (final TableRow row : rows)
		{
			final List<Value> tableValueRow = new ArrayList<>();
			for (final TableCell cell : row.children)
				tableValueRow.add(cell.properties.nameValue);
			tableValueRows.add(tableValueRow);
		}
		return new TableValue(tableValueHeader, tableValueRows);
	}

	public void log()
	{
		new TableWriter(this).log();
	}

	// ============================================================
	// Fuzzy matching
	// ============================================================

	@Override
	public Collection<Formula> getFuzzyMatchedFormulas(final String term, final FuzzyMatchFn.FuzzyMatchFnMode mode)
	{
		return fuzzyMatcher.getFuzzyMatchedFormulas(term, mode);
	}

	@Override
	public Collection<Formula> getFuzzyMatchedFormulas(final List<String> sentence, final int startIndex, final int endIndex, final FuzzyMatchFnMode mode)
	{
		return fuzzyMatcher.getFuzzyMatchedFormulas(sentence, startIndex, endIndex, mode);
	}

	@Override
	public Collection<Formula> getAllFormulas(final FuzzyMatchFn.FuzzyMatchFnMode mode)
	{
		return fuzzyMatcher.getAllFormulas(mode);
	}

	// ============================================================
	// Query
	// ============================================================

	public static final NameValue TYPE = new NameValue(CanonicalNames.TYPE);
	public static final NameValue ROW_TYPE = new NameValue(TableTypeSystem.ROW_TYPE);

	/** Return all y such that x in firsts and (x,r,y) in graph */
	@Override
	public List<Value> joinFirst(final Value r, final Collection<Value> firsts)
	{
		return joinSecond(CanonicalNames.reverseProperty(r), firsts);
	}

	/** Return all x such that y in seconds and (x,r,y) in graph */
	@Override
	public List<Value> joinSecond(final Value r, final Collection<Value> seconds)
	{
		final List<Value> answer = new ArrayList<>();
		for (final Pair<Value, Value> pair : filterSecond(r, seconds))
			answer.add(pair.getFirst());
		return answer;
	}

	/** Return all (x,y) such that x in firsts and (x,r,y) in graph */
	@Override
	public List<Pair<Value, Value>> filterFirst(final Value r, final Collection<Value> firsts)
	{
		return getReversedPairs(filterSecond(CanonicalNames.reverseProperty(r), firsts));
	}

	/*
	 * - {one,many} to one: Each X maps to 1 Y:
	 *   X-Y = row-row, row-primitive, primitive-row, row-cell, cell-primitive
	 * - {one,many} to many: Remove duplicates first, then each X maps to possibly many Y's
	 *   X-Y = cell-row, primitive-cell
	 */
	/** Return all (x,y) such that y in seconds and (x,r,y) in graph */
	// TODO(ice): Check correctness
	@Override
	public List<Pair<Value, Value>> filterSecond(Value r, final Collection<Value> seconds)
	{
		final List<Pair<Value, Value>> answer = new ArrayList<>();
		if (CanonicalNames.isReverseProperty(r))
		{
			r = CanonicalNames.reverseProperty(r);
			if (r.equals(TYPE))
				////////////////////////////////////////////////////////////
				// (!fb:type.object.type fb:row.r5) --> fb:type.row
				// Not handled right now.
				throw new BadFormulaException("Unhandled! " + r);
			else
				if (r.equals(TableTypeSystem.ROW_NEXT_VALUE))
				{
					////////////////////////////////////////////////////////////
					// (!fb:row.row.next fb:row.r5) --> fb:row.r6
					if (opts.forbidNextOnManyRows && seconds.size() != 1 && seconds != InfiniteUnaryDenotation.STAR_UNARY)
						throw new LambdaDCSException(Type.nonSingletonList, "cannot call next on " + seconds.size() + " rows.");
					if (seconds.size() == Integer.MAX_VALUE)
						for (int i = 0; i < rows.size() - 1; i++)
						{
							if (!seconds.contains(rows.get(i).nameValue))
								continue;
							answer.add(new Pair<>(rows.get(i + 1).nameValue, rows.get(i).nameValue));
						}
					else
						for (final Value value : seconds)
						{
							if (!(value instanceof NameValue))
								continue;
							final TableRow row = rowIdToTableRow.get(((NameValue) value).id);
							if (row == null)
								continue;
							final int i = opts.rowIndexStartsAt1 ? row.index - 1 : row.index;
							if (i + 1 >= rows.size())
								continue;
							answer.add(new Pair<>(rows.get(i + 1).nameValue, row.nameValue));
						}
				}
				else
					if (r.equals(TableTypeSystem.ROW_INDEX_VALUE))
					{
						////////////////////////////////////////////////////////////
						// (!fb:row.row.index fb:row.r5) --> (number 5)
						if (seconds.size() == Integer.MAX_VALUE)
							for (final TableRow row : rows)
							{
								if (!seconds.contains(row.nameValue))
									continue;
								answer.add(new Pair<>(row.indexValue, row.nameValue));
							}
						else
							for (final Value value : seconds)
							{
								if (!(value instanceof NameValue))
									continue;
								final TableRow row = rowIdToTableRow.get(((NameValue) value).id);
								if (row == null)
									continue;
								answer.add(new Pair<>(row.indexValue, row.nameValue));
							}
					}
					else
						if (TableTypeSystem.isCellProperty(r))
						{
							////////////////////////////////////////////////////////////
							// (!fb:cell.cell.number fb:cell_id.5) --> 5
							if (seconds.size() == Integer.MAX_VALUE)
								for (final TableColumn column : columns)
									for (final TableCell cell : column.children)
										for (final Value property : cell.properties.metadata.get(r))
										{
											if (!seconds.contains(cell.properties.nameValue))
												continue;
											answer.add(new Pair<>(property, cell.properties.nameValue));
										}
							else
								for (final Value value : seconds)
								{
									if (!(value instanceof NameValue))
										continue;
									final TableCellProperties properties = cellIdToTableCellProperties.get(((NameValue) value).id);
									if (properties == null)
										continue;
									for (final Value property : properties.metadata.get(r))
										answer.add(new Pair<>(property, properties.nameValue));
								}
						}
						else
							if (TableTypeSystem.isRowProperty(r))
							{
								////////////////////////////////////////////////////////////
								// (!fb:row.row.nationality fb:row.r5) --> fb:cell.canada
								if (seconds.size() == Integer.MAX_VALUE)
									for (int i = 0; i < columns.size(); i++)
									{
										if (!r.equals(columns.get(i).relationNameValue))
											continue;
										for (final TableRow row : rows)
										{
											if (!seconds.contains(row.nameValue))
												continue;
											answer.add(new Pair<>(row.children.get(i).properties.nameValue, row.nameValue));
										}
									}
								else
									for (int i = 0; i < columns.size(); i++)
									{
										if (!r.equals(columns.get(i).relationNameValue))
											continue;
										for (final Value value : seconds)
										{
											if (!(value instanceof NameValue))
												continue;
											final TableRow row = rowIdToTableRow.get(((NameValue) value).id);
											if (row == null)
												continue;
											answer.add(new Pair<>(row.children.get(i).properties.nameValue, row.nameValue));
										}
									}
							}
							else
								if (TableTypeSystem.isRowConsecutiveProperty(r))
									////////////////////////////////////////////////////////////
									// (!fb:row.consecutive.nationality fb:row.r5) --> (number 2)
									for (int i = 0; i < columns.size(); i++)
									{
										if (!r.equals(columns.get(i).relationConsecutiveNameValue))
											continue;
										int count = 0;
										NameValue lastCell = null;
										for (final TableRow row : rows)
										{
											if (row.children.get(i).properties.nameValue.equals(lastCell))
												count++;
											else
											{
												count = 1;
												lastCell = row.children.get(i).properties.nameValue;
											}
											if (!seconds.contains(row.nameValue))
												continue;
											answer.add(new Pair<>(new NumberValue(count), row.nameValue));
										}
									}
		}
		else
			if (r.equals(TYPE))
			{
				////////////////////////////////////////////////////////////
				// (fb:type.object.type fb:type.row) --> {fb:row.r1, fb:row.r2, ...}
				for (final Value second : seconds)
					if (second.equals(ROW_TYPE))
						for (final TableRow row : rows)
							answer.add(new Pair<>(row.nameValue, second));
			}
			else
				if (r.equals(TableTypeSystem.ROW_NEXT_VALUE))
				{
					////////////////////////////////////////////////////////////
					// (fb:row.row.next fb:row.r5) --> fb:row.r4
					if (opts.forbidNextOnManyRows && seconds.size() != 1 && seconds != InfiniteUnaryDenotation.STAR_UNARY)
						throw new LambdaDCSException(Type.nonSingletonList, "cannot call next on " + seconds.size() + " rows.");
					if (seconds.size() == Integer.MAX_VALUE)
						for (int i = 1; i < rows.size(); i++)
						{
							if (!seconds.contains(rows.get(i).nameValue))
								continue;
							answer.add(new Pair<>(rows.get(i - 1).nameValue, rows.get(i).nameValue));
						}
					else
						for (final Value value : seconds)
						{
							if (!(value instanceof NameValue))
								continue;
							final TableRow row = rowIdToTableRow.get(((NameValue) value).id);
							if (row == null)
								continue;
							final int i = opts.rowIndexStartsAt1 ? row.index - 1 : row.index;
							if (i - 1 < 0)
								continue;
							answer.add(new Pair<>(rows.get(i - 1).nameValue, row.nameValue));
						}
				}
				else
					if (r.equals(TableTypeSystem.ROW_INDEX_VALUE))
					{
						////////////////////////////////////////////////////////////
						// (fb:row.row.index (number 5)) --> fb:row.r5
						if (seconds.size() == Integer.MAX_VALUE)
							for (final TableRow row : rows)
							{
								if (!seconds.contains(row.indexValue))
									continue;
								answer.add(new Pair<>(row.nameValue, row.indexValue));
							}
						else
							for (final Value value : seconds)
							{
								if (!(value instanceof NumberValue))
									continue;
								final double x = ((NumberValue) value).value;
								if (Math.abs(x - Math.round(x)) > 1e-6)
									continue; // Ignore non-integers
								int i = (int) x;
								if (opts.rowIndexStartsAt1)
									i--;
								if (i < 0 || i >= rows.size())
									continue;
								final TableRow row = rows.get(i);
								answer.add(new Pair<>(row.nameValue, row.indexValue));
							}
					}
					else
						if (TableTypeSystem.isCellProperty(r))
							////////////////////////////////////////////////////////////
							// (fb:cell.cell.number (number 5)) --> {fb:cell_id.5 fb:cell_population.5, ...}
							// Possibly with repeated id (if there are multiple cells with that id)
							for (final TableColumn column : columns)
								for (final TableCell cell : column.children)
									for (final Value property : cell.properties.metadata.get(r))
									{
										if (!seconds.contains(property))
											continue;
										answer.add(new Pair<>(cell.properties.nameValue, property));
									}
						else
							if (TableTypeSystem.isRowProperty(r))
								////////////////////////////////////////////////////////////
								// (fb:row.row.nationality fb:cell.canada) --> fb:row.r5
								for (int i = 0; i < columns.size(); i++)
								{
									if (!r.equals(columns.get(i).relationNameValue))
										continue;
									for (final TableRow row : rows)
									{
										if (!seconds.contains(row.children.get(i).properties.nameValue))
											continue;
										answer.add(new Pair<>(row.nameValue, row.children.get(i).properties.nameValue));
									}
								}
							else
								if (TableTypeSystem.isRowConsecutiveProperty(r))
									////////////////////////////////////////////////////////////
									// (fb:row.consecutive.nationality (number 2)) --> fb:row.r5
									for (int i = 0; i < columns.size(); i++)
									{
										if (!r.equals(columns.get(i).relationConsecutiveNameValue))
											continue;
										int count = 0;
										NameValue lastCell = null;
										for (final TableRow row : rows)
										{
											if (row.children.get(i).properties.nameValue.equals(lastCell))
												count++;
											else
											{
												count = 1;
												lastCell = row.children.get(i).properties.nameValue;
											}
											if (!seconds.contains(new NumberValue(count)))
												continue;
											answer.add(new Pair<>(row.nameValue, new NumberValue(count)));
										}
									}
		return answer;
	}

	// ============================================================
	// Methods specific to TableKnowledgeGraph
	// ============================================================

	public void populateStats(final Evaluation evaluation)
	{
		evaluation.add("rows", rows.size());
		evaluation.add("columns", columns.size());
		evaluation.add("cells", rows.size() * columns.size());
	}

	public int numRows()
	{
		return rows.size();
	}

	public int numColumns()
	{
		return columns.size();
	}

	public int numUniqueCells()
	{
		return cellProperties.size();
	}

	public TableRow getRow(final int rowIndex)
	{
		return rows.get(rowIndex);
	}

	public TableColumn getColumn(final int columnIndex)
	{
		return columns.get(columnIndex);
	}

	public TableCell getCell(final int rowIndex, final int colIndex)
	{
		return rows.get(rowIndex).children.get(colIndex);
	}

	public List<String> getAllColumnStrings()
	{
		final List<String> columnStrings = new ArrayList<>();
		for (final TableColumn column : columns)
			columnStrings.add(column.originalString);
		return columnStrings;
	}

	public List<String> getAllCellStrings()
	{
		final List<String> cellStrings = new ArrayList<>();
		for (final TableColumn column : columns)
			for (final TableCell cell : column.children)
				cellStrings.add(cell.properties.originalString);
		return cellStrings;
	}

	public String getOriginalString(final Value value)
	{
		return value instanceof NameValue ? getOriginalString(((NameValue) value).id) : null;
	}

	public String getOriginalString(String nameValueId)
	{
		if (nameValueId.startsWith("!"))
			nameValueId = nameValueId.substring(1);
		if (cellIdToTableCellProperties.containsKey(nameValueId))
			return cellIdToTableCellProperties.get(nameValueId).originalString;
		if (partIdToOriginalString.containsKey(nameValueId))
			return partIdToOriginalString.get(nameValueId);
		if (relationIdToTableColumn.containsKey(nameValueId))
			return relationIdToTableColumn.get(nameValueId).originalString;
		if (nameValueId.startsWith(TableTypeSystem.CELL_SPECIFIC_TYPE_PREFIX))
		{
			final String property = nameValueId.replace(TableTypeSystem.CELL_SPECIFIC_TYPE_PREFIX, TableTypeSystem.ROW_PROPERTY_NAME_PREFIX);
			if (relationIdToTableColumn.containsKey(property))
				return relationIdToTableColumn.get(property).originalString;
		}
		return null;
	}

	public Value getNameValueWithOriginalString(NameValue value)
	{
		if (value.description == null)
			value = new NameValue(value.id, getOriginalString(value.id));
		return value;
	}

	public ListValue getListValueWithOriginalStrings(final ListValue answers)
	{
		final List<Value> values = new ArrayList<>();
		for (Value value : answers.values)
		{
			if (value instanceof NameValue)
			{
				final NameValue name = (NameValue) value;
				if (name.description == null)
					value = new NameValue(name.id, getOriginalString(name.id));
			}
			values.add(value);
		}
		return new ListValue(values);
	}

	/**
	 * Return a list of rows that contain a cell with the specified NameValue ID.
	 */
	public List<Integer> getRowsOfCellId(final String nameValueId)
	{
		final String property = TableTypeSystem.getPropertyOfEntity(nameValueId);
		if (property == null)
			return null;
		final TableColumn column = relationIdToTableColumn.get(property);
		if (column == null)
			return null;
		final List<Integer> answer = new ArrayList<>();
		for (int i = 0; i < column.children.size(); i++)
			if (column.children.get(i).properties.id.equals(nameValueId))
				answer.add(i);
		return answer;
	}

	/**
	 * Return the index of the column with the specified ID. Return -1 if not found.
	 */
	public int getColumnIndex(String nameValueId)
	{
		if (nameValueId.startsWith("!"))
			nameValueId = nameValueId.substring(1);
		for (int j = 0; j < columns.size(); j++)
			if (columns.get(j).relationNameValue.id.equals(nameValueId))
				return j;
		return -1;
	}

	// ============================================================
	// Test
	// ============================================================

	public static void main(final String[] args)
	{
		StringNormalizationUtils.opts.verbose = 5;
		StringNormalizationUtils.opts.numberCanStartAnywhere = true;
		StringNormalizationUtils.opts.num2CanStartAnywhere = true;
		opts.baseCSVDir = "lib/data/WikiTableQuestions/";
		final String filename = "csv/200-csv/0.csv";
		final TableKnowledgeGraph graph = (TableKnowledgeGraph) KnowledgeGraph.fromLispTree(LispTree.proto.parseFromString("(graph tables.TableKnowledgeGraph " + filename + ")"));
		for (final TableColumn column : graph.columns)
		{
			LogInfo.begin_track("%s (%s)", column.columnName, column.originalString);
			for (final TableCell cell : column.children)
				LogInfo.logs("%s (%s) %s", cell.properties.nameValue, cell.properties.originalString, cell.properties.metadata);
			LogInfo.end_track();
		}
	}

}
