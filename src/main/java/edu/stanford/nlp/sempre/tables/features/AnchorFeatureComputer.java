package edu.stanford.nlp.sempre.tables.features;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureComputer;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.FuzzyMatchFn;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import edu.stanford.nlp.sempre.tables.TableCell;
import edu.stanford.nlp.sempre.tables.TableColumn;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import java.util.HashSet;
import java.util.Set;

public class AnchorFeatureComputer implements FeatureComputer
{

	@Override
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		if (!FeatureExtractor.containsDomain("anchored-entity"))
			return;
		if (!(deriv.rule.sem instanceof FuzzyMatchFn))
			return;
		final FuzzyMatchFn sem = (FuzzyMatchFn) deriv.rule.sem;
		if (sem.getMatchAny() || sem.getMode() != FuzzyMatchFn.FuzzyMatchFnMode.ENTITY)
			return;
		final String phrase = ((StringValue) ((ValueFormula<?>) deriv.child(0).formula).value).value;
		final NameValue predicate = (NameValue) ((ValueFormula<?>) deriv.formula).value;
		final TableKnowledgeGraph graph = (TableKnowledgeGraph) ex.context.graph;
		extractMatchingFeatures(graph, deriv, phrase, predicate);
	}

	private void extractMatchingFeatures(final TableKnowledgeGraph graph, final Derivation deriv, final String phrase, final NameValue predicate)
	{
		String predicateString = graph.getOriginalString(predicate);
		//LogInfo.logs("%s -> %s = %s", phrase, predicate, predicateString);
		predicateString = StringNormalizationUtils.simpleNormalize(predicateString).toLowerCase();
		if (predicateString.equals(phrase))
			deriv.addFeature("a-e", "exact");
		//LogInfo.logs("%s %s exact", phrase, predicateString);
		else
			if (predicateString.startsWith(phrase + " "))
				deriv.addFeature("a-e", "prefix");
			//LogInfo.logs("%s %s prefix", phrase, predicateString);
			else
				if (predicateString.endsWith(" " + phrase))
					deriv.addFeature("a-e", "suffix");
				//LogInfo.logs("%s %s suffix", phrase, predicateString);
				else
					if (predicateString.contains(" " + phrase + " "))
						deriv.addFeature("a-e", "substring");
					//LogInfo.logs("%s %s substring", phrase, predicateString);
					else
						deriv.addFeature("a-e", "other");
		//LogInfo.logs("%s %s other", phrase, predicateString);
		// Does the phrase match other cells?
		final Set<String> matches = new HashSet<>();
		for (final TableColumn column : graph.columns)
			for (final TableCell cell : column.children)
			{
				final String s = StringNormalizationUtils.simpleNormalize(cell.properties.originalString).toLowerCase();
				if (s.contains(phrase) && !cell.properties.id.equals(predicate.id))
					matches.add(s);
			}
		//LogInfo.logs(">> %s", matches);
		if (matches.size() == 0)
			deriv.addFeature("a-e", "unique");
		else
			if (matches.size() < 3)
				deriv.addFeature("a-e", "multiple;" + matches.size());
			else
				deriv.addFeature("a-e", "multiple;>=3");
	}

}
