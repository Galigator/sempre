package edu.stanford.nlp.sempre.tables.features;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureComputer;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.tables.features.PredicateInfo.PredicateType;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extract features based on (phrase, predicate) pairs. - |phrase| is an n-gram from the utterance (usually n = 1) - |predicate| is a predicate (LispTree leaf)
 * from the formula Example: fb:cell_name.barack_obama, fb:row.row.name, argmax Properties of phrases: POS tags, length, word shapes, ... Properties of
 * predicates: category (entity / binary / keyword), ... Properties of alignment: exact match, prefix match, suffix match, string contains, ...
 *
 * @author ppasupat
 */
public class PhrasePredicateFeatureComputer implements FeatureComputer
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
		@Option(gloss = "Define features on partial derivations as well")
		public boolean defineOnPartialDerivs = true;
		@Option(gloss = "Also define features on prefix and suffix matches")
		public boolean usePrefixSuffixMatch = true;
		@Option(gloss = "Also define features with POS tags")
		public boolean usePosFeatures = true;
		@Option(gloss = "Define unlexicalized phrase-predicate features")
		public boolean unlexicalizedPhrasePredicate = true;
		@Option(gloss = "Define lexicalized phrase-predicate features")
		public boolean lexicalizedPhrasePredicate = true;
		@Option(gloss = "Maximum ngram length for lexicalize all pair features")
		public int maxNforLexicalizeAllPairs = Integer.MAX_VALUE;
		@Option(gloss = "phrase-category: Weight threshold")
		public double phraseCategoryWeightThreshold = 0.8;
		@Option(gloss = "phrase-category: Use binary features instead of continuous ones")
		public boolean phraseCategoryBinary = true;

	}

	public static Options opts = new Options();

	public final int maxNforLexicalizeAllPairs;

	public PhrasePredicateFeatureComputer()
	{
		maxNforLexicalizeAllPairs = Math.min(opts.maxNforLexicalizeAllPairs, PhraseInfo.opts.maxPhraseLength);
	}

	@Override
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		if (!(FeatureExtractor.containsDomain("phrase-predicate") || FeatureExtractor.containsDomain("phrase-formula") || FeatureExtractor.containsDomain("phrase-category")))
			return;
		// Only compute features at the root, except when the partial option is set.
		if (!opts.defineOnPartialDerivs && !deriv.isRoot(ex.numTokens()))
			return;
		final List<PhraseInfo> phraseInfos = PhraseInfo.getPhraseInfos(ex);
		final List<PredicateInfo> predicateInfos = PredicateInfo.getPredicateInfos(ex, deriv);
		if (opts.verbose >= 2)
		{
			LogInfo.logs("Example: %s", ex.utterance);
			LogInfo.logs("Phrases: %s", phraseInfos);
			LogInfo.logs("Derivation: %s", deriv);
			LogInfo.logs("Predicates: %s", predicateInfos);
		}
		if (FeatureExtractor.containsDomain("phrase-predicate") || FeatureExtractor.containsDomain("phrase-category"))
			if (opts.defineOnPartialDerivs)
			{
				deriv.getTempState().put("p-p", new ArrayList<>(predicateInfos));
				// Subtract predicates from children
				final Map<PredicateInfo, Integer> predicateInfoCounts = new HashMap<>();
				for (final PredicateInfo predicateInfo : predicateInfos)
					MapUtils.incr(predicateInfoCounts, predicateInfo);
				if (deriv.children != null)
					for (final Derivation child : deriv.children)
					{
						@SuppressWarnings("unchecked")
						final List<PredicateInfo> childPredicateInfos = (List<PredicateInfo>) child.getTempState().get("p-p");
						for (final PredicateInfo predicateInfo : childPredicateInfos)
							MapUtils.incr(predicateInfoCounts, predicateInfo, -1);
					}
				for (final PhraseInfo phraseInfo : phraseInfos)
					for (final Map.Entry<PredicateInfo, Integer> entry : predicateInfoCounts.entrySet())
						if (entry.getValue() != 0)
							extractMatch(ex, deriv, phraseInfo, entry.getKey(), entry.getValue());
			}
			else
				for (final PhraseInfo phraseInfo : phraseInfos)
					for (final PredicateInfo predicateInfo : predicateInfos)
						extractMatch(ex, deriv, phraseInfo, predicateInfo, 1);
		if (FeatureExtractor.containsDomain("missing-predicate"))
			extractMissing(ex, deriv, phraseInfos, predicateInfos);
	}

	// ============================================================
	// Matching
	// ============================================================

	private void extractMatch(final Example ex, final Derivation deriv, final PhraseInfo phraseInfo, final PredicateInfo predicateInfo, final double factor)
	{
		if (predicateInfo.originalString != null)
			extractMatch(ex, deriv, phraseInfo, phraseInfo.lemmaText, "", predicateInfo, predicateInfo.originalString, "(o)", factor);
		else
			extractMatch(ex, deriv, phraseInfo, phraseInfo.lemmaText, "", predicateInfo, predicateInfo.predicate, "(i)", factor);
	}

	private void extractMatch(final Example ex, final Derivation deriv, final PhraseInfo phraseInfo, final String phraseString, final String phraseType, final PredicateInfo predicateInfo, final String predicateString, final String predicateType, final double factor)
	{
		if (FeatureExtractor.containsDomain("phrase-predicate") && opts.unlexicalizedPhrasePredicate)
			if (phraseString.equals(predicateString))
				defineFeatures(ex, deriv, phraseInfo, predicateInfo, phraseType + "=" + predicateType, phraseString, predicateString, factor);
			else
				if (opts.usePrefixSuffixMatch)
				{
					if (predicateString.startsWith(phraseString))
						defineFeatures(ex, deriv, phraseInfo, predicateInfo, "*_" + phraseType + "=" + predicateType, phraseString, predicateString, factor);
					if (predicateString.endsWith(phraseString))
						defineFeatures(ex, deriv, phraseInfo, predicateInfo, "_*" + phraseType + "=" + predicateType, phraseString, predicateString, factor);
					if (phraseString.startsWith(predicateString))
						defineFeatures(ex, deriv, phraseInfo, predicateInfo, phraseType + "=_*" + predicateType, phraseString, predicateString, factor);
					if (phraseString.endsWith(predicateString))
						defineFeatures(ex, deriv, phraseInfo, predicateInfo, phraseType + "=*_" + predicateType, phraseString, predicateString, factor);
				}
		if (FeatureExtractor.containsDomain("phrase-predicate") && opts.lexicalizedPhrasePredicate && phraseInfo.end - phraseInfo.start <= maxNforLexicalizeAllPairs && (!PhraseInfo.opts.forbidBorderStopWordInLexicalizedFeatures || !phraseInfo.isBorderStopWord))
			deriv.addFeature("p-p", phraseType + phraseString + ";" + predicateType + predicateString, factor);
		if (FeatureExtractor.containsDomain("phrase-category") && predicateInfo.type == PredicateType.BINARY && (!PhraseInfo.opts.forbidBorderStopWordInLexicalizedFeatures || !phraseInfo.isBorderStopWord))
		{
			final ColumnCategoryInfo catInfo = ColumnCategoryInfo.getSingleton();
			final List<Pair<String, Double>> categories = catInfo.get(ex, predicateInfo.predicate);
			if (categories != null)
				for (final Pair<String, Double> pair : categories)
					if (pair.getSecond() >= opts.phraseCategoryWeightThreshold)
						if (opts.phraseCategoryBinary)
							deriv.addFeature("p-c", phraseType + phraseString + ";" + pair.getFirst());
						else
							deriv.addFeature("p-c", phraseType + phraseString + ";" + pair.getFirst(), pair.getSecond());
		}
	}

	private void defineFeatures(final Example ex, final Derivation deriv, final PhraseInfo phraseInfo, final PredicateInfo predicateInfo, final String featurePrefix, final String phraseString, final String predicateString, final double factor)
	{
		defineFeatures(ex, deriv, phraseInfo, predicateInfo, featurePrefix, factor);
		if (opts.usePosFeatures)
			defineFeatures(ex, deriv, phraseInfo, predicateInfo, featurePrefix + "," + phraseInfo.canonicalPosSeq, factor);
	}

	private void defineFeatures(final Example ex, final Derivation deriv, final PhraseInfo phraseInfo, final PredicateInfo predicateInfo, final String featurePrefix, final double factor)
	{
		if (opts.verbose >= 2)
			LogInfo.logs("defineFeatures: %s %s %s %s", featurePrefix, phraseInfo, predicateInfo, predicateInfo.type);
		deriv.addFeature("p-p", featurePrefix, factor);
		deriv.addFeature("p-p", featurePrefix + "," + predicateInfo.type, factor);
	}

	// ============================================================
	// Missing predicate features
	// ============================================================

	private void extractMissing(final Example ex, final Derivation deriv, final List<PhraseInfo> phraseInfos, final List<PredicateInfo> predicateInfos)
	{
		// Only makes sense at the root
		if (!deriv.isRoot(ex.numTokens()))
			return;
		// Get the list of all relevant predicates
		final Set<String> relevantPredicates = new HashSet<>();
		for (final PredicateInfo predicateInfo : predicateInfos)
			if (predicateInfo.type == PredicateType.BINARY || predicateInfo.type == PredicateType.ENTITY)
			{
				String predicate = predicateInfo.predicate;
				if (predicate.charAt(0) == '!')
					predicate = predicate.substring(1);
				relevantPredicates.add(predicate);
			}
		// See which predicates are missing!
		final Set<String> missingPredicates = new HashSet<>();
		for (final PhraseInfo phraseInfo : phraseInfos)
		{
			if (phraseInfo.fuzzyMatchedPredicates == null)
				continue;
			for (final String predicate : phraseInfo.fuzzyMatchedPredicates)
				if (!relevantPredicates.contains(predicate))
				{
					missingPredicates.add(predicate);
					missingPredicates.add("type=" + PredicateInfo.inferType(predicate));
				}
		}
		if (opts.verbose >= 2)
		{
			LogInfo.logs("have %s", relevantPredicates);
			LogInfo.logs("missing %s", missingPredicates);
		}
		for (final String missing : missingPredicates)
			deriv.addFeature("m-p", missing);
	}

}
