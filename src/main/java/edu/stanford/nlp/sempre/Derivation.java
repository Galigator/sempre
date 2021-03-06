package edu.stanford.nlp.sempre;

import fig.basic.Evaluation;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.NumUtils;
import fig.basic.Option;
import fig.basic.StopWatchSet;
import fig.basic.Utils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Derivation corresponds to the production of a (partial) logical form |formula| from a span of the utterance [start, end). Contains the formula and what was
 * used to produce it (like a search state). Each derivation is created by a grammar rule and has some features and a score.
 *
 * @author Percy Liang
 */
public class Derivation implements SemanticFn.Callable, HasScore
{
	public static class Options
	{
		@Option(gloss = "When printing derivations, to show values (could be quite verbose)")
		public boolean showValues = true;
		@Option(gloss = "When printing derivations, to show the first value (ignored when showValues is set)")
		public boolean showFirstValue = false;
		@Option(gloss = "When printing derivations, to show types")
		public boolean showTypes = true;
		@Option(gloss = "When printing derivations, to show rules")
		public boolean showRules = false;
		@Option(gloss = "When printing derivations, to show canonical utterance")
		public boolean showUtterance = false;
		@Option(gloss = "When printing derivations, show the category")
		public boolean showCat = false;
		@Option(gloss = "When executing, show formulae (for debugging)")
		public boolean showExecutions = false;
		@Option(gloss = "Pick the comparator used to sort derivations")
		public String derivComparator = "ScoredDerivationComparator";
		@Option(gloss = "bonus score for being all anchored")
		public double anchoredBonus = 0.0;
	}

	public static Options opts = new Options();

	//// Basic fields: created by the constructor.

	// Span that the derivation is built over
	public final String cat;
	public final int start;
	public final int end;

	// Floating cell information
	// TODO(yushi): make fields final
	public String canonicalUtterance;
	public boolean allAnchored = true;
	private int[] numAnchors; // Number of times each token was anchored

	/**
	 * Information for grammar induction. For each descendant derivation of the body, this class tracks where and what in the head it matches GrammarInfo.start,
	 * GrammarInfo.end refer to matching positions in the head, as opposed to the body
	 * 
	 * @author sidaw
	 **/
	public class GrammarInfo
	{
		public boolean anchored = false;
		public boolean matched = false;
		public int _start = -1, _end = -1;
		public Formula _formula;
		public List<Derivation> matches = new ArrayList<>();
	}

	public GrammarInfo grammarInfo = new GrammarInfo();

	// If this derivation is composed of other derivations
	public final Rule rule; // Which rule was used to produce this derivation?  Set to nullRule if not.
	public final List<Derivation> children; // Corresponds to the RHS of the rule.

	//// SemanticFn fields: read/written by SemanticFn.
	// Note: SemanticFn should only depend on Formula and the Freebase type
	// information.  This could be its own class, but expose more right now to
	// be more flexible.

	public final Formula formula; // Logical form produced by this derivation
	public final SemType type; // Type corresponding to that logical form

	//// Fields produced by feature extractor, evaluation, etc.

	private List<String> localChoices; // Just for printing/debugging.

	// TODO(pliang): make fields private

	// Information for scoring
	private final FeatureVector localFeatureVector; // Features
	double score = Double.NaN; // Weighted combination of features
	double prob = Double.NaN; // Probability (normalized exp of score).

	// Used during parsing (by FeatureExtractor, SemanticFn) to cache arbitrary
	// computation across different sub-Derivations.
	// Convention:
	// - use the featureDomain, FeatureComputer or SemanticFn as the key.
	// - the value is whatever the FeatureExtractor needs.
	// This information should be set to null after parsing is done.
	private Map<String, Object> tempState;

	// What the formula evaluates to (optionally set later; only non-null for the root Derivation)
	public Value value;
	public Evaluation executorStats;

	// Number in [0, 1] denoting how correct the value is.
	public double compatibility = Double.NaN;

	// Miscellaneous statistics
	int maxBeamPosition = -1; // Lowest position that this tree or any of its children is on the beam (after sorting)
	int maxUnsortedBeamPosition = -1; // Lowest position that this tree or any of its children is on the beam (before sorting)
	int preSortBeamPosition = -1;
	int postSortBeamPosition = -1;

	// Cache the hash code
	int hashCode = -1;

