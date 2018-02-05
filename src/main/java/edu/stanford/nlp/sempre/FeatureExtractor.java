package edu.stanford.nlp.sempre;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.StopWatchSet;
import fig.basic.Utils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A FeatureExtractor specifies a mapping from derivations to feature vectors. [Using features] - Specify the feature domains in the featureDomains option. - If
 * the features are defined in a separate FeatureComputer class, also specify the class name in the featureComputers option. [Implementing new features] There
 * are 3 ways to implement new features: 1) Define features in SemanticFn, which is called when the SemanticFn is called. 2) Create a FeatureComputer class,
 * which is called on each sub-Derivation if the class name is specified in the featureComputers option. 3) Add a method to this class. The method is called on
 * each sub-Derivation.
 *
 * @author Percy Liang
 */
public class FeatureExtractor
{
	// Global place to specify features.
	public static class Options
	{
		@Option(gloss = "Set of feature domains to include")
		public Set<String> featureDomains = new HashSet<>();
		@Option(gloss = "Set of feature computer classes to load")
		public Set<String> featureComputers = Sets.newHashSet("DerivOpCountFeatureComputer");
		@Option(gloss = "Disable denotation features")
		public boolean disableDenotationFeatures = false;
		@Option(gloss = "Use all possible features, regardless of what featureDomains says")
		public boolean useAllFeatures = false;
		@Option(gloss = "For bigram features in paraphrased utterances, maximum distance to consider")
		public int maxBigramDistance = 3;
		@Option(gloss = "Whether or not paraphrasing and bigram features should be lexicalized")
		public boolean lexicalBigramParaphrase = true;
	}

	private final Executor executor;
	private final List<FeatureComputer> featureComputers = new ArrayList<>();

	public FeatureExtractor(final Executor executor)
	{
		this.executor = executor;
		for (final String featureComputer : opts.featureComputers)
			featureComputers.add((FeatureComputer) Utils.newInstanceHard(SempreUtils.resolveClassName(featureComputer)));
	}

	public static Options opts = new Options();

	public static boolean containsDomain(final String domain)
	{
		if (opts.disableDenotationFeatures && domain.equals("denotation"))
			return false;
		return opts.useAllFeatures || opts.featureDomains.contains(domain);
	}

