package edu.stanford.nlp.sempre.tables.serialize;

import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.tables.TableCell;
import edu.stanford.nlp.sempre.tables.TableColumn;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.exec.Execution;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generate TSV files containing CoreNLP tags of the tables. Mandatory fields: - row: row index (-1 is the header row) - col: column index - id: unique ID of
 * the cell. - Each header cell gets a unique ID even when the contents are identical - Non-header cells get the same ID <=> they have exactly the same content
 * - content: the cell text (images and hidden spans are removed) - tokens: the cell text, tokenized - lemmaTokens: the cell text, tokenized and lemmatized -
 * posTags: the part of speech tag of each token - nerTags: the name entity tag of each token - nerValues: if the NER tag is numerical or temporal, the value of
 * that NER span will be listed here The following fields are optional: - number: interpretation as a number - For multiple numbers, the first number is
 * extracted - date: interpretation as a date - num2: the second number in the cell (useful for scores like `1-2`) - list: interpretation as a list of items -
 * listId: unique ID of list items
 *
 * @author ppasupat
 */
public class TaggedTableGenerator extends TSVGenerator implements Runnable
{

	public static void main(final String[] args)
	{
		Execution.run(args, "TaggedTableGeneratorMain", new TaggedTableGenerator(), Master.getOptionsParser());
	}

	public static final Pattern FILENAME_PATTERN = Pattern.compile("^.*/(\\d+)-csv/(\\d+).csv$");
	private LanguageAnalyzer analyzer;

	@Override
	public void run()
	{
		// Get the list of all tables
		analyzer = LanguageAnalyzer.getSingleton();
		final Path baseDir = Paths.get(TableKnowledgeGraph.opts.baseCSVDir);
		try
		{
			Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException
				{
					final Matcher matcher = FILENAME_PATTERN.matcher(file.toString());
					if (matcher.matches())
					{
						LogInfo.begin_track("Processing %s", file);
						final int batchIndex = Integer.parseInt(matcher.group(1)), dataIndex = Integer.parseInt(matcher.group(2));
						final TableKnowledgeGraph table = TableKnowledgeGraph.fromFilename(baseDir.relativize(file).toString());
						final String outDir = Execution.getFile("tagged/" + batchIndex + "-tagged/"), outFilename = new File(outDir, dataIndex + ".tagged").getPath();
						new File(outDir).mkdirs();
						out = IOUtils.openOutHard(outFilename);
						dumpTable(table);
						out.close();
						LogInfo.end_track();
					}
					return super.visitFile(file, attrs);
				}
			});
		}
		catch (final IOException e)
		{
			e.printStackTrace();
			LogInfo.fails("%s", e);
		}
	}

	private static final String[] FIELDS = new String[] { "row", "col", "id", "content", "tokens", "lemmaTokens", "posTags", "nerTags", "nerValues", "number", "date", "num2", "list", "listId", };

	@Override
	protected void dump(final String... stuff)
	{
		assert stuff.length == FIELDS.length;
		super.dump(stuff);
	}

	private void dumpTable(final TableKnowledgeGraph table)
	{
		dump(FIELDS);
		// header row
		for (int j = 0; j < table.columns.size(); j++)
			dumpColumnHeader(j, table.columns.get(j));
		// other rows
		for (int i = 0; i < table.rows.size(); i++)
			for (int j = 0; j < table.columns.size(); j++)
				dumpCell(i, j, table.rows.get(i).children.get(j));
	}

	private void dumpColumnHeader(final int j, final TableColumn column)
	{
		final String[] fields = new String[FIELDS.length];
		fields[0] = "-1";
		fields[1] = "" + j;
		fields[2] = serialize(column.relationNameValue._id);
		fields[3] = serialize(column.originalString);
		final LanguageInfo info = analyzer.analyze(column.originalString);
		fields[4] = serialize(info.tokens);
		fields[5] = serialize(info.lemmaTokens);
		fields[6] = serialize(info.posTags);
		fields[7] = serialize(info.nerTags);
		fields[8] = serialize(info.nerValues);
		fields[9] = fields[10] = fields[11] = fields[12] = fields[13] = "";
		dump(fields);
	}

	private void dumpCell(final int i, final int j, final TableCell cell)
	{
		final String[] fields = new String[FIELDS.length];
		fields[0] = "" + i;
		fields[1] = "" + j;
		fields[2] = serialize(cell.properties.nameValue._id);
		fields[3] = serialize(cell.properties.originalString);
		final LanguageInfo info = analyzer.analyze(cell.properties.originalString);
		fields[4] = serialize(info.tokens);
		fields[5] = serialize(info.lemmaTokens);
		fields[6] = serialize(info.posTags);
		fields[7] = serialize(info.nerTags);
		fields[8] = serialize(info.nerValues);
		fields[9] = serialize(new ListValue(new ArrayList<>(cell.properties.metadata.get(TableTypeSystem.CELL_NUMBER_VALUE))));
		fields[10] = serialize(new ListValue(new ArrayList<>(cell.properties.metadata.get(TableTypeSystem.CELL_DATE_VALUE))));
		fields[11] = serialize(new ListValue(new ArrayList<>(cell.properties.metadata.get(TableTypeSystem.CELL_NUM2_VALUE))));
		final ListValue parts = new ListValue(new ArrayList<>(cell.properties.metadata.get(TableTypeSystem.CELL_PART_VALUE)));
		fields[12] = serialize(parts);
		fields[13] = serializeId(parts);
		dump(fields);
	}

}
