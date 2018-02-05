package edu.stanford.nlp.sempre.tables.alter;

import edu.stanford.nlp.sempre.DescriptionValue;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.TargetValuePreprocessor;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read aggregated Turked results from TSV file. File format: - HIT ID - Example ID - Alter table index - Flag (A2/A3 = agreed on an answer, B2/B3 = agreed on
 * "no answer", X = disagreed) - Agreed answer (blank for B or X) - Individual answers; come in pairs of (worker id, answer) -- not used here
 *
 * @author ppasupat
 */
public class AggregatedTurkData
{

	/**
	 * Map from Example ID -> altered table index -> agreed response
	 */
	Map<String, Map<Integer, Value>> data = new HashMap<>();

	/**
	 * List of all tables used regardless of agreement
	 */
	Map<String, List<Integer>> allTurkedTables = new HashMap<>();

	public AggregatedTurkData()
	{
	}

	public AggregatedTurkData(final String filename)
	{
		addFromFile(filename);
	}

	public AggregatedTurkData addFromFile(final String filename)
	{
		LogInfo.begin_track("Reading Turked data from %s", filename);
		try (BufferedReader reader = new BufferedReader(new FileReader(filename)))
		{
			int count = 0;
			String line;
			while ((line = reader.readLine()) != null)
			{
				final String[] tokens = line.split("\t");
				final String exampleId = tokens[1];
				final int alteredTableIndex = Integer.parseInt(tokens[2]);
				MapUtils.addToList(allTurkedTables, exampleId, alteredTableIndex);
				final char flagCode = tokens[3].charAt(0);
				if (flagCode != 'A' && flagCode != 'B')
					continue;
				final String response = tokens[4];
				final Value canonicalized = toValue(response);
				MapUtils.set(data, exampleId, alteredTableIndex, canonicalized);
				count++;
			}
			LogInfo.logs("Read %d records", count);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
		LogInfo.end_track();
		return this;
	}

	public Map<Integer, Value> get(final String exampleId)
	{
		return data.get(exampleId);
	}

	public Value get(final String exampleId, final int alteredTableIndex)
	{
		return MapUtils.get(data, exampleId, alteredTableIndex, null);
	}

	/**
	 * Get all Turked tables regardless of whether the answer is agreed upon.
	 */
	public List<Integer> getAllTurkedTables(final String exampleId)
	{
		return allTurkedTables.get(exampleId);
	}

	/**
	 * Return canonicalized Value.
	 */
	private Value toValue(final String response)
	{
		if (response.isEmpty())
			// TODO: Distinguish empty list from ERROR
			return ValueCanonicalizer.ERROR;
		final List<Value> values = new ArrayList<>();
		for (final String x : response.split("\\|"))
			values.add(new DescriptionValue(StringNormalizationUtils.unescapeTSV(x)));
		return TargetValuePreprocessor.getSingleton().preprocess(new ListValue(values), null);
	}

}