	// Each derivation that gets created gets a unique ID in increasing order so that
	// we can break ties consistently for reproducible results.
	long creationIndex;
	public static long numCreated = 0; // Incremented for each derivation we create.
	@SuppressWarnings("unchecked")
	public static final Comparator<Derivation> derivScoreComparator = (Comparator<Derivation>) Utils.newInstanceHard(SempreUtils.resolveClassName("Derivation$" + opts.derivComparator));

	public static final List<Derivation> emptyList = Collections.emptyList();

	// A Derivation is built from

	/** Builder for everyone. */
	public static class Builder
	{
		private String cat;
		private int start;
		private int end;
		private Rule rule;
		private List<Derivation> children;
		private Formula formula;
		private SemType type;
		private FeatureVector localFeatureVector = new FeatureVector();
		private double score = Double.NaN;
		private Value value;
		private Evaluation executorStats;
		private double compatibility = Double.NaN;
		private double prob = Double.NaN;
		private String canonicalUtterance = "";

		public Builder cat(final String cat_)
		{
			cat = cat_;
			return this;
		}

		public Builder start(final int start_)
		{
			start = start_;
			return this;
		}

		public Builder end(final int end_)
		{
			end = end_;
			return this;
		}

		public Builder rule(final Rule rule_)
		{
			rule = rule_;
			return this;
		}

		public Builder children(final List<Derivation> children_)
		{
			children = children_;
			return this;
		}

		public Builder formula(final Formula formula_)
		{
			formula = formula_;
			return this;
		}

		public Builder type(final SemType type_)
		{
			type = type_;
			return this;
		}

		public Builder localFeatureVector(final FeatureVector localFeatureVector_)
		{
			localFeatureVector = localFeatureVector_;
			return this;
		}

		public Builder score(final double score_)
		{
			score = score_;
			return this;
		}

		public Builder value(final Value value_)
		{
			value = value_;
			return this;
		}

		public Builder executorStats(final Evaluation executorStats_)
		{
			executorStats = executorStats_;
			return this;
		}

		public Builder compatibility(final double compatibility_)
		{
			compatibility = compatibility_;
			return this;
		}

		public Builder prob(final double prob_)
		{
			prob = prob_;
			return this;
		}

		public Builder canonicalUtterance(final String canonicalUtterance_)
		{
			canonicalUtterance = canonicalUtterance_;
			return this;
		}

		public Builder withStringFormulaFrom(final String value_)
		{
			formula = new ValueFormula<>(new StringValue(value_));
			type = SemType.stringType;
			return this;
		}

		public Builder withFormulaFrom(final Derivation deriv_)
		{
			formula = deriv_.formula;
			type = deriv_.type;
			return this;
		}

		public Builder withCallable(final SemanticFn.Callable c)
		{
			cat = c.getCat();
			start = c.getStart();
			end = c.getEnd();
			rule = c.getRule();
			children = c.getChildren();
			return this;
		}

		public Builder withAllFrom(final Derivation deriv)
		{
			cat = deriv.cat;
			start = deriv.start;
			end = deriv.end;
			rule = deriv.rule;
			children = deriv.children == null ? null : new ArrayList<>(deriv.children);
			formula = deriv.formula;
			type = deriv.type;
			localFeatureVector = deriv.localFeatureVector;
			score = deriv.score;
			value = deriv.value;
			executorStats = deriv.executorStats;
			compatibility = deriv.compatibility;
			prob = deriv.prob;
			canonicalUtterance = deriv.canonicalUtterance;
			return this;
		}

		public Derivation createDerivation()
		{
			return new Derivation(cat, start, end, rule, children, formula, type, localFeatureVector, score, value, executorStats, compatibility, prob, canonicalUtterance);
		}
	}

	Derivation(final String cat_, final int start_, final int end_, final Rule rule_, final List<Derivation> children_, //
			final Formula formula_, final SemType type_, final FeatureVector localFeatureVector_, final double score_, //
			final Value value_, final Evaluation executorStats_, final double compatibility_, final double prob_, //
			final String canonicalUtterance_)
	{
		cat = cat_;
		start = start_;
		end = end_;
		rule = rule_;
		children = children_;
		formula = formula_;
		type = type_;
		localFeatureVector = localFeatureVector_;
		score = score_;
		value = value_;
		executorStats = executorStats_;
		compatibility = compatibility_;
		prob = prob_;
		canonicalUtterance = canonicalUtterance_;
		creationIndex = numCreated++;
	}

	public Formula getFormula()
	{
		return formula;
	}

	@Override
	public double getScore()
	{
		return score;
	}

