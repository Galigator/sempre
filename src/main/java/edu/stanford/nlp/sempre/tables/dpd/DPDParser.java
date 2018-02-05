package edu.stanford.nlp.sempre.tables.dpd;

import edu.stanford.nlp.sempre.CatSizeBound;
import edu.stanford.nlp.sempre.ChildDerivationsGroup;
import edu.stanford.nlp.sempre.DefaultDerivationPruningComputer;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationPruner;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FloatingParser;
import edu.stanford.nlp.sempre.FloatingRuleUtils;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.PairListValue;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.Parser;
import edu.stanford.nlp.sempre.ParserState;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.DenotationTypeInference;
import edu.stanford.nlp.sempre.tables.InfiniteListValue;
import edu.stanford.nlp.sempre.tables.ScopedValue;
import edu.stanford.nlp.sempre.tables.TableDerivationPruningComputer;
import edu.stanford.nlp.sempre.tables.grow.ApplyFn;
import fig.basic.ListUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.Pair;
import fig.basic.StopWatch;
import fig.basic.StopWatchSet;
import fig.basic.ValueComparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A DPDParser parses utterances like a FloatingParser, but the dynamic programming states also include the *denotation* in addition to (start,end) or
 * depth/size. DPDParser makes 2 passes: - Pass 1: Find the parse paths that lead to the correct final denotation. - Pass 2: Use regular beam search (from
 * FloatingParser), but only restrict the parse paths to the ones found in Pass 1.
 *
 * @author ppasupat
 */
public class DPDParser extends FloatingParser
{
	public static class Options
	{
		@Option(gloss = "Use the targetValue at test time")
		public boolean cheat = false;
		@Option(gloss = "During training, use FloatingParser instead (don't use DPDParser at all)")
		public boolean useFloatingParserForTrain = false;
		@Option(gloss = "During training, combine the derivation list from FloatingParser")
		public boolean combineFromFloatingParser = false;
		@Option(gloss = "Random object for shuffling the derivation list")
		public Random shuffleRandom = new Random(1);
		@Option(gloss = "Custom maximum depth for DPDParser (default = FloatingParser's maxDepth)")
		public int dpdParserMaxDepth = -1;
		@Option(gloss = "Custom beam size for DPDParser (default = FloatingParser's beamSize)")
		public int dpdParserBeamSize = -1;
		@Option(gloss = "Prune the cells in first pass")
		public int firstPassBeamSize = -1;
		@Option(gloss = "Stop the current pass if the number of (cell, denotation) pairs exceeds this number")
		public int maxNumCellDenotations = 5000000;
		@Option(gloss = "Stop the current pass if it has used more than this amount of time (in seconds)")
		public int maxDPDParsingTime = 600;
		@Option(gloss = "Allowed pruning strategies in first pass (must not depend on actual formulas)")
		public List<String> allowedPrunersInFirstPass = new ArrayList<>(Arrays.asList(DefaultDerivationPruningComputer.emptyDenotation, DefaultDerivationPruningComputer.nonLambdaError, DefaultDerivationPruningComputer.mistypedMerge, DefaultDerivationPruningComputer.badSummarizerHead, TableDerivationPruningComputer.lambdaDCSError, TableDerivationPruningComputer.subsetMerge, TableDerivationPruningComputer.sameMark, TableDerivationPruningComputer.subsetMerge, TableDerivationPruningComputer.aggregateInfinite, TableDerivationPruningComputer.aggregateUncomparable));
		@Option(gloss = "Use all pruning strategies if only one formula can produce the denotation")
		public boolean aggressivelyPruneSingleFormulas = true;
		// Debugging flags
		@Option(gloss = "DEBUG: Put the cell name in the canonical utterance of final derivations")
		public boolean putCellNameInCanonicalUtterance = false;
		@Option(gloss = "DEBUG: Dump denotations after each pass")
		public DumpSpec dumpDenotations = DumpSpec.NONE;
		@Option(gloss = "DEBUG: Dump allowed ingredients after the first pass")
		public boolean dumpAllowedIngredients = false;
		@Option(gloss = "DEBUG: Summarize denotations after each pass")
		public boolean summarizeDenotations = false;
		@Option(gloss = "DEBUG: Count the number of useful unique-denotations and cell-denotations")
		public boolean summarizeCountUseful = false;
		@Option(gloss = "DEBUG: Do not do first pass; allow any ingredients in the second pass")
		public boolean ignoreFirstPass = false;
	}

	public static Options opts = new Options();

	public static enum DumpSpec
	{
		NONE, UNIQUE, NONERROR, ALL, FORMULA
	}

	public DPDParser(final Spec spec)
	{
		super(spec);
	}

	@Override
	public ParserState newParserState(final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		if (computeExpectedCounts)
		{ // Training
			// Only use floating?
			if (opts.useFloatingParserForTrain)
				return super.newParserState(params, ex, computeExpectedCounts);
			// Use mixture?
			if (opts.combineFromFloatingParser)
				return new DPDParserState(this, params, ex, computeExpectedCounts, super.newParserState(params, ex, computeExpectedCounts));
			// Otherwise, just use DPDParser (look at denotation)
			return new DPDParserState(this, params, ex, computeExpectedCounts);
		}
		else
		{ // Test
			// Cheat by looking at denotation?
			if (opts.cheat)
				return new DPDParserState(this, params, ex, computeExpectedCounts);
			// Otherwise, don't cheat and just use floating parser
			return super.newParserState(params, ex, computeExpectedCounts);
		}
	}

}

/**
 * Actual parsing logic.
 */
class DPDParserState extends ParserState
{

	private final DerivationPruner pruner;
	private final int maxDepth, beamSize;
	private final CatSizeBound catSizeBound;
	private final ParserState backoffParserState;
	private long firstPassParseTime, secondPassParseTime;
	private boolean timeout = false;

	private Map<Rule, Long> ruleTime;

	public DPDParserState(final DPDParser parser, final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		this(parser, params, ex, computeExpectedCounts, null);
	}

	public DPDParserState(final DPDParser parser, final Params params, final Example ex, final boolean computeExpectedCounts, final ParserState backoff)
	{
		super(parser, params, ex, computeExpectedCounts);
		pruner = new DerivationPruner(this);
		maxDepth = DPDParser.opts.dpdParserMaxDepth > 0 ? DPDParser.opts.dpdParserMaxDepth : FloatingParser.opts.maxDepth;
		beamSize = DPDParser.opts.dpdParserBeamSize > 0 ? DPDParser.opts.dpdParserBeamSize : Parser.opts.beamSize;
		catSizeBound = new CatSizeBound(maxDepth, parser.grammar);
		backoffParserState = backoff;
	}