	// This function is called on every sub-Derivation, so we should extract only
	// features which depend in some way on |deriv|, not just on its children.
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		StopWatchSet.begin("FeatureExtractor.extractLocal");
		extractRuleFeatures(ex, deriv);
		extractSpanFeatures(ex, deriv);
		extractDenotationFeatures(ex, deriv);
		extractDependencyFeatures(ex, deriv);
		extractWhTypeFeatures(ex, deriv);
		conjoinLemmaAndBinary(ex, deriv);
		extractBigramFeatures(ex, deriv);
		for (final FeatureComputer featureComputer : featureComputers)
			featureComputer.extractLocal(ex, deriv);
		StopWatchSet.end();
	}

	// Add an indicator for each applied rule.
	void extractRuleFeatures(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("rule"))
			return;
		if (deriv.rule != Rule.nullRule)
		{
			deriv.addFeature("rule", "fire");
			deriv.addFeature("rule", deriv.rule.toString());
		}
	}

	// Extract features on the linguistic information of the spanned (anchored) tokens.
	// (Not applicable for floating rules)
	void extractSpanFeatures(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("span") || deriv.start == -1)
			return;
		deriv.addFeature("span", "cat=" + deriv.cat + ",#tokens=" + (deriv.end - deriv.start));
		deriv.addFeature("span", "cat=" + deriv.cat + ",POS=" + ex.posTag(deriv.start) + "..." + ex.posTag(deriv.end - 1));
	}

	// Extract features on the denotation of the logical form produced.
	// (For example, number of items in the list)
	void extractDenotationFeatures(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("denotation"))
			return;
		if (!deriv.isRoot(ex.numTokens()))
			return;

		deriv.ensureExecuted(executor, ex.context);

		if (deriv.value instanceof ErrorValue)
		{
			deriv.addFeature("denotation", "error");
			return;
		}

		if (deriv.value instanceof StringValue)
		{
			if (((StringValue) deriv.value).value.equals("[]") || ((StringValue) deriv.value).value.equals("[null]"))
				deriv.addFeature("denotation", "empty");
			return;
		}

		if (deriv.value instanceof ListValue)
		{
			final ListValue list = (ListValue) deriv.value;

			if (list.values.size() == 1 && list.values.get(0) instanceof NumberValue)
			{
				final int count = getNumber(list.values.get(0));
				deriv.addFeature("denotation", "count-size" + (count <= 1 ? "=" + count : ">1"));
			}
			else
			{
				final int size = list.values.size();
				deriv.addFeature("denotation", "size" + (size < 3 ? "=" + size : ">=" + 3));
			}

		}
	}

	int getNumber(final Value value)
	{
		if (value instanceof NumberValue)
			return (int) ((NumberValue) value).value;
		if (value instanceof ListValue)
			return getNumber(((ListValue) value).values.get(0));
		throw new RuntimeException("Can't extract number from " + value);
	}

	// Add an indicator for each alignment between a syntactic dependency (produced by the
	// Stanford dependency parser) and the application of a semantic function.
	void extractDependencyFeatures(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("dependencyParse") && !containsDomain("fullDependencyParse"))
			return;
		if (deriv.rule != Rule.nullRule)
			for (final Derivation child : deriv.children)
				for (int i = child.start; i < child.end; i++)
					for (final LanguageInfo.DependencyEdge dependency : ex.languageInfo.dependencyChildren.get(i))
						if (!child.containsIndex(dependency.modifier))
						{
							final String direction = dependency.modifier > i ? "forward" : "backward";
							final String containment = deriv.containsIndex(dependency.modifier) ? "internal" : "external";
							if (containsDomain("fullDependencyParse"))
								addAllDependencyFeatures(dependency, direction, containment, deriv);
							else
								deriv.addFeature("dependencyParse", "(" + dependency.label + " " + direction + " " + containment + ") --- " + deriv.getRule().toString());
						}
	}

	private void addAllDependencyFeatures(final LanguageInfo.DependencyEdge dependency, final String direction, final String containment, final Derivation deriv)
	{
		final String[] types = { dependency.label, "*" };
		final String[] directions = { " " + direction, "" };
		final String[] containments = { " " + containment, "" };
		final String[] rules = { deriv.getRule().toString(), "" };
		for (final String typePresent : types)
			for (final String directionPresent : directions)
				for (final String containmentPresent : containments)
					for (final String rulePresent : rules)
						deriv.addFeature("fullDependencyParse", "(" + typePresent + directionPresent + containmentPresent + ") --- " + rulePresent);
	}

	// Conjunction of wh-question word and type
	// (For example, "who" should go with PERSON and not DATE)
	void extractWhTypeFeatures(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("whType"))
			return;
		if (!deriv.isRoot(ex.numTokens()))
			return;

		if (ex.posTag(0).startsWith("W"))
			deriv.addFeature("whType", "token0=" + ex.token(0) + "," + "type=" + coarseType(deriv.type.toString()));
	}

	public static final String PERSON = "fb:people.person";
	public static final String LOC = "fb:location.location";
	public static final String ORG = "fb:organization.organization";

	public static String coarseType(final String type)
	{
		final Set<String> superTypes = SemTypeHierarchy.singleton.getSupertypes(type);
		if (superTypes != null)
		{
			if (superTypes.contains(PERSON))
				return PERSON;
			if (superTypes.contains(LOC))
				return LOC;
			if (superTypes.contains(ORG))
				return ORG;
			if (superTypes.contains(CanonicalNames.NUMBER))
				return CanonicalNames.NUMBER;
			if (superTypes.contains(CanonicalNames.DATE))
				return CanonicalNames.DATE;
		}
		return "OTHER";
	}

	//used in Berant et al., 2013 and in the RL parser
	//conjoins all binaries in the logical form with all non-entity lemmas
	void conjoinLemmaAndBinary(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("lemmaAndBinaries"))
			return;
		if (!deriv.isRoot(ex.numTokens()))
			return;

		final List<String> nonEntityLemmas = new LinkedList<>();
		extractNonEntityLemmas(ex, deriv, nonEntityLemmas);
		final List<String> binaries = extractBinaries(deriv.formula);
		if (!binaries.isEmpty())
		{
			final String binariesStr = Joiner.on('_').join(binaries);
			for (final String nonEntityLemma : nonEntityLemmas)
				deriv.addFeature("lemmaAndBinaries", "nonEntitylemmas=" + nonEntityLemma + ",binaries=" + binariesStr);
		}
	}

	// Extract the utterance that the derivation generates (not necessarily the
	// one in the input utterance).
	private void extractUtterance(final Derivation deriv, final List<String> utterance)
	{
		if (deriv.rule == Rule.nullRule)
			return;
		int c = 0; // Index into children
		for (final String item : deriv.rule.rhs)
			if (Rule.isCat(item))
				extractUtterance(deriv.children.get(c++), utterance);
			else
				utterance.add(item);
	}

	//Used in Berant et., EMNLP 2013, and in the agenda RL parser
	//Extracts all content-word lemmas in the derivation tree not dominated by the category $Entity
	private void extractNonEntityLemmas(final Example ex, final Derivation deriv, final List<String> nonEntityLemmas)
	{
		if (deriv.children.size() == 0)
			for (int i = deriv.start; i < deriv.end; i++)
			{
				final String pos = ex.languageInfo.posTags.get(i);
				if ((pos.startsWith("N") || pos.startsWith("V") || pos.startsWith("W") || pos.startsWith("A") || pos.equals("IN")) && !ex.languageInfo.lemmaTokens.get(i).equals("be"))
					nonEntityLemmas.add(ex.languageInfo.lemmaTokens.get(i));
			}
		else
			for (final Derivation child : deriv.children)
				if (child.rule.lhs == null || !child.rule.lhs.equals("$Entity"))
					extractNonEntityLemmas(ex, child, nonEntityLemmas);
				else
					if (child.rule.lhs.equals("$Entity"))
						nonEntityLemmas.add("E");
	}

	//Used in Berant et al., 2013 and in agenda-based RL parser
	private List<String> extractBinaries(final Formula formula)
	{
		final List<String> res = new LinkedList<>();
		final Set<String> atomicElements = Formulas.extractAtomicFreebaseElements(formula);
		for (final String atomicElement : atomicElements)
			if (atomicElement.split("\\.").length == 3 && !atomicElement.equals("fb:type.object.type"))
				res.add(atomicElement);
		return res;
	}

	/**
	 * Add an indicator for each pair of bigrams that can be aligned from the original utterance and two (not necessarily contiguous) lemmas in the generated
	 * utterance
	 */
	private void extractBigramFeatures(final Example ex, final Derivation deriv)
	{
		if (!containsDomain("bigram"))
			return;
		if (!deriv.cat.equals(Rule.rootCat))
			return;
		final LanguageInfo derivInfo = LanguageAnalyzer.getSingleton().analyze(deriv.canonicalUtterance);
		final List<String> derivLemmas = derivInfo.lemmaTokens;
		final List<String> exLemmas = ex.languageInfo.lemmaTokens;
		final Map<Integer, Integer> bigramCounts = new HashMap<>();
		for (int i = 0; i < exLemmas.size() - 1; i++)
			for (int j = 0; j < derivLemmas.size() - 1; j++)
				if (derivLemmas.get(j).equals(exLemmas.get(i)))
					// Consider bigrams separated by up to maxBigramDistance in generated utterance
					for (int k = 1; j + k < derivLemmas.size() && k <= opts.maxBigramDistance; k++)
						if (derivLemmas.get(j + k).equals(exLemmas.get(i + 1)))
							if (opts.lexicalBigramParaphrase)
								deriv.addFeature("bigram", exLemmas.get(i) + "," + exLemmas.get(i + 1) + " - " + k);
							else
								MapUtils.incr(bigramCounts, k, 1);
		if (!opts.lexicalBigramParaphrase)
			for (final Integer dist : bigramCounts.keySet())
				deriv.addFeature("bigram", "distance " + dist + " - " + bigramCounts.get(dist));
	}

	// Joins arrayList of strings into string
	String join(final List<String> l, final String delimiter)
	{
		final StringBuilder sb = new StringBuilder(l.get(0));
		for (int i = 1; i < l.size(); i++)
		{
			sb.append(delimiter);
			sb.append(l.get(i));
		}
		return sb.toString();
	}
}
