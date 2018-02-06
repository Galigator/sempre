package edu.stanford.nlp.sempre;

import com.google.common.base.Joiner;
import fig.basic.Fmt;
import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.NumUtils;
import fig.basic.Option;
import fig.basic.Pair;
import fig.exec.Execution;
import fig.prob.SampleUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * @author joberant Parser and learning using re-inforcement learning, that is maximizing future rewards. During learning we sample from the agenda that
 *         contains all possible next actions Then we update the gradient so that actions that were taken and led to good reward will get better weights than
 *         other actions At test time we simply find the highest probability parse so that is done using regular parsing We implement this using a chart and an
 *         agenda that holds the possible future actions NOTE: during search we search with the agenda and then do re-ranking features are separated by a
 *         "search" prefix. For this when we compute a derivation score on the agenda we prefix the derivation features with "search", and in addition, when
 *         updating the expected counts we add the "search" prefix. This is brittle... but at the end we score with re-ranking features and then the score of
 *         the derivation is \theta \times \phi as usual
 */
public class ReinforcementParser extends Parser
{
	public static class Options
	{
		@Option(gloss = "Whether to do coarse pruning")
		public boolean efficientCoarsePrune = true;
		@Option(gloss = "Whether to do importance sampling")
		public double multiplicativeBonus = 1000d;
		@Option(gloss = "Number of samples")
		public int numOfSamplesPerExample = 1;
		@Option(gloss = "Whether to update gradient only for correct moves")
		public boolean updateGradientForCorrectMovesOnly = true;
		@Option(gloss = "Low probability for which we don't unroll the stream")
		public double lowProb = 0.01;
		@Option(gloss = "Whether to simulate the log liklihood objective")
		public boolean simulateNonRlObjective = false;
		@Option(gloss = "Whether to always unroll (even at test time)")
		public boolean alwaysUnroll = false;
	}

	public static Options opts = new Options();

	// we assume here a binarized grammar
	Map<String, Map<String, List<Rule>>> leftToRightSiblingMap = new HashMap<>();
	Map<String, Map<String, List<Rule>>> rightToLeftSiblingMap = new HashMap<>();
	Map<String, List<Rule>> terminalsToRulesList = new HashMap<>();
	final CoarseParser coarseParser;
	public static final String SEARCH_PREFIX = "search_";
	public final String searchPrefix;

	public ReinforcementParser(final Spec spec)
	{
		super(spec);
		coarseParser = new CoarseParser(grammar);

		// generate maps from left to right and vice versa
		for (final Rule rule : grammar.rules)
		{
			if (rule.rhs.size() > 2)
				throw new RuntimeException("We assume that the grammar is binarized, rule: " + rule);
			if (rule.rhs.size() == 2)
			{
				final String left = rule.rhs.get(0);
				final String right = rule.rhs.get(1);
				addToSiblingMap(left, right, rule, leftToRightSiblingMap);
				addToSiblingMap(right, left, rule, rightToLeftSiblingMap);
			}
			if (rule.isRhsTerminals())
				MapUtils.addToList(terminalsToRulesList, Joiner.on(' ').join(rule.rhs), rule);
		}
		if (Parser.opts.visualizeChartFilling)
			chartFillOut = IOUtils.openOutAppendEasy(Execution.getFile("chartfill"));
		searchPrefix = opts.simulateNonRlObjective ? "" : SEARCH_PREFIX;
		LogInfo.logs("ReinforcementParser(): search prefix is %s", searchPrefix);
	}

	private void addToSiblingMap(final String keySibling, final String valueSibling, final Rule rule, final Map<String, Map<String, List<Rule>>> siblingToSiblingMap)
	{
		Map<String, List<Rule>> valueSiblingMap = siblingToSiblingMap.get(keySibling);
		if (valueSiblingMap == null)
			siblingToSiblingMap.put(keySibling, valueSiblingMap = new HashMap<>());
		MapUtils.addToList(valueSiblingMap, valueSibling, rule);
	}

	@Override
	public ParserState newParserState(final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		if (computeExpectedCounts)
			// if we simulate non RL we just take the max and not do sampling
			if (opts.simulateNonRlObjective)
				return new ReinforcementParserState.StateBuilder().parser(this).params(params).example(ex).samplingStrategy("max").computeExpectedCounts(true).createState();
			else
				return new ReinforcementParserState.StateBuilder().parser(this).params(params).example(ex).samplingStrategy("proposal").computeExpectedCounts(true).createState();
		return new ReinforcementParserState.StateBuilder().parser(this).params(params).example(ex).samplingStrategy("max").computeExpectedCounts(false).createState();
	}

	@Override
	public Params getSearchParams(final Params params)
	{
		return params.copyParamsByPrefix(searchPrefix);
	}
}

// Parsing by sampling items from the agenda
// Defined by: (a) sampling strategy (from agenda, from true derivation, take the max)
// (b) whether an update needs to be done
final class ReinforcementParserState extends AbstractReinforcementParserState
{

	public enum NecessaryDeriv
	{
		NECESSARY_DERIV, UNNECESSARY_DERIV, UNKNOWN
	}

	private static final double LOG_SMALL_PROB = Math.log(ReinforcementParser.opts.lowProb);