	@Override
	protected int getBeamSize()
	{
		return beamSize;
	}

	protected void ensureExecuted(final Derivation deriv)
	{
		deriv.ensureExecuted(parser.executor, ex.context);
		if (!deriv.isFeaturizedAndScored() && currentPass != ParsingPass.FIRST)
			featurizeAndScoreDerivation(deriv);
	}

	// ============================================================
	// Dynamic programming cells
	// ============================================================

	// Pass 1: Just try to reach the correct denotation
	//   state name => denotation => FirstPassData
	private final Map<String, Map<Value, Metadata>> firstPassCells = new HashMap<>();
	// Pass 2: Using results from Pass 1 to prune the possible formulas
	//   state name => denotation => SecondPassData
	private final Map<String, Map<Value, Metadata>> secondPassCells = new HashMap<>();

	enum ParsingPass
	{
		FIRST, SECOND, DONE
	};

	ParsingPass currentPass = ParsingPass.FIRST;

	private Map<String, Map<Value, Metadata>> getCellsForCurrentPass()
	{
		return currentPass == ParsingPass.FIRST ? firstPassCells : secondPassCells;
	}

	// ============================================================
	// DenotationIngredient
	// ============================================================

	// Represents a possible method for creating a particular denotation in a particular cell.
	class Ingredient
	{
		public final String parentCell;
		public final Rule rule;
		public final Value child1, child2;
		private final int hashCode;

		public Ingredient(final String parentCell, final Rule rule, final Derivation deriv1, final Derivation deriv2)
		{
			this.parentCell = parentCell;
			this.rule = rule;
			if (deriv1 == null)
				child1 = null;
			else
			{
				ensureExecuted(deriv1);
				child1 = deriv1.value;
			}
			if (deriv2 == null)
				child2 = null;
			else
			{
				ensureExecuted(deriv2);
				child2 = deriv2.value;
			}
			hashCode = parentCell.hashCode() + (rule == null ? 0 : rule.hashCode() * 1729) + (child1 == null ? 0 : child1.hashCode() * 42) + (child2 == null ? 0 : child2.hashCode() * 345);
		}

		public Ingredient(final String parentCell)
		{
			this(parentCell, null, null, null);
		}

		@Override
		public boolean equals(final Object o)
		{
			if (!(o instanceof Ingredient))
				return false;
			final Ingredient that = (Ingredient) o;
			if (!parentCell.equals(that.parentCell))
				return false;
			if (rule != that.rule)
				return false; // Rules must be the same object
			if (child1 == null && that.child1 != null || child1 != null && !child1.equals(that.child1))
				return false;
			if (child2 == null && that.child2 != null || child2 != null && !child2.equals(that.child2))
				return false;
			return true;
		}

		@Override
		public int hashCode()
		{
			return hashCode;
		}

		@Override
		public String toString()
		{
			String cellName = parentCell;
			if (cellName.contains(":"))
			{
				final String[] parts = cellName.split(":");
				assert parts.length == 2;
				cellName = String.format("&%2s:%s", parts[1], parts[0]);
			}
			return new StringBuilder().append("[ ").append(cellName).append(" | ").append(rule).append(" | ").append(child1).append(" | ").append(child2).append(" ]").toString();
		}
	}

	private final Set<Ingredient> allowedIngredients = new HashSet<>();

	// ============================================================
	// BackPointer
	// ============================================================

	// Back pointer for dynamic programming. Points to a child cell.
	class BackPointer
	{
		public final String cell;
		public final Value value;

		public BackPointer(final String cell, final Value denotation)
		{
			this.cell = cell;
			value = denotation;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (!(o instanceof BackPointer))
				return false;
			final BackPointer that = (BackPointer) o;
			return cell.equals(that.cell) && value.equals(that.value);
		}

		@Override
		public int hashCode()
		{
			return cell.hashCode() * 100 + value.hashCode();
		}

		@Override
		public String toString()
		{
			return cell + " " + value;
		}

		public boolean isSingleFormula()
		{
			return getCellsForCurrentPass().get(cell).get(value).singleFormula;
		}
	}

	public BackPointer getBackPointer(final String cell, final Derivation child)
	{
		if (cell == null || child == null)
			return null;
		ensureExecuted(child);
		return new BackPointer(cell, child.value);
	}

	// ============================================================
	// Metadata
	// ============================================================

	// Stores derivations and other data
	class Metadata
	{
		public final Value value;
		// List of possible parse paths to create this value
		public Set<Ingredient> possibleIngredients = new HashSet<>();
		// Backpointers for backtracking after the first pass
		public Set<BackPointer> backPointers = new HashSet<>();
		// All derivations containing formulas that execute to the denotation.
		// For the FIRST pass, this has only 1 formula.
		// For the SECOND pass, this will eventually be pruned to the beam size.
		public List<Derivation> derivations = new ArrayList<>();
		// Whether there is only one possible formula that executes to the denotation.
		// (only used during the first pass)
		// If so, we can apply any pruning heuristic on the formulas built upon this formula.
		public boolean singleFormula = true;

		public Metadata(final Value value)
		{
			this.value = value;
		}

		public void add(final Derivation deriv, final Ingredient ingredient, final BackPointer bp1, final BackPointer bp2)
		{
			if (currentPass == ParsingPass.FIRST)
			{
				if (derivations.isEmpty())
				{
					if (Parser.opts.verbose >= 3)
						LogInfo.logs("Metadata.add: %s %s", value, deriv);
					derivations.add(deriv);
				}
				else
					if (!derivations.get(0).formula.equals(deriv.formula))
						singleFormula = false;
				if (Parser.opts.verbose >= 3)
					LogInfo.logs("possibleIngredients.add: %s", ingredient);
				possibleIngredients.add(ingredient);
				if (bp1 != null)
				{
					backPointers.add(bp1);
					if (!bp1.isSingleFormula())
						singleFormula = false;
				}
				if (bp2 != null)
				{
					backPointers.add(bp2);
					if (!bp2.isSingleFormula())
						singleFormula = false;
				}
			}
			else
				if (currentPass == ParsingPass.SECOND)
				{
					derivations.add(deriv);
					singleFormula = derivations.size() == 1;
				}
		}
	}

