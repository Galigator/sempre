package edu.stanford.nlp.sempre;

import com.google.common.collect.Sets;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.StopWatchSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contains methods for putting derivations on the chart and combining them to add new derivations to the agenda
 * 
 * @author joberant
 */
abstract class AbstractReinforcementParserState extends ChartParserState
{

	protected final ReinforcementParser _parser;
	protected final CoarseParser coarseParser;
	protected CoarseParser.CoarseParserState coarseParserState;
	protected static final double EPSILON = 10e-20; // used to break ties between agenda items

	public AbstractReinforcementParserState(final ReinforcementParser parser_, final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		super(parser_, params, ex, computeExpectedCounts);
		_parser = parser_;
		coarseParser = parser_.coarseParser;
	}

	protected abstract void addToAgenda(DerivationStream derivationStream);

	protected boolean coarseAllows(final String cat, final int start, final int end)
	{
		return coarseParserState == null || coarseParserState.coarseAllows(cat, start, end);
	}

	//don't add to a cell in the chart that is fill
	protected boolean addToBoundedChart(final Derivation deriv)
	{

		List<Derivation> derivations = chart[deriv.start][deriv.end].get(deriv.cat);
		totalGeneratedDerivs++;
		if (Parser.opts.visualizeChartFilling)
			chartFillingList.add(new CatSpan(deriv.start, deriv.end, deriv.cat));
		if (derivations == null)
			chart[deriv.start][deriv.end].put(deriv.cat, derivations = new ArrayList<>());
		if (derivations.size() < getBeamSize())
		{
			derivations.add(deriv);
			Collections.sort(derivations, Derivation.derivScoreComparator); // todo - perhaps can be removed
			return true;
		}
		else
			return false;
	}

	// for [start, end) we try to create [start, end + i) or [start - i, end) and add unary rules
	protected void combineWithChartDerivations(final Derivation deriv)
	{
		expandDerivRightwards(deriv);
		expandDerivLeftwards(deriv);
		applyCatUnaryRules(deriv);
	}

	private void expandDerivRightwards(final Derivation leftChild)
	{
		if (_parser.verbose(6))
			LogInfo.begin_track("Expanding rightward");
		final Map<String, List<Rule>> rhsCategoriesToRules = _parser.leftToRightSiblingMap.get(leftChild.cat);
		if (rhsCategoriesToRules != null)
		{
			for (int i = 1; leftChild.end + i <= numTokens; ++i)
			{
				final Set<String> intersection = Sets.intersection(rhsCategoriesToRules.keySet(), chart[leftChild.end][leftChild.end + i].keySet());

				for (final String rhsCategory : intersection)
				{
					final List<Rule> compatibleRules = rhsCategoriesToRules.get(rhsCategory);
					final List<Derivation> rightChildren = chart[leftChild.end][leftChild.end + i].get(rhsCategory);
					generateParentDerivations(leftChild, rightChildren, true, compatibleRules);
				}
			}
			// handle terminals
			if (leftChild.end < numTokens)
				handleTerminalExpansion(leftChild, false, rhsCategoriesToRules);
		}
		if (_parser.verbose(6))
			LogInfo.end_track();
	}

	private void expandDerivLeftwards(final Derivation rightChild)
	{
		if (_parser.verbose(5))
			LogInfo.begin_track("Expanding leftward");
		final Map<String, List<Rule>> lhsCategorisToRules = _parser.rightToLeftSiblingMap.get(rightChild.cat);
		if (lhsCategorisToRules != null)
		{
			for (int i = 1; rightChild.start - i >= 0; ++i)
			{
				final Set<String> intersection = Sets.intersection(lhsCategorisToRules.keySet(), chart[rightChild.start - i][rightChild.start].keySet());

				for (final String lhsCategory : intersection)
				{
					final List<Rule> compatibleRules = lhsCategorisToRules.get(lhsCategory);
					final List<Derivation> leftChildren = chart[rightChild.start - i][rightChild.start].get(lhsCategory);
					generateParentDerivations(rightChild, leftChildren, false, compatibleRules);
				}
			}
			// handle terminals
			if (rightChild.start > 0)
				handleTerminalExpansion(rightChild, true, lhsCategorisToRules);
		}
		if (_parser.verbose(5))
			LogInfo.end_track();
	}