	private final ParserAgenda<PrioritizedDerivationStream> agenda;

	private int completeDerivationsPushed = 0;
	private int firstCorrectItem = -1;
	private final String samplingStrategy;
	private Sampler sampler;
	List<Derivation> correctDerivations = new ArrayList<>();
	private final Map<String, Double> stateSequenceExpectedCounts = new HashMap<>();
	Random randGen = new Random(1);
	// backpointers for remembering what derivations on the stream were popped before others
	private final Map<Long, Pair<ArrayList<Derivation>, Integer>> backpointerList;
	private int numItemsSampled = 0;

	public static class StateBuilder
	{
		private ReinforcementParser parser;
		private Params params;
		private Example example;
		private ParserState coarseState;
		private String samplingStrategy = null;
		private boolean computeExpectedCounts;

		public StateBuilder parser(final ReinforcementParser parser_)
		{
			parser = parser_;
			return this;
		}

		public StateBuilder params(final Params params_)
		{
			params = params_;
			return this;
		}

		public StateBuilder example(final Example example_)
		{
			example = example_;
			return this;
		}

		public StateBuilder samplingStrategy(final String samplingStrategy_)
		{
			samplingStrategy = samplingStrategy_;
			return this;
		}

		public StateBuilder computeExpectedCounts(final boolean computeExpectedCounts_)
		{
			computeExpectedCounts = computeExpectedCounts_;
			return this;
		}

		public ReinforcementParserState createState()
		{
			return new ReinforcementParserState(parser, params, example, computeExpectedCounts, samplingStrategy);
		}
	}

	// note that the sampler has a pointer to the fields of the state where they were created
	private ReinforcementParserState(final ReinforcementParser parser_, final Params params_, final Example ex_, final boolean computeExpectedCounts_, final String samplingStrategy_)
	{
		super(parser_, params_, ex_, computeExpectedCounts_);
		samplingStrategy = samplingStrategy_;
		backpointerList = new HashMap<>();
		agenda = samplingStrategy.equals("max") ? new QueueParserAgenda() : new ListParserAgenda();
	}

	@Override
	protected void addToAgenda(final DerivationStream derivationStream)
	{
		addToAgenda(derivationStream, 0d);
	}

	private void addToAgenda(final DerivationStream derivationStream, final double probSum)
	{

		if (!derivationStream.hasNext())
			return;

		//if it's less than one we can just add it even if we unroll everything (optimization)
		if (!ReinforcementParser.opts.alwaysUnroll || derivationStream.estimatedSize() <= 1)
		{
			final Derivation deriv = derivationStream.peek();
			featurizeAndScoreDerivation(deriv);
			addToAgendaWithScore(derivationStream, deriv.score, probSum);
			if (completeDerivationsPushed % 100 == 0) // sort the agenda
				agenda.sort();
		}
		else
			while (derivationStream.hasNext())
			{
				final Derivation deriv = derivationStream.next();
				featurizeAndScoreDerivation(deriv);
				final DerivationStream newStream = SingleDerivationStream.constant(deriv);
				addToAgendaWithScore(newStream, deriv.score, probSum);
				if (completeDerivationsPushed % 100 == 0) // sort the agenda
					agenda.sort();
			}
	}

	// we need to override the method because parameters are prefixed with "search_"
	// this means that the score will not be the dot product and features and weights
	@Override
	protected void featurizeAndScoreDerivation(final Derivation deriv)
	{
		if (deriv.isFeaturizedAndScored())
			return;

		// Compute features
		_parser.extractor.extractLocal(ex, deriv);

		// Compute score by adding |SEARCH_PREFIX| prefix and adding children scores
		final FeatureVector searchFV = deriv.addPrefixLocalFeatureVector(_parser.searchPrefix);
		deriv.score = searchFV.dotProduct(params);
		if (deriv.children != null)
			for (final Derivation child : deriv.children)
				deriv.score += child.score;

		if (_parser.verbose(3))
			LogInfo.logs("featurizeAndScore(score=%s) %s %s: %s [rule: %s]", Fmt.D(deriv.score), deriv.cat, ex.spanString(deriv.start, deriv.end), deriv, deriv.rule);
		numOfFeaturizedDerivs++;
	}

	private void addToAgendaWithScore(final DerivationStream derivationStream, final double derivScore, final double probSum)
	{
		if (derivScore == Double.NEGATIVE_INFINITY)
			return; // no need to add bad derivations - shouldn't happen

		final Derivation deriv = derivationStream.peek(); // Score a DerivationStream based on the first item in the stream.
		final double priority = derivScore - completeDerivationsPushed++ * EPSILON;
		agenda.add(new PrioritizedDerivationStream(derivationStream, priority, probSum), priority); // when adding to agenda probsum is 0

		if (_parser.verbose(3))
			LogInfo.logs("ReinforcementParser: adding to agenda: size=%s, priority=%s, deriv=%s(%s,%s), formula=%s,|pushed|=%s", agenda.size(), priority, deriv.cat, deriv.start, deriv.end, deriv.formula, completeDerivationsPushed);
	}

	public boolean continueParsing()
	{
		if (agenda.size() == 0)
		{
			LogInfo.log("Agenda is empty");
			return false;
		}

		return chart[0][numTokens].get(Rule.rootCat) == null || chart[0][numTokens].get(Rule.rootCat).size() < getBeamSize();
	}