	// ============================================================
	// Add to Chart
	// ============================================================

	private void addToChart(final Derivation deriv, final Ingredient ingredient, final BackPointer bp1, final BackPointer bp2)
	{
		if (Parser.opts.verbose >= 3)
			LogInfo.logs("addToChart %s %s: %s", ingredient.parentCell, deriv.value, deriv);
		ensureExecuted(deriv);
		final Map<String, Map<Value, Metadata>> cells = getCellsForCurrentPass();
		Map<Value, Metadata> denotationToData = cells.get(ingredient.parentCell);
		if (denotationToData == null)
			cells.put(ingredient.parentCell, denotationToData = new HashMap<>());
		Metadata metadata = denotationToData.get(deriv.value);
		if (metadata == null)
			denotationToData.put(deriv.value, metadata = new Metadata(deriv.value));
		metadata.add(deriv, ingredient, bp1, bp2);
	}

	private String anchoredCell(final String cat, final int start, final int end)
	{
		return (cat + "[" + start + "," + end + "]").intern();
	}

	private String floatingCell(final String cat, final int depth)
	{
		return (cat + ":" + depth).intern();
	}

	// ============================================================
	// Apply Rule
	// ============================================================

	private boolean isRootRule(final Rule rule)
	{
		return Rule.rootCat.equals(rule.lhs);
	}

	private boolean applyRule(final Rule rule, final int start, final int end, final int depth, final String cell1, final Derivation child1, final String cell2, final Derivation child2)
	{
		if (timeout && !isRootRule(rule))
			return false;
		applyRuleActual(rule, start, end, depth, cell1, child1, cell2, child2);
		return true;
	}

	private void applyRuleActual(final Rule rule, final int start, final int end, final int depth, final String cell1, final Derivation child1, final String cell2, final Derivation child2)
	{
		if (Parser.opts.verbose >= 5)
			LogInfo.logs("applyRule %s [%s:%s] depth=%s, %s %s", rule, start, end, depth, child1, child2);

		Ingredient anchoredIngredient = null, floatingIngredient = null;
		if (depth == -1)
		{
			anchoredIngredient = new Ingredient(anchoredCell(rule.lhs, start, end), rule, child1, child2);
			floatingIngredient = new Ingredient(floatingCell(rule.lhs, 0), rule, child1, child2);
		}
		else
			floatingIngredient = new Ingredient(floatingCell(rule.lhs, depth), rule, child1, child2);
		if (currentPass == ParsingPass.SECOND && !DPDParser.opts.ignoreFirstPass)
			// Prune invalid ingredient
			if (!allowedIngredients.contains(anchoredIngredient) && !allowedIngredients.contains(floatingIngredient))
				return;
		final BackPointer bp1 = getBackPointer(cell1, child1), bp2 = getBackPointer(cell2, child2);
		final boolean singleFormula = (bp1 == null || bp1.isSingleFormula()) && (bp2 == null || bp2.isSingleFormula());

		List<Derivation> children;
		if (child1 == null) // 0-ary
			children = Collections.emptyList();
		else
			if (child2 == null) // 1-ary
				children = Collections.singletonList(child1);
			else
			{
				children = ListUtils.newList(child1, child2);
				// optionally: ensure that specific anchors are only used once (or K times) per final derivation
				// Cannot impose useAnchorsOnce on the first pass without dropping correct derivations!
				if (currentPass != ParsingPass.FIRST)
					if (FloatingParser.opts.useAnchorsOnce)
					{
						if (FloatingRuleUtils.derivationAnchorsOverlap(child1, child2))
							return;
					}
					else
						if (FloatingParser.opts.useMaxAnchors >= 0)
							if (FloatingRuleUtils.maxNumAnchorOverlaps(child1, child2) > FloatingParser.opts.useMaxAnchors)
								return;
			}

		// Call the semantic function on the children and read the results
		final DerivationStream results = rule.sem.call(ex, new SemanticFn.CallInfo(rule.lhs, start, end, rule, children));
		while (results.hasNext())
		{
			Derivation newDeriv = results.next();
			newDeriv = newDeriv.betaReduction();
			if (DPDParser.opts.aggressivelyPruneSingleFormulas)
			{
				if (currentPass == ParsingPass.FIRST && singleFormula)
				{
					if (pruner.isPruned(newDeriv, null))
						continue;
				}
				else
					if (pruner.isPruned(newDeriv))
						continue;
			}
			else
				if (pruner.isPruned(newDeriv))
					continue;
			if (newDeriv.value instanceof ErrorValue)
				// Assign canonical error value
				newDeriv.value = new DPDErrorValue(newDeriv, rule, child1, child2);
			if (depth == -1)
			{
				// Anchored rule
				addToChart(newDeriv, anchoredIngredient, bp1, bp2);
				addToChart(newDeriv, floatingIngredient, bp1, bp2);
			}
			else
				// Floating rule
				addToChart(newDeriv, floatingIngredient, bp1, bp2);
		}
	}

	private boolean applyAnchoredRule(final Rule rule, final int start, final int end)
	{
		return applyRule(rule, start, end, -1, null, null, null, null);
	}

	private boolean applyAnchoredRule(final Rule rule, final int start, final int end, final String cell1, final Derivation child1)
	{
		return applyRule(rule, start, end, -1, cell1, child1, null, null);
	}

	private boolean applyAnchoredRule(final Rule rule, final int start, final int end, final String cell1, final Derivation child1, final String cell2, final Derivation child2)
	{
		return applyRule(rule, start, end, -1, cell1, child1, cell2, child2);
	}

	private boolean applyFloatingRule(final Rule rule, final int depth)
	{
		return applyRule(rule, -1, -1, depth, null, null, null, null);
	}

	private boolean applyFloatingRule(final Rule rule, final int depth, final String cell1, final Derivation child1)
	{
		return applyRule(rule, -1, -1, depth, cell1, child1, null, null);
	}

	private boolean applyFloatingRule(final Rule rule, final int depth, final String cell1, final Derivation child1, final String cell2, final Derivation child2)
	{
		return applyRule(rule, -1, -1, depth, cell1, child1, cell2, child2);
	}

	// ============================================================
	// Get derivations
	// ============================================================

