package edu.stanford.nlp.sempre.tables.baseline;

import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureComputer;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import edu.stanford.nlp.sempre.tables.features.PhraseInfo;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compute features for BaselineParser
 *
 * @author ppasupat
 */
public class TableBaselineFeatureComputer implements FeatureComputer
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbosity = 0;
	}

	public static Options opts = new Options();

	@Override
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		if (!deriv.isRoot(ex.numTokens()))
			return;
		if (!FeatureExtractor.containsDomain("table-baseline"))
			return;
		final List<PhraseInfo> phraseInfos = PhraseInfo.getPhraseInfos(ex);
		// Find the list of all entities mentioned in the question
		final Set<String> mentionedEntities = new HashSet<>(), mentionedProperties = new HashSet<>();
		for (final PhraseInfo phraseInfo : phraseInfos)
			for (final String s : phraseInfo.fuzzyMatchedPredicates)
			{
				// s is either an ENTITY or a BINARY
				final SemType entityType = TableTypeSystem.getEntityTypeFromId(s);
				final SemType propertyType = TableTypeSystem.getPropertyTypeFromId(s);
				if (entityType != null)
					mentionedEntities.add(s);
				if (propertyType != null)
					mentionedProperties.add(s);
			}
		// Find the base cell(s)
		final TableKnowledgeGraph graph = (TableKnowledgeGraph) ex.context.graph;
		List<Value> values = ((ListValue) deriv.value).values;
		if (opts.verbosity >= 2)
			LogInfo.logs("%s", values);
		if (values.get(0) instanceof NumberValue)
			values = graph.joinSecond(TableTypeSystem.CELL_NUMBER_VALUE, values);
		else
			if (values.get(0) instanceof DateValue)
				values = graph.joinSecond(TableTypeSystem.CELL_DATE_VALUE, values);
			else
				values = new ArrayList<>(values);
		if (opts.verbosity >= 2)
			LogInfo.logs("%s", values);
		final List<String> predictedEntities = new ArrayList<>();
		for (final Value value : values)
			predictedEntities.add(((NameValue) value)._id);
		// Define features
		for (final String predicted : predictedEntities)
		{
			final String pProp = TableTypeSystem.getPropertyOfEntity(predicted);
			final List<Integer> pRows = graph.getRowsOfCellId(predicted);
			if (opts.verbosity >= 2)
				LogInfo.logs("[p] %s %s %s", predicted, pProp, pRows);
			for (final String mentioned : mentionedEntities)
			{
				final String mProp = TableTypeSystem.getPropertyOfEntity(mentioned);
				final List<Integer> mRows = graph.getRowsOfCellId(mentioned);
				if (opts.verbosity >= 2)
					LogInfo.logs("[m] %s %s %s", mentioned, mProp, mRows);
				// Same column as ENTITY + offset
				if (pProp != null && mProp != null && pProp.equals(mProp))
				{
					defineAllFeatures(deriv, "same-column", phraseInfos);
					if (pRows != null && pRows.size() == 1 && mRows != null && mRows.size() == 1)
						defineAllFeatures(deriv, "same-column;offset=" + (pRows.get(0) - mRows.get(0)), phraseInfos);
				}
				// Same row as ENTITY
				if (mRows != null && pRows != null)
					for (final int pRow : pRows)
						if (mRows.contains(pRow))
						{
							defineAllFeatures(deriv, "same-row", phraseInfos);
							break;
						}
			}
			for (final String mentioned : mentionedProperties)
			{
				// match column name BINARY
				if (opts.verbosity >= 2)
					LogInfo.logs("%s %s", pProp, mentioned);
				if (mentioned.equals(pProp))
					defineAllFeatures(deriv, "match-column-binary", phraseInfos);
			}
			// Row index (first or last)
			if (pRows != null && pRows.contains(0))
				defineAllFeatures(deriv, "first-row", phraseInfos);
			if (pRows != null && pRows.contains(graph.numRows() - 1))
				defineAllFeatures(deriv, "last-row", phraseInfos);
		}

	}

	private void defineAllFeatures(final Derivation deriv, final String name, final List<PhraseInfo> phraseInfos)
	{
		defineUnlexicalizedFeatures(deriv, name);
		defineLexicalizedFeatures(deriv, name, phraseInfos);
	}

	private void defineUnlexicalizedFeatures(final Derivation deriv, final String name)
	{
		deriv.addFeature("table-baseline", name);
	}

	private void defineLexicalizedFeatures(final Derivation deriv, final String name, final List<PhraseInfo> phraseInfos)
	{
		for (final PhraseInfo phraseInfo : phraseInfos)
			deriv.addFeature("table-baseline", "phrase=" + phraseInfo.lemmaText + ";" + name);
	}

}