	@Override
	public void infer()
	{
		if (numTokens == 0)
			return;

		ReinforcementParserState oracleState = null;
		expectedCounts = new HashMap<>();
		if (computeExpectedCounts && !ReinforcementParser.opts.simulateNonRlObjective)
		{ // when updating params we first find a correct derivation to set the oracle sampler
			// TODO(jonathan): move to ReinforcementParser, not ParserState
			LogInfo.begin_track("Finding oracle derivation");
			oracleState = new StateBuilder().parser(_parser).params(params).example(ex).samplingStrategy("agenda").computeExpectedCounts(false).createState(); // update params is false preventing an infinite loop
			oracleState.infer();
			LogInfo.end_track();
			if (oracleState.correctDerivations.isEmpty())
			{
				LogInfo.logs("No oracle derivation found");
				return;
			}
		}
		createSampler(oracleState); // we can only create the sampler after we have the oracle derivation

		LogInfo.begin_track("Coarse parsing");
		coarseParserState = null;
		if (ReinforcementParser.opts.efficientCoarsePrune)
			coarseParserState = coarseParser.getCoarsePrunedChart(ex);
		LogInfo.end_track();

		// draw a sample to compute gradient and expected reward
		LogInfo.begin_track("ReinforcementParserState.inferBySampling");
		sampleHistoryAndInfer();
		LogInfo.end_track();

		// Compute gradient
		setPredDerivations();
		if (_parser.verbose(3))
			LogInfo.logs("Expected reward = %s", objectiveValue);
		visualizeChart();
	}

	private void sampleHistoryAndInfer()
	{

		// add to chart the token and phrase parts
		for (final Derivation deriv : gatherTokenAndPhraseDerivations())
			addToAgenda(SingleDerivationStream.constant(deriv));
		// add to agenda unaries where RHS is just terminals
		for (final DerivationStream derivStream : gatherRhsTerminalsDerivations())
			addToAgenda(derivStream);

		ensureExecuted();

		while (continueParsing())
		{

			unrollHighProbStreams();
			final Pair<PrioritizedDerivationStream, Double> pdsAndProbability = sampler.sample();

			final DerivationStream sampledDerivations = pdsAndProbability.getFirst().derivStream;
			final Derivation sampledDerivation = sampledDerivations.next();
			updateBackpointers(sampledDerivations, sampledDerivation); // to be able to get all correct actions
			numItemsSampled++;

			assert sampledDerivation.isFeaturizedAndScored() : "top derivation is not featurized and scored: " + sampledDerivation;
			assert Math.abs(sampledDerivation.score - pdsAndProbability.getFirst().priority) < 1e-4 : sampledDerivation.score + " != " + pdsAndProbability.getFirst().priority;

			if (_parser.verbose(2))
				LogInfo.begin_track("Item %d (|agenda|=%d), priority %s: |item|=%s -> %s %s %s [%s], prob=%s", numItemsSampled, agenda.size() + 1, Fmt.D(pdsAndProbability.getFirst().priority), sampledDerivations.estimatedSize(), sampledDerivation.cat, ex.spanString(sampledDerivation.start, sampledDerivation.end), sampledDerivation, sampledDerivation.rule, pdsAndProbability.getSecond());

			// handle root derivations - get compatibility and record number of compatible derivations
			handleRootDerivation(ex, numItemsSampled, sampledDerivation);

			if (computeExpectedCounts)
			{
				final Map<String, Double> counts = new HashMap<>();
				// add the feature vector and subtract for the time it was in the agenda unless has negative probability
				//pretty hacky
				if (pdsAndProbability.getSecond() > -0.0001)
					sampledDerivation.incrementLocalFeatureVector(1 - pdsAndProbability.getFirst().probSum, counts);
				else
					sampledDerivation.incrementLocalFeatureVector(-pdsAndProbability.getFirst().probSum, counts);
				if (_parser.verbose(3))
					SempreUtils.logMap(counts, "agenda item gradient");
				ReinforcementUtils.addToDoubleMap(stateSequenceExpectedCounts, counts, _parser.searchPrefix); // upate the gradient incrementally
			}
			// only after update of params we can change the chart and the agenda
			if (addToBoundedChart(sampledDerivation))
			{
				if (_parser.verbose(5))
					LogInfo.logs("ReinforcementParserState.infer: adding to chart %s(%s,%s) formula=%s", sampledDerivation.cat, sampledDerivation.start, sampledDerivation.end, sampledDerivation.formula);
				combineWithChartDerivations(sampledDerivation);
			}
			addToAgenda(sampledDerivations);
			if (_parser.verbose(2))
				LogInfo.end_track();
		}

		finalizeSearchExpectedCounts(); // gradient for remaining agenda items
		rerankRootDerivations(); // last action
		if (computeExpectedCounts)
			computeGradient();
	}