	private List<Derivation> getDerivations(final Object cell)
	{
		final Map<String, Map<Value, Metadata>> cells = getCellsForCurrentPass();
		final Map<Value, Metadata> denotationToData = cells.get(cell);
		if (denotationToData == null)
			return Collections.emptyList();
		final List<Derivation> derivations = new ArrayList<>();
		for (final Metadata metadata : denotationToData.values())
			derivations.addAll(metadata.derivations);
		return derivations;
	}

	/**
	 * Return a collection of ChildDerivationsGroup. The rule should be applied on all derivations (or all pairs of derivations) in each ChildDerivationsGroup.
	 */
	private Collection<ChildDerivationsGroup> getFilteredDerivations(final Rule rule, final String cell1, final String cell2)
	{
		final List<Derivation> derivations1 = getDerivations(cell1), derivations2 = cell2 == null ? null : getDerivations(cell2);
		if (!FloatingParser.opts.filterChildDerivations)
			return Collections.singleton(new ChildDerivationsGroup(derivations1, derivations2));
		// Try to filter down the number of partial logical forms
		if (rule.getSem().supportFilteringOnTypeData())
			return rule.getSem().getFilteredDerivations(derivations1, derivations2);
		return Collections.singleton(new ChildDerivationsGroup(derivations1, derivations2));
	}

	private Collection<ChildDerivationsGroup> getFilteredDerivations(final Rule rule, final String cell)
	{
		return getFilteredDerivations(rule, cell, null);
	}

	// ============================================================
	// Build Anchored
	// ============================================================

	// Build derivations over span |start|, |end|.
	private void buildAnchored(final int start, final int end)
	{
		// Apply unary tokens on spans (rule $A (a))
		for (final Rule rule : parser.grammar.getRules())
		{
			if (timeout && !isRootRule(rule))
				continue;
			if (!rule.isAnchored())
				continue;
			if (rule.rhs.size() != 1 || rule.isCatUnary())
				continue;
			final boolean match = end - start == 1 && ex.token(start).equals(rule.rhs.get(0));
			if (!match)
				continue;

			final StopWatch stopWatch = new StopWatch().start();
			applyAnchoredRule(rule, start, end);
			ruleTime.put(rule, ruleTime.getOrDefault(rule, 0L) + stopWatch.stop().ms);
		}

		// Apply binaries on spans (rule $A ($B $C)), ...
		for (int mid = start + 1; mid < end; mid++)
			for (final Rule rule : parser.grammar.getRules())
			{
				if (timeout && !isRootRule(rule))
					continue;
				if (!rule.isAnchored())
					continue;
				if (rule.rhs.size() != 2)
					continue;

				final StopWatch stopWatch = new StopWatch().start();
				final String rhs1 = rule.rhs.get(0);
				final String rhs2 = rule.rhs.get(1);
				final boolean match1 = mid - start == 1 && ex.token(start).equals(rhs1);
				final boolean match2 = end - mid == 1 && ex.token(mid).equals(rhs2);

				if (!Rule.isCat(rhs1) && Rule.isCat(rhs2))
				{ // token $Cat
					if (match1)
					{
						final String cell = anchoredCell(rhs2, mid, end);
						final List<Derivation> derivations = getDerivations(cell);
						for (final Derivation deriv : derivations)
							if (!applyAnchoredRule(rule, start, end, cell, deriv))
								break;
					}
				}
				else
					if (Rule.isCat(rhs1) && !Rule.isCat(rhs2))
					{ // $Cat token
						if (match2)
						{
							final String cell = anchoredCell(rhs1, start, mid);
							final List<Derivation> derivations = getDerivations(cell);
							for (final Derivation deriv : derivations)
								if (!applyAnchoredRule(rule, start, end, cell, deriv))
									break;
						}
					}
					else
						if (!Rule.isCat(rhs1) && !Rule.isCat(rhs2))
						{ // token token
							if (match1 && match2)
								if (!applyAnchoredRule(rule, start, end))
									break;
						}
						else
						{ // $Cat $Cat
							final String cell1 = anchoredCell(rhs1, start, mid);
							final String cell2 = anchoredCell(rhs2, mid, end);
							final List<Derivation> derivations1 = getDerivations(cell1);
							final List<Derivation> derivations2 = getDerivations(cell2);
							derivLoop: for (final Derivation deriv1 : derivations1)
								for (final Derivation deriv2 : derivations2)
									if (!applyAnchoredRule(rule, start, end, cell1, deriv1, cell2, deriv2))
										break derivLoop;
						}
				ruleTime.put(rule, ruleTime.getOrDefault(rule, 0L) + stopWatch.stop().ms);
			}

		// Apply unary categories on spans (rule $A ($B))
		// Important: do this in topologically sorted order and after all the binaries are done.
		for (final Rule rule : parser.getCatUnaryRules())
		{
			if (timeout && !isRootRule(rule))
				continue;
			if (!rule.isAnchored())
				continue;

			final StopWatch stopWatch = new StopWatch().start();
			final String cell = anchoredCell(rule.rhs.get(0), start, end);
			final List<Derivation> derivations = getDerivations(cell);
			for (final Derivation deriv : derivations)
				if (!applyAnchoredRule(rule, start, end, cell, deriv))
					break;
			ruleTime.put(rule, ruleTime.getOrDefault(rule, 0L) + stopWatch.stop().ms);
		}
	}

	// ============================================================
	// Build Floating
	// ============================================================