	public double getProb()
	{
		return prob;
	}

	public double getCompatibility()
	{
		return compatibility;
	}

	@Override
	public List<Derivation> getChildren()
	{
		return children;
	}

	public Value getValue()
	{
		return value;
	}

	public boolean isFeaturizedAndScored()
	{
		return !Double.isNaN(score);
	}

	public boolean isExecuted()
	{
		return value != null;
	}

	public int getMaxBeamPosition()
	{
		return maxBeamPosition;
	}

	@Override
	public String getCat()
	{
		return cat;
	}

	@Override
	public int getStart()
	{
		return start;
	}

	@Override
	public int getEnd()
	{
		return end;
	}

	public boolean containsIndex(final int i)
	{
		return i < end && i >= start;
	}

	@Override
	public Rule getRule()
	{
		return rule;
	}

	public Evaluation getExecutorStats()
	{
		return executorStats;
	}

	public FeatureVector getLocalFeatureVector()
	{
		return localFeatureVector;
	}

	@Override
	public Derivation child(final int i)
	{
		return children.get(i);
	}

	@Override
	public String childStringValue(final int i)
	{
		return Formulas.getString(children.get(i).formula);
	}

	// Return whether |deriv| is built over the root Derivation.
	public boolean isRoot(final int numTokens)
	{
		return cat.equals(Rule.rootCat) && (start == 0 && end == numTokens || start == -1);
	}

	// Return whether |deriv| has root category (for floating parser)
	public boolean isRootCat()
	{
		return cat.equals(Rule.rootCat);
	}

	// Functions that operate on features.
	public void addFeature(final String domain, final String name)
	{
		addFeature(domain, name, 1);
	}

	public void addFeature(final String domain, final String name, final double value_)
	{
		localFeatureVector.add(domain, name, value_);
	}

	public void addHistogramFeature(final String domain, final String name, final double value_, final int initBinSize, final int numBins, final boolean exp)
	{
		localFeatureVector.addHistogram(domain, name, value_, initBinSize, numBins, exp);
	}

	public void addFeatureWithBias(final String domain, final String name, final double value_)
	{
		localFeatureVector.addWithBias(domain, name, value_);
	}

	public void addFeatures(final FeatureVector fv)
	{
		localFeatureVector.add(fv);
	}

	public double localScore(final Params params)
	{
		return localFeatureVector.dotProduct(params) + (allAnchored() ? opts.anchoredBonus : 0.0);
	}

	// SHOULD NOT BE USED except during test time if the memory is desperately needed.
	public void clearFeatures()
	{
		localFeatureVector.clear();
	}

	/**
	 * Recursively compute the score for each node in derivation. Update |score| field as well as return its value.
	 */
	public double computeScore(final Params params)
	{
		score = localScore(params);
		if (children != null)
			for (final Derivation child : children)
				score += child.computeScore(params);
		return score;
	}

	/**
	 * Same as |computeScore()| but without recursion (assumes children are already scored).
	 */
	public double computeScoreLocal(final Params params)
	{
		score = localScore(params);
		if (children != null)
			for (final Derivation child : children)
				score += child.score;
		return score;
	}

	// If we haven't executed the formula associated with this derivation, then
	// execute it!
	public void ensureExecuted(final Executor executor, final ContextValue context)
	{
		if (isExecuted())
			return;
		StopWatchSet.begin("Executor.execute");
		if (opts.showExecutions)
			LogInfo.logs("%s - %s", canonicalUtterance, formula);
		final Executor.Response response = executor.execute(formula, context);
		StopWatchSet.end();
		value = response.value;
		executorStats = response.stats;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("derivation");
		if (formula != null)
			tree.addChild(LispTree.proto.newList("formula", formula.toLispTree()));
		if (value != null)
			if (opts.showValues)
				tree.addChild(LispTree.proto.newList("value", value.toLispTree()));
			else
				if (value instanceof ListValue)
				{
					final List<Value> values = ((ListValue) value).values;
					if (opts.showFirstValue && values.size() > 0)
						tree.addChild(LispTree.proto.newList(values.size() + " values", values.get(0).toLispTree()));
					else
						tree.addChild(values.size() + " values");
				}
		if (type != null && opts.showTypes)
			tree.addChild(LispTree.proto.newList("type", type.toLispTree()));
		if (opts.showRules)
			if (rule != null)
				tree.addChild(getRuleLispTree());
		if (opts.showUtterance && canonicalUtterance != null)
			tree.addChild(LispTree.proto.newList("canonicalUtterance", canonicalUtterance));
		if (opts.showCat && cat != null)
			tree.addChild(LispTree.proto.newList("cat", cat));
		return tree;
	}