	private void unrollHighProbStreams()
	{

		if (samplingStrategy.equals("max"))
			return;

		sampler.unroll(); // if multiplicative, then unroll oracle stuff (ignore \beta currently!)

		if (_parser.verbose(3))
			LogInfo.begin_track("Unrolling high probability streams");

		double lb = Double.NEGATIVE_INFINITY;
		int numOfHiddenStreams = 0;
		for (final PrioritizedDerivationStream pds : agenda)
		{
			lb = NumUtils.logAdd(lb, pds.getScore());
			if (pds.derivStream.estimatedSize() > 1)
				numOfHiddenStreams++;
		}

		if (_parser.verbose(3))
			LogInfo.logs("unrollHighProbStreams(): |agenda|=%s, lb=%s, |hiddenstreams|=%s", agenda.size(), lb, numOfHiddenStreams);

		final List<Pair<DerivationStream, Double>> derivsToAdd = new ArrayList<>();
		final List<Integer> indicesToRemove = new ArrayList<>();
		for (int i = 0; i < agenda.size(); ++i)
		{
			final PrioritizedDerivationStream pds = agenda.get(i);
			boolean modified = false;
			while (pds.derivStream.hasNext() && pds.derivStream.estimatedSize() > 1 && illegalStream(pds.derivStream, lb, pds.derivStream.estimatedSize(), numOfHiddenStreams))
			{
				modified = true;
				final Derivation nextDeriv = pds.derivStream.next();
				updateBackpointers(pds.derivStream, nextDeriv);

				final DerivationStream derivStream = SingleDerivationStream.constant(nextDeriv);
				if (_parser.verbose(3) && derivStream.hasNext())
				{
					final Derivation deriv = derivStream.peek();
					LogInfo.logs("unrollIllegalStreams(): add deriv=%s(%s,%s) [%s] score=%s, |stream|=%s", deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.score, pds.derivStream.estimatedSize());
				}
				derivsToAdd.add(Pair.newPair(derivStream, pds.probSum));
				//update lb
				if (pds.derivStream.hasNext())
				{
					featurizeAndScoreDerivation(pds.derivStream.peek());
					lb = NumUtils.logAdd(lb, pds.getScore());
				}
				//update num of hidden streams
				if (pds.derivStream.estimatedSize() <= 1)
					numOfHiddenStreams--;
			}
			if (modified)
			{
				indicesToRemove.add(i);
				derivsToAdd.add(Pair.newPair(pds.derivStream, pds.probSum));
			}
		}

		// remove - need to make sure indices don't change due to removal so go from end to start
		for (int i = indicesToRemove.size() - 1; i >= 0; --i)
			agenda.remove(agenda.get(indicesToRemove.get(i)), indicesToRemove.get(i));

		// add
		for (final Pair<DerivationStream, Double> pair : derivsToAdd)
			addToAgenda(pair.getFirst(), pair.getSecond());

		if (_parser.verbose(3))
			LogInfo.logs("unrollHighProbStreams(): |agenda|=%s", agenda.size());
		if (_parser.verbose(3))
			LogInfo.end_track();
	}

	private boolean illegalStream(final DerivationStream derivStream, final double logSum, final int estimatedSize, final int numOfHiddenStreams)
	{
		final Derivation deriv = derivStream.peek();
		final double firstItemLogProb = deriv.score - logSum; //log(exp(s(g_1))/L)
		final double upperBound = Math.log(estimatedSize) + Math.log(numOfHiddenStreams); //log(M(g)|G'|)

		if (_parser.verbose(3))
			LogInfo.logs("IllegalStream(): score=%s, logsum=%s, |stream|=%s, |hiddenstreams|=%s, deriv=%s(%s,%s) %s, sum=%s", deriv.score, logSum, estimatedSize, numOfHiddenStreams, deriv.cat, deriv.start, deriv.end, deriv.formula, firstItemLogProb + upperBound);

		return firstItemLogProb + upperBound > LOG_SMALL_PROB;
	}

	// recompute score using dot product of features and re-ranking feature weights
	private void rerankRootDerivations()
	{
		setPredDerivations();
		for (final Derivation rootDeriv : predDerivations)
		{
			final double oldScore = rootDeriv.score;
			rootDeriv.computeScore(params);
			if (_parser.verbose(3))
				LogInfo.logs("ReinforcementParser.rerankRootDerivations: deriv=%s, old=%s, new=%s", rootDeriv, oldScore, rootDeriv.score);
		}
		Derivation.sortByScore(predDerivations);
	}

	private void updateBackpointers(final DerivationStream stream, final Derivation sampledDeriv)
	{
		Pair<ArrayList<Derivation>, Integer> pair = backpointerList.get(sampledDeriv.creationIndex);
		if (!stream.hasNext())
			return;

		if (pair == null)
		{
			final ArrayList<Derivation> list = new ArrayList<>();
			list.add(sampledDeriv);
			backpointerList.put(sampledDeriv.creationIndex, pair = Pair.newPair(list, 0));
		}
		final List<Derivation> list = pair.getFirst();
		final Derivation nextDeriv = stream.peek();
		list.add(nextDeriv);
		backpointerList.put(nextDeriv.creationIndex, Pair.newPair(pair.getFirst(), list.size() - 1));
	}

	private double computeExpectedReward(final List<Derivation> predDerivations, final double[] probs)
	{
		double rewardExpectation = 0d;
		for (int i = 0; i < predDerivations.size(); ++i)
			rewardExpectation += probs[i] * compatibilityToReward(predDerivations.get(i).compatibility);
		return rewardExpectation;
	}