	// Build floating derivations of exactly depth |depth|.
	private void buildFloating(final int depth)
	{
		// Build a floating predicate from thin air
		// (rule $A (a)); note that "a" is ignored
		if (depth == 0)
			for (final Rule rule : parser.grammar.getRules())
			{
				if (timeout && !isRootRule(rule))
					continue;
				if (!rule.isFloating())
					continue;
				if (rule.rhs.size() != 1 || rule.isCatUnary())
					continue;

				final StopWatch stopWatch = new StopWatch().start();
				applyFloatingRule(rule, depth);
				ruleTime.put(rule, ruleTime.getOrDefault(rule, 0L) + stopWatch.stop().ms);
			}

		// Apply unary categories on spans (rule $A ($B))
		for (final Rule rule : parser.getCatUnaryRules())
		{
			if (timeout && !isRootRule(rule))
				continue;
			if (!rule.isFloating())
				continue;
			if (catSizeBound.getBound(rule.lhs) < depth)
				continue;

			final StopWatch stopWatch = new StopWatch().start();
			final String cell = floatingCell(rule.rhs.get(0), depth - 1);
			derivLoop: for (final ChildDerivationsGroup group : getFilteredDerivations(rule, cell))
				for (final Derivation deriv : group.derivations1)
					if (!applyFloatingRule(rule, depth, cell, deriv))
						break derivLoop;
			ruleTime.put(rule, ruleTime.getOrDefault(rule, 0L) + stopWatch.stop().ms);
		}

		// Apply binaries on spans (rule $A ($B $C)), ...
		for (final Rule rule : parser.grammar.getRules())
		{
			if (timeout && !isRootRule(rule))
				continue;
			if (!rule.isFloating())
				continue;
			if (rule.rhs.size() != 2)
				continue;
			if (catSizeBound.getBound(rule.lhs) < depth)
				continue;

			final StopWatch stopWatch = new StopWatch().start();
			final String rhs1 = rule.rhs.get(0);
			final String rhs2 = rule.rhs.get(1);
			if (!Rule.isCat(rhs1) || !Rule.isCat(rhs2))
				throw new RuntimeException("Floating rules with > 1 arguments cannot have tokens on the RHS: " + rule);

			if (FloatingParser.opts.useSizeInsteadOfDepth)
				derivLoop: for (int depth1 = 0; depth1 < depth; depth1++)
				{ // sizes must add up to depth-1 (actually size-1)
					final int depth2 = depth - 1 - depth1;
					final String cell1 = floatingCell(rhs1, depth1);
					final String cell2 = floatingCell(rhs2, depth2);
					for (final ChildDerivationsGroup group : getFilteredDerivations(rule, cell1, cell2))
						for (final Derivation deriv1 : group.derivations1)
							for (final Derivation deriv2 : group.derivations2)
								if (!applyFloatingRule(rule, depth, cell1, deriv1, cell2, deriv2))
									break derivLoop;
				}
			else
			{
				{
					derivLoop: for (int subDepth = 0; subDepth < depth; subDepth++)
					{ // depth-1 <=depth-1
						final String cell1 = floatingCell(rhs1, depth - 1);
						final String cell2 = floatingCell(rhs2, subDepth);
						for (final ChildDerivationsGroup group : getFilteredDerivations(rule, cell1, cell2))
							for (final Derivation deriv1 : group.derivations1)
								for (final Derivation deriv2 : group.derivations2)
									if (!applyFloatingRule(rule, depth, cell1, deriv1, cell2, deriv2))
										break derivLoop;
					}
				}
				{
					derivLoop: for (int subDepth = 0; subDepth < depth - 1; subDepth++)
					{ // <depth-1 depth-1
						final String cell1 = floatingCell(rhs1, subDepth);
						final String cell2 = floatingCell(rhs2, depth - 1);
						for (final ChildDerivationsGroup group : getFilteredDerivations(rule, cell1, cell2))
							for (final Derivation deriv1 : group.derivations1)
								for (final Derivation deriv2 : group.derivations2)
									if (!applyFloatingRule(rule, depth, cell1, deriv1, cell2, deriv2))
										break derivLoop;
					}
				}
			}
			ruleTime.put(rule, ruleTime.getOrDefault(rule, 0L) + stopWatch.stop().ms);
		}
	}

	// ============================================================
	// Infer (main entry)
	// ============================================================

	@Override
	public void infer()
	{
		LogInfo.begin_track("DPDParser.infer()");
		StopWatch watch;
		// First pass
		if (!DPDParser.opts.ignoreFirstPass)
		{
			LogInfo.begin_track("First pass");
			StopWatchSet.begin("DPDParser.firstPass");
			watch = new StopWatch().start();
			currentPass = ParsingPass.FIRST;
			ApplyFn.exactScopeHead = false; // TODO: Make this not specific to ApplyFn
			runParsingPass();
			ApplyFn.exactScopeHead = true;
			if (DPDParser.opts.summarizeCountUseful)
				countUseful();
			collectPossibleIngredients();
			firstPassParseTime = watch.stop().getCurrTimeLong();
			StopWatchSet.end();
			LogInfo.end_track();
		}
		// Second pass
		LogInfo.begin_track("Second pass");
		StopWatchSet.begin("DPDParser.secondPass");
		watch = new StopWatch().start();
		currentPass = ParsingPass.SECOND;
		runParsingPass();
		secondPassParseTime = watch.stop().getCurrTimeLong();
		StopWatchSet.end();
		LogInfo.end_track();
		// Collect final derivations
		StopWatchSet.begin("DPDParser.final");
		currentPass = ParsingPass.DONE;
		collectFinalDerivations();
		ensureExecuted();
		if (computeExpectedCounts)
		{
			expectedCounts = new HashMap<>();
			ParserState.computeExpectedCounts(predDerivations, expectedCounts);
		}
		StopWatchSet.end();
		LogInfo.end_track();
	}

	private void runParsingPass()
	{
		// Create a parsing thread and run for some time
		timeout = false;
		final Thread parsingThread = new Thread(new DPDParserParsingThread());
		parsingThread.start();
		try
		{
			parsingThread.join(DPDParser.opts.maxDPDParsingTime * 1000);
			if (parsingThread.isAlive())
			{
				// This will only interrupt first or second passes, not the final candidate collection.
				LogInfo.warnings("Parsing time exceeded %d seconds. Will now interrupt ...", DPDParser.opts.maxDPDParsingTime);
				timeout = true;
				parsingThread.interrupt();
				parsingThread.join();
			}
		}
		catch (final InterruptedException e)
		{
			e.printStackTrace();
			LogInfo.fails("DPDParser error: %s", e);
		}
		evaluation.add((currentPass == ParsingPass.FIRST ? "first" : "second") + "passTimeout", timeout);
	}

