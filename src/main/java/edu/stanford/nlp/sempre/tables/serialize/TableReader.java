package edu.stanford.nlp.sempre.tables.serialize;

import com.opencsv.CSVReader;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import fig.basic.LogInfo;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Read a table in either CSV or TSV format. For CSV, this class is just a wrapper for OpenCSV. Escape sequences for CSV: - \\ => \ - \" or "" => " Each cell
 * can be quoted inside "...". Embed newlines must be quoted. For TSV, each line must represent one table row (no embed newlines). Escape sequences for TSV
 * (custom): - \n => [newline] - \\ => \ - \p => |
 *
 * @author ppasupat
 */
public class TableReader implements Closeable, Iterable<String[]>
{

	enum DataType
	{
		CSV, TSV, UNKNOWN
	}

	CSVReader csvReader = null;
	List<String[]> tsvData = null;

	public TableReader(final String filename) throws IOException
	{
		switch (guessDataType(filename))
		{
			case CSV:
				csvReader = new CSVReader(new FileReader(filename));
				break;
			case TSV:
				parseTSV(filename);
				break;
			default:
				throw new RuntimeException("Unknown data type for " + filename);
		}
	}

	private DataType guessDataType(final String filename)
	{
		if (filename.endsWith(".csv"))
			return DataType.CSV;
		else
			if (filename.endsWith(".tsv"))
				return DataType.TSV;
		// Guess from the first line of the file
		try (BufferedReader reader = new BufferedReader(new FileReader(filename)))
		{
			final String line = reader.readLine();
			if (line.contains("\t"))
				return DataType.TSV;
			else
				if (line.contains(",") || line.startsWith("\""))
					return DataType.CSV;
		}
		catch (final IOException e)
		{
			throw new RuntimeException("Unknown data type for " + filename);
		}
		return DataType.UNKNOWN;
	}

	private void parseTSV(final String filename)
	{
		try (BufferedReader reader = new BufferedReader(new FileReader(filename)))
		{
			String line;
			tsvData = new ArrayList<>();
			while ((line = reader.readLine()) != null)
			{
				final String[] fields = line.split("\t", -1); // Include trailing spaces
				for (int i = 0; i < fields.length; i++)
					fields[i] = StringNormalizationUtils.unescapeTSV(fields[i]);
				tsvData.add(fields);
			}
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public Iterator<String[]> iterator()
	{
		if (csvReader != null)
			return csvReader.iterator();
		else
			return tsvData.iterator();
	}

	@Override
	public void close() throws IOException
	{
		if (csvReader != null)
			csvReader.close();
	}

	// ============================================================
	// Test
	// ============================================================

	public static void main(final String[] args)
	{
		final String filename = "t/csv/200-csv/0.tsv";
		LogInfo.logs("%s", filename);
		try (TableReader tableReader = new TableReader(filename))
		{
			for (final String[] x : tableReader)
			{
				LogInfo.begin_track("ROW");
				for (final String y : x)
					LogInfo.logs("|%s|", y);
				LogInfo.end_track();
			}
		}
		catch (final Exception e)
		{
			e.printStackTrace();
		}
	}

}