	// q - proposal distribution
	// pi = model distribution
	private void computeGradient()
	{
		if (predDerivations.isEmpty())
			return;

		final double[] qDist = sampler.getDerivDistribution(predDerivations); //uniform over correct things when \beta is high
		final double[] piDist = ReinforcementUtils.expNormalize(predDerivations);
		// compute E_q(R(d))
		LogInfo.begin_track("Computing gradient");
		final double rewardExpectation = computeExpectedReward(predDerivations, qDist);

		// compute E_q(\phi(d) R(d)) and E_pi(\phi(d))
		final Map<String, Double> featureExpectation = new HashMap<>(), rewardInfusedFeatureExpectation = new HashMap<>();
		for (int i = 0; i < predDerivations.size(); ++i)
		{
			final Derivation deriv = predDerivations.get(i);
			deriv.incrementAllFeatureVector(piDist[i], featureExpectation);
			deriv.incrementAllFeatureVector(qDist[i] * compatibilityToReward(deriv.compatibility), rewardInfusedFeatureExpectation);
		}
		// final gradient computation
		Map<String, Double> sampleCounts = new HashMap<>();
		if (ReinforcementParser.opts.simulateNonRlObjective)
			ParserState.computeExpectedCounts(predDerivations, sampleCounts);
		else
		{
			sampleCounts = ReinforcementUtils.multiplyDoubleMap(stateSequenceExpectedCounts, rewardExpectation);
			SempreUtils.addToDoubleMap(sampleCounts, rewardInfusedFeatureExpectation);
			ReinforcementUtils.subtractFromDoubleMap(sampleCounts, ReinforcementUtils.multiplyDoubleMap(featureExpectation, rewardExpectation));
		}
		SempreUtils.addToDoubleMap(expectedCounts, sampleCounts);

		double sum = 0d;
		for (final String key : sampleCounts.keySet())
		{
			final double value = sampleCounts.get(key);
			if (_parser.verbose(3))
				LogInfo.logs("feature=%s, value=%s", key, value);
			sum += value * value;
		}
		LogInfo.logs("L2 norm: %s", Math.sqrt(sum));
		LogInfo.end_track();
	}

	private void createSampler(final ReinforcementParserState oracleState)
	{
		if ("proposal".equals(samplingStrategy))
		{
			if (oracleState == null)
				throw new RuntimeException("missing oracle state");
			sampler = new MultiplicativeProposalSampler(oracleState);
		}
		else
			if ("max".equals(samplingStrategy))
				sampler = new MaxSampler();
			else
				if ("agenda".equals(samplingStrategy) || samplingStrategy == null) // default
					sampler = new AgendaSampler();
	}

	// info for visualizing chart
	private void visualizeChart()
	{
		if (_parser.chartFillOut != null && Parser.opts.visualizeChartFilling)
		{
			_parser.chartFillOut.println(Json.writeValueAsStringHard(new ChartFillingData(ex.id, chartFillingList, ex.utterance, ex.numTokens())));
			_parser.chartFillOut.flush();
		}
	}

	// go over all agenda items that we did not subtract counts for and finalize
	private void finalizeSearchExpectedCounts()
	{
		if (ReinforcementParser.opts.simulateNonRlObjective)
			return;
		if (!computeExpectedCounts)
			return;
		final Map<String, Double> counts = new HashMap<>();
		for (final PrioritizedDerivationStream pds : agenda)
			pds.derivStream.peek().incrementLocalFeatureVector(pds.probSum, counts);
		if (_parser.verbose(3))
			SempreUtils.logMap(counts, "subtracted");
		ReinforcementUtils.subtractFromDoubleMap(stateSequenceExpectedCounts, counts, _parser.searchPrefix);
		if (_parser.verbose(3))
			SempreUtils.logMap(stateSequenceExpectedCounts, "final search gradient");
	}

	private void handleRootDerivation(final Example ex, final int numItemsSampled, final Derivation sampledDerivation)
	{
		if (!sampledDerivation.isRoot(ex.numTokens()))
			return;

		sampledDerivation.ensureExecuted(_parser.executor, ex.context);
		if (ex.targetValue != null)
			sampledDerivation.compatibility = _parser.valueEvaluator.getCompatibility(ex.targetValue, sampledDerivation.value);
		if (Parser.opts.partialReward ? sampledDerivation.compatibility > 0 : sampledDerivation.compatibility == 1)
		{
			if (_parser.verbose(2))
				LogInfo.logs("Top-level %s: reward = %s", numItemsSampled, sampledDerivation.compatibility);
			// put in position 0 the derivation with best compatibility
			correctDerivations.add(sampledDerivation);
			if (correctDerivations.get(0).compatibility < sampledDerivation.compatibility)
				Collections.swap(correctDerivations, 0, correctDerivations.size() - 1);

			if (firstCorrectItem == -1)
				firstCorrectItem = numItemsSampled;
		}
	}

	@Override
	public void setEvaluation()
	{
		LogInfo.begin_track_printAll("ReinforcementParserParserState.setEvaluation");
		super.setEvaluation();

		if (coarseParserState != null)
			evaluation.add("coarseParseTime", coarseParserState.getCoarseParseTime());
		if (firstCorrectItem != -1)
			evaluation.add("firstCorrectItem", firstCorrectItem);
		LogInfo.end_track();
	}