	public class DPDParserParsingThread implements Runnable
	{
		@Override
		public void run()
		{
			ruleTime = new HashMap<>();

			final Set<String> categories = new HashSet<>();
			for (final Rule rule : parser.grammar.getRules())
				categories.add(rule.lhs);

			// Set the pruner
			if (currentPass == ParsingPass.FIRST)
				pruner.setCustomAllowedPruningStrategies(DPDParser.opts.allowedPrunersInFirstPass);
			else
				pruner.setCustomAllowedPruningStrategies(null); // All pruners allowed

			// Base case ($TOKEN, $PHRASE, $LEMMA_PHRASE)
			// Denotations are StringValue
			for (final Derivation deriv : gatherTokenAndPhraseDerivations())
			{
				ensureExecuted(deriv);
				addToChart(deriv, new Ingredient(anchoredCell(deriv.cat, deriv.start, deriv.end)), null, null);
			}

			// Build up anchored derivations
			final int numTokens = ex.numTokens();
			for (int len = 1; len <= numTokens; len++)
				for (int i = 0; i + len <= numTokens; i++)
				{
					buildAnchored(i, i + len);
					for (final String cat : categories)
					{
						if (Rule.rootCat.equals(cat))
							continue;
						pruneBeam(anchoredCell(cat, i, i + len));
					}
				}

			// Build up floating derivations
			for (int depth = 0; depth <= maxDepth; depth++)
			{
				if (Parser.opts.verbose >= 1)
					LogInfo.begin_track("(%s) %s = %d", currentPass, FloatingParser.opts.useSizeInsteadOfDepth ? "SIZE" : "DEPTH", depth);
				buildFloating(depth);
				for (final String cat : categories)
				{
					if (Rule.rootCat.equals(cat))
						continue;
					pruneBeam(floatingCell(cat, depth));
				}
				if (Parser.opts.verbose >= 1)
				{
					final Map<String, Integer> statistics = countNumCells(getCellsForCurrentPass());
					LogInfo.logs("(%s) %d cells | %d unique-denotations | %d cell-denotations | %d derivations", currentPass, statistics.get("Cells"), statistics.get("UniqueDenotations"), statistics.get("CellDenotations"), statistics.get("Derivations"));
					LogInfo.end_track();
				}
				final int numCellDenotations = getNumCellDenotations(), maxNumCellDenotations = DPDParser.opts.maxNumCellDenotations;
				if (depth != maxDepth && maxNumCellDenotations >= 0 && maxNumCellDenotations < numCellDenotations)
				{
					LogInfo.logs("Stop parsing: number of (cell, denotation) pairs is %d > %d", numCellDenotations, maxNumCellDenotations);
					break;
				}
			}
			if (DPDParser.opts.dumpDenotations != DPDParser.DumpSpec.NONE)
				dumpDenotations(getCellsForCurrentPass());
			if (DPDParser.opts.summarizeDenotations)
				classifyUniqueDenotations(getCellsForCurrentPass());
			if (FloatingParser.opts.summarizeRuleTime)
				summarizeRuleTime();
		}
	}

	// Prune to the beam size
	private void pruneBeam(final String cell)
	{
		if (currentPass == ParsingPass.FIRST && DPDParser.opts.firstPassBeamSize > 0)
		{
			final Map<String, Map<Value, Metadata>> cells = getCellsForCurrentPass();
			Map<Value, Metadata> denotationToData = cells.get(cell);
			if (denotationToData == null || denotationToData.size() <= DPDParser.opts.firstPassBeamSize)
				return;
			// TODO: Prune based on some criteria
			if (Parser.opts.verbose >= 1)
				LogInfo.logs("Pruning first pass beam: %d => %d", denotationToData.entrySet().size(), DPDParser.opts.firstPassBeamSize);
			final List<Map.Entry<Value, Metadata>> pruned = new ArrayList<>(denotationToData.entrySet());
			denotationToData = new HashMap<>();
			for (final Map.Entry<Value, Metadata> entry : pruned.subList(0, DPDParser.opts.firstPassBeamSize))
				denotationToData.put(entry.getKey(), entry.getValue());
			cells.put(cell, denotationToData);
		}
		else
			if (currentPass == ParsingPass.SECOND)
			{
				final Map<String, Map<Value, Metadata>> cells = getCellsForCurrentPass();
				final Map<Value, Metadata> denotationToData = cells.get(cell);
				if (denotationToData == null)
					return;
				for (final Metadata metadata : denotationToData.values())
					pruneCell(cell, metadata.derivations);
			}
	}

	// ============================================================
	// Collect ingredients (after FIRST pass)
	// ============================================================

	private void collectPossibleIngredients()
	{
		if (Parser.opts.verbose >= 4)
			LogInfo.logs("DPDParserState.collectPossibleIngredients()");
		final Set<BackPointer> usedBps = new HashSet<>();
		collectPossibleIngredients(anchoredCell(Rule.rootCat, 0, numTokens), usedBps);
		for (int depth = 1; depth <= maxDepth; depth++)
			collectPossibleIngredients(floatingCell(Rule.rootCat, depth), usedBps);
		if (Parser.opts.verbose >= 4 || DPDParser.opts.dumpAllowedIngredients)
		{
			LogInfo.begin_track("allowedDenotationIngredients");
			final Set<String> sorted = new TreeSet<>();
			for (final Ingredient ingredient : allowedIngredients)
				sorted.add(ingredient.toString());
			for (final String ingredient : sorted)
				LogInfo.logs("%s", ingredient);
			LogInfo.end_track();
		}
	}

	private void collectPossibleIngredients(final String cell, final Set<BackPointer> usedBps)
	{
		if (Parser.opts.verbose >= 4)
			LogInfo.logs("DPDParserState.collectPossibleIngredients(%s)", cell);
		final Map<Value, Metadata> denotationToMetadata = firstPassCells.get(cell);
		if (denotationToMetadata == null)
			return;
		for (final Value denotation : denotationToMetadata.keySet())
		{
			final double compatibility = parser.valueEvaluator.getCompatibility(ex.targetValue, denotation);
			if (compatibility != 1)
				continue;
			if (Parser.opts.verbose >= 2)
				LogInfo.logs("[%f] %s", compatibility, denotationToMetadata.get(denotation).derivations.get(0));
			final BackPointer bp = new BackPointer(cell, denotation);
			if (!usedBps.contains(bp))
				collectPossibleIngredients(bp, usedBps, 0);
		}
	}

	private void collectPossibleIngredients(final BackPointer bp, final Set<BackPointer> usedBps, final int depth)
	{
		if (Parser.opts.verbose >= 4)
			LogInfo.logs("DPDParserState.collectPossibleIngredients(%s)", bp);
		usedBps.add(bp);
		final Map<Value, Metadata> denotationToMetadata = firstPassCells.get(bp.cell);
		if (denotationToMetadata == null)
			return;
		final Metadata metadata = denotationToMetadata.get(bp.value);
		if (metadata == null)
			return;
		allowedIngredients.addAll(metadata.possibleIngredients);
		if (Parser.opts.verbose >= 4)
			LogInfo.logs("Adding %s", metadata.possibleIngredients);
		// Recurse
		for (final BackPointer childBp : metadata.backPointers)
			if (!usedBps.contains(childBp))
				collectPossibleIngredients(childBp, usedBps, depth + 1);
	}