	/**
	 * @return lisp tree showing the entire parse tree
	 */
	public LispTree toRecursiveLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("derivation");
		tree.addChild(LispTree.proto.newList("span", cat + "[" + start + ":" + end + "]"));
		if (formula != null)
			tree.addChild(LispTree.proto.newList("formula", formula.toLispTree()));
		for (final Derivation child : children)
			tree.addChild(child.toRecursiveLispTree());
		return tree;
	}

	public String toRecursiveString()
	{
		return toRecursiveLispTree().toString();
	}

	// TODO(pliang): remove this in favor of localChoices
	private LispTree getRuleLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("rules");
		getRuleLispTreeRecurs(tree);
		return tree;
	}

	private void getRuleLispTreeRecurs(final LispTree tree)
	{
		if (children.size() > 0)
		{
			tree.addChild(LispTree.proto.newList("rule", rule.toLispTree()));
			for (final Derivation child : children)
				child.getRuleLispTreeRecurs(tree);
		}
	}

	public String startEndString(final List<String> tokens)
	{
		return start + ":" + end + (start == -1 ? "" : tokens.subList(start, end));
	}

	@Override
	public String toString()
	{
		return toLispTree().toString();
	}

	public void incrementLocalFeatureVector(final double factor, final Map<String, Double> map)
	{
		localFeatureVector.increment(factor, map, AllFeatureMatcher.matcher);
	}

	public void incrementAllFeatureVector(final double factor, final Map<String, Double> map)
	{
		incrementAllFeatureVector(factor, map, AllFeatureMatcher.matcher);
	}

	public void incrementAllFeatureVector(final double factor, final Map<String, Double> map, final FeatureMatcher updateFeatureMatcher)
	{
		localFeatureVector.increment(factor, map, updateFeatureMatcher);
		for (final Derivation child : children)
			child.incrementAllFeatureVector(factor, map, updateFeatureMatcher);
	}

	public void incrementAllFeatureVector(final double factor, final FeatureVector fv)
	{
		localFeatureVector.add(factor, fv);
		for (final Derivation child : children)
			child.incrementAllFeatureVector(factor, fv);
	}

	// returns feature vector with renamed features by prefix
	public FeatureVector addPrefixLocalFeatureVector(final String prefix)
	{
		return localFeatureVector.addPrefix(prefix);
	}

	public Map<String, Double> getAllFeatureVector()
	{
		final Map<String, Double> m = new HashMap<>();
		incrementAllFeatureVector(1.0d, m, AllFeatureMatcher.matcher);
		return m;
	}

	// TODO(pliang): this is crazy inefficient
	public double getAllFeatureVector(final String featureName)
	{
		final Map<String, Double> m = new HashMap<>();
		incrementAllFeatureVector(1.0d, m, new ExactFeatureMatcher(featureName));
		return MapUtils.get(m, featureName, 0.0);
	}

	public void addLocalChoice(final String choice)
	{
		if (localChoices == null)
			localChoices = new ArrayList<>();
		localChoices.add(choice);
	}

	public void incrementAllChoices(final int factor, final Map<String, Integer> map)
	{
		if (opts.showRules)
			MapUtils.incr(map, "[" + start + ":" + end + "] " + rule.toString(), 1);
		if (localChoices != null)
			for (final String choice : localChoices)
				MapUtils.incr(map, choice, factor);
		for (final Derivation child : children)
			child.incrementAllChoices(factor, map);
	}

	// Used to compare derivations by score.
	public static class ScoredDerivationComparator implements Comparator<Derivation>
	{
		@Override
		public int compare(final Derivation deriv1, final Derivation deriv2)
		{
			if (deriv1.score > deriv2.score)
				return -1;
			if (deriv1.score < deriv2.score)
				return +1;
			// Ensure reproducible randomness
			if (deriv1.creationIndex < deriv2.creationIndex)
				return -1;
			if (deriv1.creationIndex > deriv2.creationIndex)
				return +1;
			return 0;
		}
	}

	// Used to compare derivations by compatibility.
	public static class CompatibilityDerivationComparator implements Comparator<Derivation>
	{
		@Override
		public int compare(final Derivation deriv1, final Derivation deriv2)
		{
			if (deriv1.compatibility > deriv2.compatibility)
				return -1;
			if (deriv1.compatibility < deriv2.compatibility)
				return +1;
			// Ensure reproducible randomness
			if (deriv1.creationIndex < deriv2.creationIndex)
				return -1;
			if (deriv1.creationIndex > deriv2.creationIndex)
				return +1;
			return 0;
		}
	}

	//Used to compare derivations by score, prioritizing the fully anchored.
	public static class AnchorPriorityScoreComparator implements Comparator<Derivation>
	{
		@Override
		public int compare(final Derivation deriv1, final Derivation deriv2)
		{
			final boolean deriv1Core = deriv1.allAnchored();
			final boolean deriv2Core = deriv2.allAnchored();

			if (deriv1Core && !deriv2Core)
				return -1;
			if (deriv2Core && !deriv1Core)
				return +1;

			if (deriv1.score > deriv2.score)
				return -1;
			if (deriv1.score < deriv2.score)
				return +1;
			// Ensure reproducible randomness
			if (deriv1.creationIndex < deriv2.creationIndex)
				return -1;
			if (deriv1.creationIndex > deriv2.creationIndex)
				return +1;
			return 0;
		}
	}

	// for debugging
	public void printDerivationRecursively()
	{
		LogInfo.logs("Deriv: %s(%s,%s) %s", cat, start, end, formula);
		for (int i = 0; i < children.size(); i++)
		{
			LogInfo.begin_track("child %s:", i);
			children.get(i).printDerivationRecursively();
			LogInfo.end_track();
		}
	}

	public static void sortByScore(final List<Derivation> trees)
	{
		Collections.sort(trees, derivScoreComparator);
	}

	// Generate a probability distribution over derivations given their scores.
	public static double[] getProbs(final List<Derivation> derivations, final double temperature)
	{
		final double[] probs = new double[derivations.size()];
		for (int i = 0; i < derivations.size(); i++)
			probs[i] = derivations.get(i).getScore() / temperature;
		if (probs.length > 0)
			NumUtils.expNormalize(probs);
		return probs;
	}

	// Manipulation of temporary state used during parsing.
	public Map<String, Object> getTempState()
	{
		// Create the tempState if it doesn't exist.
		if (tempState == null)
			tempState = new HashMap<>();
		return tempState;
	}

	public void clearTempState()
	{
		tempState = null;
		if (children != null)
			for (final Derivation child : children)
				child.clearTempState();
	}

	/**
	 * Return an int array numAnchors where numAnchors[i] is the number of times we anchored on token i. numAnchors[>= numAnchors.length] are 0 by default.
	 */
	public int[] getNumAnchors()
	{
		if (numAnchors == null)
			if (rule.isAnchored())
			{
				numAnchors = new int[end];
				for (int i = start; i < end; i++)
					numAnchors[i] = 1;
			}
			else
			{
				numAnchors = new int[0];
				for (final Derivation child : children)
				{
					final int[] childNumAnchors = child.getNumAnchors();
					if (numAnchors.length < childNumAnchors.length)
					{
						final int[] newNumAnchors = new int[childNumAnchors.length];
						for (int i = 0; i < numAnchors.length; i++)
							newNumAnchors[i] = numAnchors[i];
						numAnchors = newNumAnchors;
					}
					for (int i = 0; i < childNumAnchors.length; i++)
						numAnchors[i] += childNumAnchors[i];
				}
			}
		return numAnchors;
	}

	/**
	 * Return a boolean array anchoredTokens where anchoredTokens[i] indicates whether we have anchored on token i. anchoredTokens[>= anchoredTokens.length] are
	 * False by default
	 */
	public boolean[] getAnchoredTokens()
	{
		final int[] numAnchors_ = getNumAnchors();
		final boolean[] anchoredTokens = new boolean[numAnchors_.length];
		for (int i = 0; i < numAnchors_.length; i++)
			anchoredTokens[i] = numAnchors_[i] > 0;
		return anchoredTokens;
	}

	public Derivation betaReduction()
	{
		final Formula reduced = Formulas.betaReduction(formula);
		return new Builder().withAllFrom(this).formula(reduced).createDerivation();
	}

	public boolean allAnchored()
	{
		if (rule.isInduced() || !allAnchored)
		{
			allAnchored = false;
			return false;
		}
		else
		{
			for (final Derivation child : children)
				if (child.allAnchored() == false)
					return false;
			return true;
		}
	}
}