	// Defines how to sample the next state
	abstract class Sampler
	{
		// returns a derivation stream with the sample probability
		public abstract Pair<PrioritizedDerivationStream, Double> sample();

		public abstract double[] getDerivDistribution(List<Derivation> rootDerivs);

		public abstract void unroll();

		// go over agenda and update the probability sum for gradient computation before sampling
		public void updateProbSum(final double[] modelProbs)
		{
			for (int i = 0; i < agenda.size(); ++i)
			{
				final PrioritizedDerivationStream pds = agenda.get(i);
				pds.addProb(modelProbs[i]);
				if (_parser.verbose(3))
					LogInfo.logs("updateProbSum(): deriv=%s, probSum=%s", pds.derivStream.peek(), pds.probSum);
			}
		}
	}

	// sample from agenda based on agenda scores
	class AgendaSampler extends Sampler
	{

		@Override

		public Pair<PrioritizedDerivationStream, Double> sample()
		{

			final double[] modelProbs = ReinforcementUtils.expNormalize(agenda);
			if (computeExpectedCounts)// compute probability sum before sampling for gradient computation (easier before sampling)
				updateProbSum(modelProbs);

			final int sampledIndex = SampleUtils.sampleMultinomial(randGen, modelProbs);
			final PrioritizedDerivationStream pds = agenda.get(sampledIndex);
			final double prob = modelProbs[sampledIndex];
			agenda.remove(pds, sampledIndex);
			return Pair.newPair(pds, prob);
		}

		@Override
		public double[] getDerivDistribution(final List<Derivation> rootDerivs)
		{
			return ReinforcementUtils.expNormalize(rootDerivs);
		}

		@Override
		public void unroll()
		{
		}
	}

	class MaxSampler extends Sampler
	{

		@Override
		public Pair<PrioritizedDerivationStream, Double> sample()
		{
			final PrioritizedDerivationStream pds = agenda.pop();
			return Pair.newPair(pds, 1d);
		}

		@Override
		public double[] getDerivDistribution(final List<Derivation> rootDerivs)
		{
			final double[] res = new double[rootDerivs.size()];
			Arrays.fill(res, 0d);
			res[0] = 1d;
			return res;
		}

		@Override
		public void unroll()
		{
		}
	}

	// sample categories and spans that are in a correct derivation
	class OracleInfo
	{

		private final List<DerivInfo> necessaryDerivInfos; // info for derivations necessary to generate oracle derivations
		private final List<DerivInfo> oracleDerivInfos; // info for oracle derivations
		private Map<Long, Pair<ArrayList<Derivation>, Integer>> backPointers;
		private NecessaryDeriv[] necessaryDerivsCache; // memorizing whether a deriv is necessary or not
		long firstCorrectDerivNumber = -1; // offset for necessaryDerivs

		// we sample from the end to the start
		public OracleInfo(final ReinforcementParserState oracleState)
		{
			if (oracleState == null)
				throw new RuntimeException("oracle state is null");
			necessaryDerivInfos = new ArrayList<>();
			oracleDerivInfos = new ArrayList<>();
			if (!oracleState.correctDerivations.isEmpty())
			{
				Collections.sort(oracleState.correctDerivations, new CorrectDerivationComparator());
				backPointers = oracleState.backpointerList;
				final Derivation oracleDeriv = oracleState.correctDerivations.get(0);
				firstCorrectDerivNumber = oracleDeriv.creationIndex;

				LogInfo.logs("OracleSampler: deriv=%s, comp=%s", oracleDeriv, oracleDeriv.compatibility);
				populateCorrectDerivations(oracleDeriv);

				if (_parser.verbose(2))
				{
					LogInfo.begin_track("OracleSampler: necessary infos:");
					for (final DerivInfo necessaryInfo : necessaryDerivInfos)
						LogInfo.log(necessaryInfo);
					LogInfo.end_track();
					LogInfo.begin_track("OracleSampler: oracle infos:");
					for (final DerivInfo oracleInfo : oracleDerivInfos)
						LogInfo.log(oracleInfo);
					LogInfo.end_track();
				}
			}
		}

		private void populateCorrectDerivations(final Derivation oracleDeriv)
		{
			// add derivation info and also all upstream derivations
			if (_parser.verbose(4))
				LogInfo.logs("populateCorrectDerivations(): oracle deriv: %s", oracleDeriv);

			final Pair<ArrayList<Derivation>, Integer> listAndIndex = backPointers.get(oracleDeriv.creationIndex);
			if (listAndIndex != null)
				for (int i = listAndIndex.getSecond() - 1; i >= 0; i--)
				{
					final Derivation deriv = listAndIndex.getFirst().get(i);
					if (_parser.verbose(4))
						LogInfo.logs("populateCorrectDerivations(): necessary deriv: %s", deriv);
					final DerivInfo derivInfo = new DerivInfo(deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.rule);
					if (!necessaryDerivInfos.contains(derivInfo))
						necessaryDerivInfos.add(derivInfo);
				}
			final DerivInfo derivInfo = new DerivInfo(oracleDeriv.cat, oracleDeriv.start, oracleDeriv.end, oracleDeriv.formula, oracleDeriv.rule);
			if (!oracleDerivInfos.contains(derivInfo))
			{
				necessaryDerivInfos.add(derivInfo);
				oracleDerivInfos.add(derivInfo);
			}

			// recurse
			for (final Derivation child : oracleDeriv.children)
				populateCorrectDerivations(child);
		}