	// ============================================================
	// Collect final derivations (after SECOND pass)
	// ============================================================

	private void collectFinalDerivations()
	{
		String cellName = anchoredCell(Rule.rootCat, 0, numTokens);
		for (final Derivation deriv : getDerivations(cellName))
		{
			if (DPDParser.opts.putCellNameInCanonicalUtterance)
				deriv.canonicalUtterance = cellName;
			predDerivations.add(deriv);
		}
		for (int depth = 0; depth <= maxDepth; depth++)
		{
			cellName = floatingCell(Rule.rootCat, depth);
			for (final Derivation deriv : getDerivations(cellName))
			{
				if (DPDParser.opts.putCellNameInCanonicalUtterance)
					deriv.canonicalUtterance = cellName;
				predDerivations.add(deriv);
			}
		}
		if (backoffParserState != null)
		{
			// Also combine derivations from the backoff parser state
			LogInfo.begin_track("Backoff ParserState");
			backoffParserState.infer();
			predDerivations.addAll(backoffParserState.predDerivations);
			// Prevent oracles from always being at the front.
			Collections.shuffle(predDerivations, DPDParser.opts.shuffleRandom);
			LogInfo.end_track();
		}
	}

	// ============================================================
	// Collect statistics
	// ============================================================

	// Collect the statistics and put them into the Evaluation object
	@Override
	protected void setEvaluation()
	{
		super.setEvaluation();
		// Parse times
		evaluation.add("firstPassParseTime", firstPassParseTime);
		evaluation.add("secondPassParseTime", secondPassParseTime);
		// Number of cells
		for (final Map.Entry<String, Integer> entry : countNumCells(firstPassCells).entrySet())
			evaluation.add("firstPass" + entry.getKey(), entry.getValue());
		for (final Map.Entry<String, Integer> entry : countNumCells(secondPassCells).entrySet())
			evaluation.add("secondPass" + entry.getKey(), entry.getValue());
		// Number of possible ingredients
		evaluation.add("allowedIngredients", allowedIngredients.size());
	}

	private Map<String, Integer> countNumCells(final Map<String, Map<Value, Metadata>> cells)
	{
		int numAnchored = 0, numFloating = 0, numDenotations = 0, numErrorDenotations = 0, numUniqueErrorDenotations = 0, numDerivations = 0;
		final Set<Value> uniqueDenotations = new HashSet<>();
		for (final Map.Entry<String, Map<Value, Metadata>> entry : cells.entrySet())
		{
			if (entry.getKey().contains(","))
				numAnchored++;
			else
				numFloating++;
			for (final Map.Entry<Value, Metadata> subentry : entry.getValue().entrySet())
			{
				final Value denotation = subentry.getKey();
				numDenotations++;
				uniqueDenotations.add(denotation);
				if (denotation instanceof ErrorValue)
					numErrorDenotations++;
				numDerivations += subentry.getValue().derivations.size();
			}
		}
		for (final Value denotation : uniqueDenotations)
			if (denotation instanceof ErrorValue)
				numUniqueErrorDenotations++;
		final Map<String, Integer> statistics = new HashMap<>();
		statistics.put("Cells", cells.size());
		statistics.put("Anchored", numAnchored);
		statistics.put("Floating", numFloating);
		statistics.put("CellDenotations", numDenotations);
		statistics.put("ErrorDenotations", numErrorDenotations);
		statistics.put("UniqueDenotations", uniqueDenotations.size());
		statistics.put("UniqueErrorDenotations", numUniqueErrorDenotations);
		statistics.put("Derivations", numDerivations);
		return statistics;
	}

	private int getNumCellDenotations()
	{
		int numDenotations = 0;
		for (final Map<Value, Metadata> value : getCellsForCurrentPass().values())
			numDenotations += value.size();
		return numDenotations;
	}

	// ============================================================
	// Debug: print all denotations in all cells
	// ============================================================

	protected void dumpDenotations(final Map<String, Map<Value, Metadata>> cells)
	{
		final Map<String, Formula> denotationToSampleFormula = new TreeMap<>();
		for (final Map.Entry<String, Map<Value, Metadata>> entry : cells.entrySet())
		{
			String cellName = entry.getKey();
			if (DPDParser.opts.dumpDenotations == DPDParser.DumpSpec.NONERROR && Grammar.isIntermediate(cellName))
				continue;
			if (cellName.contains(":"))
			{
				final String[] parts = cellName.split(":");
				assert parts.length == 2;
				cellName = String.format("&%2s:%s", parts[1], parts[0]);
			}
			for (final Map.Entry<Value, Metadata> subentry : entry.getValue().entrySet())
			{
				final Value denotation = subentry.getKey();
				String key = null;
				switch (DPDParser.opts.dumpDenotations)
				{
					case UNIQUE:
						key = denotation.toString();
						break;
					case NONERROR:
						if (denotation instanceof ErrorValue || denotation instanceof DPDErrorValue)
							continue;
						key = cellName + " | " + denotation;
						break;
					case ALL:
						key = cellName + " | " + denotation;
						break;
					default:
						throw new RuntimeException("Unknown dump option: " + DPDParser.opts.dumpDenotations);
				}
				if (!denotationToSampleFormula.containsKey(key))
					denotationToSampleFormula.put(key, subentry.getValue().derivations.get(0).formula);
			}
		}
		LogInfo.begin_track("%s DENOTATIONS", DPDParser.opts.dumpDenotations);
		for (final Map.Entry<String, Formula> entry : denotationToSampleFormula.entrySet())
			LogInfo.logs("%s | %s", entry.getKey(), entry.getValue());
		LogInfo.end_track();
	}

	// ============================================================
	// Debug: classify unique-denotations by attributes
	// ============================================================

	protected void classifyUniqueDenotations(final Map<String, Map<Value, Metadata>> cells)
	{
		final Set<String> denotations = new HashSet<>();
		final Map<String, Integer> attributeCounter = new TreeMap<>();
		for (final Map.Entry<String, Map<Value, Metadata>> entry : cells.entrySet())
			for (final Map.Entry<Value, Metadata> subentry : entry.getValue().entrySet())
			{
				final Value denotation = subentry.getKey();
				final String key = denotation.toString();
				if (denotations.contains(key))
					continue;
				MapUtils.incr(attributeCounter, getDenotationAttributes(denotation));
				denotations.add(key);
			}
		LogInfo.begin_track("Denotation Classification");
		for (final Map.Entry<String, Integer> entry : attributeCounter.entrySet())
			LogInfo.logs("%7d (%6.2f%%) : %s", entry.getValue(), entry.getValue() * 100.0 / denotations.size(), entry.getKey());
		LogInfo.end_track();
	}