	private void generateParentDerivations(final Derivation expandedDeriv, final List<Derivation> otherDerivs, final boolean expandedLeftChild, final List<Rule> compatibleRules)
	{

		for (final Derivation otherDeriv : otherDerivs)
		{
			Derivation leftChild, rightChild;
			if (expandedLeftChild)
			{
				leftChild = expandedDeriv;
				rightChild = otherDeriv;
			}
			else
			{
				leftChild = otherDeriv;
				rightChild = expandedDeriv;
			}
			final List<Derivation> children = new ArrayList<>();
			children.add(leftChild);
			children.add(rightChild);
			for (final Rule rule : compatibleRules)
				if (coarseAllows(rule.lhs, leftChild.start, rightChild.end))
				{
					final DerivationStream resDerivations = applyRule(leftChild.start, rightChild.end, rule, children);

					if (!resDerivations.hasNext())
						continue;
					addToAgenda(resDerivations);
				}
		}
	}

	// returns the score of derivation computed
	private DerivationStream applyRule(final int start, final int end, final Rule rule, final List<Derivation> children)
	{
		try
		{
			if (Parser.opts.verbose >= 5)
				LogInfo.logs("applyRule %s %s %s %s", start, end, rule, children);
			StopWatchSet.begin(rule.getSemRepn()); // measuring time
			StopWatchSet.begin(rule.toString());
			final DerivationStream results = rule.sem.call(ex, new SemanticFn.CallInfo(rule.lhs, start, end, rule, com.google.common.collect.ImmutableList.copyOf(children)));
			StopWatchSet.end();
			StopWatchSet.end();
			return results;
		}
		catch (final Exception e)
		{
			LogInfo.errors("Composition failed: rule = %s, children = %s", rule, children);
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private void applyCatUnaryRules(final Derivation deriv)
	{
		if (_parser.verbose(4))
			LogInfo.begin_track("Category unary rules");
		for (final Rule rule : _parser.catUnaryRules)
		{
			if (!coarseAllows(rule.lhs, deriv.start, deriv.end))
				continue;
			if (deriv.cat.equals(rule.rhs.get(0)))
			{
				final DerivationStream resDerivations = applyRule(deriv.start, deriv.end, rule, Collections.singletonList(deriv));
				addToAgenda(resDerivations);
			}
		}
		if (_parser.verbose(4))
			LogInfo.end_track();
	}

	public List<DerivationStream> gatherRhsTerminalsDerivations()
	{
		final List<DerivationStream> derivs = new ArrayList<>();
		final List<Derivation> empty = Collections.emptyList();

		for (int i = 0; i < numTokens; i++)
			for (int j = i + 1; j <= numTokens; j++)
				for (final Rule rule : MapUtils.get(_parser.terminalsToRulesList, phrases[i][j], Collections.<Rule> emptyList()))
				{
					if (!coarseAllows(rule.lhs, i, j))
						continue;
					derivs.add(applyRule(i, j, rule, empty));
				}
		return derivs;
	}

	// rules where one word is a terminal and the other is a non-terminal
	private void handleTerminalExpansion(final Derivation child, final boolean before, final Map<String, List<Rule>> categoriesToRules)
	{

		final String phrase = before ? phrases[child.start - 1][child.start] : phrases[child.end][child.end + 1];
		final int start = before ? child.start - 1 : child.start;
		final int end = before ? child.end : child.end + 1;

		if (categoriesToRules.containsKey(phrase))
		{
			final List<Derivation> children = new ArrayList<>();
			children.add(child);
			for (final Rule rule : categoriesToRules.get(phrase))
				if (coarseAllows(rule.lhs, start, end))
				{
					final DerivationStream resDerivations = applyRule(start, end, rule, children);
					if (!resDerivations.hasNext())
						continue;
					addToAgenda(resDerivations);
				}
		}
	}
}