		// checking if derivation is necessary using the cache
		protected boolean isNecessaryDeriv(final Derivation deriv)
		{

			if (necessaryDerivInfos.isEmpty())
				return false;
			if (necessaryDerivsCache == null)
			{
				necessaryDerivsCache = new NecessaryDeriv[200000];
				Arrays.fill(necessaryDerivsCache, NecessaryDeriv.UNKNOWN);
			}
			final int index = (int) (deriv.creationIndex - firstCorrectDerivNumber);
			if (index < 0)
				throw new RuntimeException("Negative index - correct index larger than deriv number");
			if (index >= 200000)
			{
				LogInfo.warnings("isNecessaryDeriv(): index larger than 200000: %s", index);
				return necessaryDerivInfos.contains(new DerivInfo(deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.rule));
			}
			if (necessaryDerivsCache[index] == NecessaryDeriv.UNNECESSARY_DERIV)
				return false;
			if (necessaryDerivsCache[index] == NecessaryDeriv.NECESSARY_DERIV)
				return true;
			// unknown
			final boolean res = necessaryDerivInfos.contains(new DerivInfo(deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.rule));
			necessaryDerivsCache[index] = res ? NecessaryDeriv.NECESSARY_DERIV : NecessaryDeriv.UNNECESSARY_DERIV;
			return res;
		}
	}

	class MultiplicativeProposalSampler extends Sampler
	{

		private final double bonus;
		private final OracleInfo oracleInfo;

		public MultiplicativeProposalSampler(final ReinforcementParserState oracleState)
		{
			oracleInfo = new OracleInfo(oracleState);
			bonus = ReinforcementParser.opts.multiplicativeBonus;
			LogInfo.logs("Bonus=%s", bonus);
		}

		// We assume that oracle stuff has been unrolled
		@Override
		public Pair<PrioritizedDerivationStream, Double> sample()
		{

			final double[] modelProbs = ReinforcementUtils.expNormalize(agenda);
			final double[] samplerProbs = getUnnormalizedAgendaDistribution();
			if (!NumUtils.expNormalize(samplerProbs))
				throw new RuntimeException("Normalization failed" + Arrays.toString(samplerProbs));

			final int sampledIndex = ReinforcementUtils.sampleIndex(randGen, samplerProbs);
			final PrioritizedDerivationStream pds = agenda.get(sampledIndex);
			final double prob = samplerProbs[sampledIndex];

			if (_parser.verbose(3))
			{
				final Derivation deriv = pds.derivStream.peek();
				if (oracleInfo.oracleDerivInfos.contains(new DerivInfo(deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.rule)))
					LogInfo.logs("MultiplicativeProposalSampler.sample(): Sampled from correct!, prob=%s", prob);
				else
					LogInfo.logs("MultiplicativeProposalSampler.sample(): Sampled from incorrect!, prob=%s", prob);
			}
			boolean returnProb = true;

			// whether to update only for correct moves or not hack
			if (computeExpectedCounts && ReinforcementParser.opts.updateGradientForCorrectMovesOnly)
			{
				final Derivation deriv = pds.derivStream.peek();
				final DerivInfo derivInfo = new DerivInfo(deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.rule);
				if (oracleInfo.oracleDerivInfos.contains(derivInfo))
					updateProbSum(modelProbs);
				else
					returnProb = false;

				if (_parser.verbose(3))
					LogInfo.logs("Updating gradient=%s", returnProb);
			}
			else
				if (computeExpectedCounts) // compute probability sum before sampling for gradient computation (easier before sampling)
					updateProbSum(modelProbs);

			agenda.remove(pds, sampledIndex);
			return Pair.newPair(pds, returnProb ? prob : -1d);
		}

