package edu.stanford.nlp.sempre.tables.features;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureComputer;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extract features based on (phrase, denotation) pairs. Intuition: "when" usually matches a date, which "how many" usually matches a number.
 *
 * @author ppasupat
 */
public class PhraseDenotationFeatureComputer implements FeatureComputer
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	@Override
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		if (!(FeatureExtractor.containsDomain("custom-denotation") || FeatureExtractor.containsDomain("phrase-denotation") || FeatureExtractor.containsDomain("headword-denotation")))
			return;
		// Only compute features at the root.
		if (!deriv.isRoot(ex.numTokens()))
			return;
		final Collection<String> denotationTypes = tableTypes(deriv);
		extractCustomDenotationFeatures(ex, deriv, denotationTypes);
		extractPhraseDenotationFeatures(ex, deriv, denotationTypes);
		extractHeadwordDenotationFeatures(ex, deriv, denotationTypes);
	}

	public static Collection<String> tableTypes(final Derivation deriv)
	{
		final Set<String> denotationTypes = new HashSet<>();
		// Type based on SemType
		populateSemType("", deriv.type, denotationTypes);
		// Look for the type under the first cell property
		final Formula formula = deriv.formula;
		if (formula instanceof JoinFormula)
		{
			final JoinFormula join = (JoinFormula) formula;
			final String property = getCellProperty(join.relation);
			if (property != null)
				populateSemType(property + "/", TypeInference.inferType(join.child), denotationTypes);
		}
		if (denotationTypes.isEmpty())
			denotationTypes.add("OTHER");
		return denotationTypes;
	}

	private static void populateSemType(final String prefix, final SemType type, final Collection<String> denotationTypes)
	{
		final LispTree tree = type.toLispTree();
		if (tree.isLeaf())
			denotationTypes.add(prefix + tree.value);
		else
			for (final LispTree subtree : tree.children)
			{
				if (!subtree.isLeaf())
					continue;
				if (subtree.value.startsWith(TableTypeSystem.CELL_SPECIFIC_TYPE_PREFIX))
				{
					denotationTypes.add(prefix + subtree.value);
					denotationTypes.add(prefix + TableTypeSystem.CELL_GENERIC_TYPE);
				}
			}
	}

	private static String getCellProperty(final Formula formula)
	{
		final LispTree tree = formula.toLispTree();
		if (tree.isLeaf())
		{
			final String value = tree.value;
			if (value.charAt(0) == '!' && value.substring(1).startsWith(TableTypeSystem.CELL_PROPERTY_NAME_PREFIX))
				return value;
		}
		else
			if ("reverse".equals(tree.child(0).value) && tree.child(1).value.startsWith(TableTypeSystem.CELL_PROPERTY_NAME_PREFIX))
				return "!" + tree.child(1).value;
		return null;
	}

	// ============================================================
	// Custom Denotation Features
	// ============================================================

	private void extractCustomDenotationFeatures(final Example ex, final Derivation deriv, final Collection<String> denotationTypes)
	{
		if (!FeatureExtractor.containsDomain("custom-denotation"))
			return;

		if (deriv.value instanceof ErrorValue)
		{
			deriv.addFeature("custom-denotation", "error");
			return;
		}
		else
			if (deriv.value instanceof ListValue)
			{
				final ListValue list = (ListValue) deriv.value;
				final int size = list.values.size();
				deriv.addFeature("custom-denotation", "size" + (size < 3 ? "=" + size : ">=" + 3));
				if (size == 1)
				{
					final Value value = list.values.get(0);
					if (value instanceof NumberValue)
					{
						final double number = ((NumberValue) value)._value;
						deriv.addFeature("custom-denotation", "number" + (number > 0 ? ">0" : number == 0 ? "=0" : "<0"));
						deriv.addFeature("custom-denotation", "number" + ((int) number == number ? "-int" : "-frac"));
					}
				}
			}
	}

	// ============================================================
	// Phrase - Denotation
	// ============================================================

	private void extractPhraseDenotationFeatures(final Example ex, final Derivation deriv, final Collection<String> denotationTypes)
	{
		if (!FeatureExtractor.containsDomain("phrase-denotation"))
			return;
		final List<PhraseInfo> phraseInfos = PhraseInfo.getPhraseInfos(ex);
		if (opts.verbose >= 2)
			LogInfo.logs("%s %s %s", deriv.value, deriv.type, denotationTypes);
		for (final String denotationType : denotationTypes)
		{
			for (final PhraseInfo phraseInfo : phraseInfos)
			{
				if (PhraseInfo.opts.forbidBorderStopWordInLexicalizedFeatures && phraseInfo.isBorderStopWord)
					continue;
				deriv.addFeature("p-d", phraseInfo.lemmaText + ";" + denotationType);
			}
			// Check original column text
			final String[] tokens = denotationType.split("/");
			final String actualType = tokens[tokens.length - 1], suffix = tokens.length == 1 ? "" : "(" + tokens[0] + ")";
			String originalColumn;
			if ((originalColumn = PredicateInfo.getOriginalString(actualType, ex)) != null)
			{
				originalColumn = PredicateInfo.getLemma(originalColumn);
				for (final PhraseInfo phraseInfo : phraseInfos)
					if (phraseInfo.lemmaText.equals(originalColumn))
					{
						if (opts.verbose >= 2)
							LogInfo.logs("%s %s %s %s", phraseInfo, actualType, originalColumn, Arrays.asList(tokens));
						deriv.addFeature("p-d", "=" + suffix);
					}
			}
		}
	}

	// ============================================================
	// Headword - Denotation
	// ============================================================

	private void extractHeadwordDenotationFeatures(final Example ex, final Derivation deriv, final Collection<String> denotationTypes)
	{
		if (!FeatureExtractor.containsDomain("headword-denotation"))
			return;
		final HeadwordInfo headwordInfo = HeadwordInfo.getHeadwordInfo(ex);
		if (headwordInfo.questionWord.isEmpty() && headwordInfo.headword.isEmpty())
			return;
		if (opts.verbose >= 2)
			LogInfo.logs("%s [%s] | %s %s %s", ex.utterance, headwordInfo, deriv.value, deriv.type, denotationTypes);
		for (final String denotationType : denotationTypes)
		{
			deriv.addFeature("h-d", headwordInfo + ";" + denotationType);
			deriv.addFeature("h-d", headwordInfo.questionWordTuple() + ";" + denotationType);
			deriv.addFeature("h-d", headwordInfo.headwordTuple() + ";" + denotationType);
			// Check original column text
			final String[] tokens = denotationType.split("/");
			final String actualType = tokens[tokens.length - 1], suffix = tokens.length == 1 ? "" : "(" + tokens[0] + ")";
			String originalColumn;
			if ((originalColumn = PredicateInfo.getOriginalString(actualType, ex)) != null)
			{
				originalColumn = PredicateInfo.getLemma(originalColumn);
				if (headwordInfo.headword.equals(originalColumn))
				{
					if (opts.verbose >= 2)
						LogInfo.logs("%s %s %s %s", headwordInfo, actualType, originalColumn, Arrays.asList(tokens));
					deriv.addFeature("h-d", "=" + suffix);
					deriv.addFeature("h-d", headwordInfo.questionWordTuple() + "=" + suffix);
				}
			}
		}
	}

}