	protected String getDenotationAttributes(final Value denotation)
	{
		final StringBuilder sb = new StringBuilder();
		if (denotation instanceof ListValue || denotation instanceof InfiniteListValue)
		{
			sb.append("L");
			if (denotation instanceof InfiniteListValue)
			{
				sb.append("|size=Inf");
				sb.append("|type=").append(DenotationTypeInference.getValueType(denotation));
			}
			else
			{
				// Size
				final List<Value> values = ((ListValue) denotation).values;
				if (values.size() == 0)
					sb.append("|size=0");
				else
					if (values.size() == 1)
						sb.append("|size=1");
					else
						sb.append("|size=many");
				// Type
				if (!values.isEmpty())
					sb.append("|type=").append(DenotationTypeInference.getValueType(denotation));
			}
		}
		else
			if (denotation instanceof ScopedValue)
			{
				sb.append("S");
				try
				{
					final ListValue head = (ListValue) ((ScopedValue) denotation).head;
					final PairListValue relation = (PairListValue) ((ScopedValue) denotation).relation;
					// Head
					if (head.values.size() == 1)
						sb.append("|hsize=1");
					else
						sb.append("|hsize=many");
					sb.append("|htype=").append(DenotationTypeInference.getKeyType(denotation));
					// Relation
					int relationSize = 0;
					for (final Pair<Value, Value> pair : relation.pairs)
						if (pair.getSecond() instanceof ListValue)
							relationSize = Math.max(relationSize, ((ListValue) pair.getSecond()).values.size());
						else
							if (pair.getSecond() instanceof InfiniteListValue)
								relationSize = Integer.MAX_VALUE;
							else
								throw new RuntimeException();
					if (relationSize == 0)
						sb.append("|vsize=0");
					else
						if (relationSize == 1)
							sb.append("|vsize=1");
						else
							if (relationSize == Integer.MAX_VALUE)
								sb.append("|vsize=Inf");
							else
								sb.append("|vsize=many");
					sb.append("|vtype=").append(DenotationTypeInference.getValueType(denotation));
				}
				catch (final Exception e)
				{
					sb.append("|???=").append(e);
				}
			}
			else
				// Currently PairListValue and ErrorValue, which don't appear in grow grammar,
				// are not handled explicitly.
				sb.append("X=" + denotation.getClass().getSimpleName());
		return sb.toString();
	}

	// ============================================================
	// Debug: count the number of useful unique-denotations and cell-denotations
	// ============================================================

	private void countUseful()
	{
		final Set<String> allUniqueDenotations = new HashSet<>(), usefulUniqueDenotations = new HashSet<>(), allCellDenotations = new HashSet<>(), usefulCellDenotations = new HashSet<>();
		final Set<BackPointer> usedBps = new HashSet<>();
		// All cells and denotations
		for (final Map.Entry<String, Map<Value, Metadata>> entry : firstPassCells.entrySet())
			for (final Value value : entry.getValue().keySet())
			{
				// Unique-denotations
				final String denotation = value.toString();
				allUniqueDenotations.add(denotation);
				// Cell-denotations
				final String cellDenotation = entry.getKey() + " | " + denotation;
				allCellDenotations.add(cellDenotation);
			}
		// Useful unique-denotations and cell-denotations
		findUseful(anchoredCell(Rule.rootCat, 0, numTokens), usedBps, usefulUniqueDenotations, usefulCellDenotations);
		for (int depth = 1; depth <= maxDepth; depth++)
			findUseful(floatingCell(Rule.rootCat, depth), usedBps, usefulUniqueDenotations, usefulCellDenotations);
		// Summarize
		LogInfo.begin_track("countUseful %s", ex.id);
		LogInfo.logs("uniqueDenotations: %d / %d (%.3f%%)", usefulUniqueDenotations.size(), allUniqueDenotations.size(), usefulUniqueDenotations.size() * 100.0 / allUniqueDenotations.size());
		LogInfo.logs("cellDenotations: %d / %d (%.3f%%)", usefulCellDenotations.size(), allCellDenotations.size(), usefulCellDenotations.size() * 100.0 / allCellDenotations.size());
		LogInfo.end_track();
	}

	private void findUseful(final String cell, final Set<BackPointer> usedBps, final Set<String> usefulUniqueDenotations, final Set<String> usefulCellDenotations)
	{
		final Map<Value, Metadata> denotationToMetadata = firstPassCells.get(cell);
		if (denotationToMetadata == null)
			return;
		for (final Value denotation : denotationToMetadata.keySet())
		{
			final BackPointer bp = new BackPointer(cell, denotation);
			findUseful(bp, usedBps, usefulUniqueDenotations, usefulCellDenotations);
		}
	}

	private void findUseful(final BackPointer bp, final Set<BackPointer> usedBps, final Set<String> usefulUniqueDenotations, final Set<String> usefulCellDenotations)
	{
		usedBps.add(bp);
		// Unique-denotations
		final String denotation = bp.value.toString();
		usefulUniqueDenotations.add(denotation);
		// Cell-denotations
		final String cellDenotation = bp.cell + " | " + denotation;
		usefulCellDenotations.add(cellDenotation);
		// Recurse
		final Map<Value, Metadata> denotationToMetadata = firstPassCells.get(bp.cell);
		if (denotationToMetadata == null)
			return;
		final Metadata metadata = denotationToMetadata.get(bp.value);
		if (metadata == null)
			return;
		for (final BackPointer childBp : metadata.backPointers)
			if (!usedBps.contains(childBp))
				findUseful(childBp, usedBps, usefulUniqueDenotations, usefulCellDenotations);
	}

	// ============================================================
	// Debug: summarize the time used in each rule
	// ============================================================

	private void summarizeRuleTime()
	{
		final List<Map.Entry<Rule, Long>> entries = new ArrayList<>(ruleTime.entrySet());
		entries.sort(new ValueComparator<>(true));
		LogInfo.begin_track("(%s) Rule time", currentPass);
		for (final Map.Entry<Rule, Long> entry : entries)
			LogInfo.logs("%9d : %s", entry.getValue(), entry.getKey());
		LogInfo.end_track();
	}
}