		//todo : This unrolls all necessary derivations, and ignore \beta
		//assumes probability mass on non-necessary things is very very small
		//might need to be fixed if we anneal \beta to 0
		@Override
		public void unroll()
		{

			if (_parser.verbose(3))
				LogInfo.begin_track("MultiplicativeBonusSampler.unroll()");

			final List<Pair<DerivationStream, Double>> derivsToAdd = new ArrayList<>();
			final List<Integer> indicesToRemove = new ArrayList<>();

			for (int i = 0; i < agenda.size(); ++i)
			{
				final PrioritizedDerivationStream pds = agenda.get(i);
				boolean modified = false;
				while (pds.derivStream.hasNext() && pds.derivStream.estimatedSize() > 1 && oracleInfo.isNecessaryDeriv(pds.derivStream.peek()))
				{
					modified = true;
					final Derivation nextDeriv = pds.derivStream.next();
					final DerivationStream newDerivStream = SingleDerivationStream.constant(nextDeriv);
					if (_parser.verbose(3) && newDerivStream.hasNext())
					{
						final Derivation deriv = newDerivStream.peek();
						LogInfo.logs("MultiplicativeSampler.unroll(): add necessary deriv=%s(%s,%s) [%s] score=%s, |stream|=%s, creationIndex=%s", deriv.cat, deriv.start, deriv.end, deriv.formula, deriv.score, pds.derivStream.estimatedSize(), deriv.creationIndex);
					}
					derivsToAdd.add(Pair.newPair(newDerivStream, pds.probSum));
					if (pds.derivStream.hasNext())
						featurizeAndScoreDerivation(pds.derivStream.peek());
				}
				if (modified)
				{
					indicesToRemove.add(i);
					derivsToAdd.add(Pair.newPair(pds.derivStream, pds.probSum));
				}
			}
			// remove - need to make sure indices don't change due to removal so go from end to start
			for (int i = indicesToRemove.size() - 1; i >= 0; --i)
				agenda.remove(agenda.get(indicesToRemove.get(i)), indicesToRemove.get(i));
			// add
			for (final Pair<DerivationStream, Double> pair : derivsToAdd)
				addToAgenda(pair.getFirst(), pair.getSecond());
			if (_parser.verbose(3))
				LogInfo.end_track();
		}

		private double[] getUnnormalizedAgendaDistribution()
		{
			final double[] probs = new double[agenda.size()];
			for (int i = 0; i < agenda.size(); ++i)
			{
				final Derivation d = agenda.get(i).derivStream.peek();
				probs[i] = d.score;
				// we assume all necessary things have been unrolled already so no need to handle that
				if (oracleInfo.oracleDerivInfos.contains(new DerivInfo(d.cat, d.start, d.end, d.formula, d.rule)))
					probs[i] += bonus;
			}
			return probs;
		}

		@Override
		public double[] getDerivDistribution(final List<Derivation> rootDerivs)
		{
			final double[] res = new double[rootDerivs.size()];
			for (int i = 0; i < rootDerivs.size(); ++i)
			{
				final Derivation rootDeriv = rootDerivs.get(i);
				res[i] = rootDeriv.score + bonus * rootDeriv.compatibility;
			}
			NumUtils.expNormalize(res);
			return res;
		}
	}

	//A heuristic for choosing the oracle derivation. It'd be good to get rid of this or simplify
	public static class CorrectDerivationComparator implements Comparator<Derivation>
	{
		@Override
		public int compare(final Derivation deriv1, final Derivation deriv2)
		{
			if (deriv1.compatibility > deriv2.compatibility)
				return -1;
			if (deriv1.compatibility < deriv2.compatibility)
				return +1;

			final boolean deriv1Join = containsJoin(deriv1);
			final boolean deriv2Join = containsJoin(deriv2);
			if (deriv1Join && !deriv2Join)
				return -1;
			if (!deriv1Join && deriv2Join)
				return +1;
			// by score
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

		private boolean containsJoin(final Derivation d)
		{
			final SemanticFn semanticFn = d.rule.getSem();
			if (semanticFn != null)
				if (semanticFn instanceof JoinFn)
					return true;
			for (final Derivation child : d.children)
				if (containsJoin(child))
					return true;
			return false;
		}
	}
}

//holds the stream, the priority, and a probability sum to make grdient computation efficient
class PrioritizedDerivationStream implements Comparable<PrioritizedDerivationStream>, HasScore
{
	public final DerivationStream derivStream;
	public final double priority;
	public double probSum;

	PrioritizedDerivationStream(final DerivationStream derivStream_, final double priority_, final double probSum_)
	{
		derivStream = derivStream_;
		priority = priority_;
		probSum = probSum_;
	}

	@Override
	public int compareTo(final PrioritizedDerivationStream o)
	{
		if (priority > o.priority)
			return -1;
		if (priority < o.priority)
			return +1;
		return 0;
	}

	@Override
	public double getScore()
	{
		return derivStream.peek().score;
	}

	public void addProb(final double prob)
	{
		probSum += prob;
	}
}

class DerivInfo
{
	public final String cat;
	public final int start;
	public final int end;
	public final Formula formula;
	public final Rule rule;

	DerivInfo(final String cat_, final int start_, final int end_, final Formula formula_, final Rule rule_)
	{
		cat = cat_;
		start = start_;
		end = end_;
		formula = formula_;
		rule = rule_;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;

		final DerivInfo derivInfo = (DerivInfo) o;

		if (end != derivInfo.end)
			return false;
		if (start != derivInfo.start)
			return false;
		if (cat != null ? !cat.equals(derivInfo.cat) : derivInfo.cat != null)
			return false;
		if (formula != null ? !formula.equals(derivInfo.formula) : derivInfo.formula != null)
			return false;
		return !(rule != null ? !rule.equals(derivInfo.rule) : derivInfo.rule != null);

	}

	@Override
	public int hashCode()
	{
		int result = cat != null ? cat.hashCode() : 0;
		result = 31 * result + start;
		result = 31 * result + end;
		result = 31 * result + (formula != null ? formula.hashCode() : 0);
		result = 31 * result + (rule != null ? rule.hashCode() : 0);
		return result;
	}

	@Override
	public String toString()
	{
		return cat + "(" + start + "," + end + ") " + formula.toString();
	}
}
