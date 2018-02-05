package edu.stanford.nlp.sempre;

import com.google.common.base.Joiner;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Pair;
import fig.basic.StopWatch;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser that only has information on what categories can parse what spans Does not hold backpointers for getting full parse, only reachability information
 * Important: assumes that the grammar is binary Independent from the Parser code and therefore there is duplicate code (traverse(), keepTopDownReachable())
 * 
 * @author jonathanberant
 */
public class CoarseParser
{

	public final Grammar grammar;
	private final Map<Pair<String, String>, Set<String>> rhsToLhsMap;
	ArrayList<Rule> catUnaryRules; // Unary rules with category on RHS
	Map<String, List<Rule>> terminalsToRulesList = new HashMap<>();

	public CoarseParser(final Grammar grammar_)
	{
		grammar = grammar_;
		catUnaryRules = new ArrayList<>();
		rhsToLhsMap = new HashMap<>();

		final Map<String, List<Rule>> graph = new HashMap<>(); // Node from LHS to list of rules
		for (final Rule rule : grammar.rules)
		{
			if (rule.rhs.size() > 2)
				throw new RuntimeException("We assume that the grammar is binarized, rule: " + rule);
			if (rule.isCatUnary())
				MapUtils.addToList(graph, rule.lhs, rule);
			else
				if (rule.rhs.size() == 2)
					MapUtils.addToSet(rhsToLhsMap, Pair.newPair(rule.rhs.get(0), rule.rhs.get(1)), rule.lhs);
				else
				{
					assert rule.isRhsTerminals();
					MapUtils.addToList(terminalsToRulesList, Joiner.on(' ').join(rule.rhs), rule);
				}
		}
		// Topologically sort catUnaryRules so that B->C occurs before A->B
		final Map<String, Boolean> done = new HashMap<>();
		for (final String node : graph.keySet())
			traverse(catUnaryRules, node, graph, done);
		LogInfo.logs("Coarse parser: %d catUnaryRules (sorted), %d nonCatUnaryRules", catUnaryRules.size(), grammar.rules.size() - catUnaryRules.size());
	}

	/** Helper function for transitive closure of unary rules. */
	private void traverse(final List<Rule> catUnaryRules, final String node, final Map<String, List<Rule>> graph, final Map<String, Boolean> done)
	{
		final Boolean d = done.get(node);
		if (Boolean.TRUE.equals(d))
			return;
		if (Boolean.FALSE.equals(d))
			throw new RuntimeException("Found cycle of unaries involving " + node);
		done.put(node, false);
		for (final Rule rule : MapUtils.getList(graph, node))
		{
			traverse(catUnaryRules, rule.rhs.get(0), graph, done);
			catUnaryRules.add(rule);
		}
		done.put(node, true);
	}

	public CoarseParserState getCoarsePrunedChart(final Example ex)
	{
		final CoarseParserState res = new CoarseParserState(ex, this);
		res.infer();
		return res;
	}

	class CoarseParserState
	{

		private final Map<String, List<CategorySpan>>[][] chart;
		public final Example example;
		public final CoarseParser parser;
		private final int numTokens;
		private long time;
		private final String[][] phrases;

		@SuppressWarnings({ "unchecked" })
		public CoarseParserState(final Example example, final CoarseParser parser)
		{
			this.example = example;
			this.parser = parser;
			numTokens = example.numTokens();
			// Initialize the chart.
			chart = (HashMap<String, List<CategorySpan>>[][]) Array.newInstance(HashMap.class, numTokens, numTokens + 1);
			phrases = new String[numTokens][numTokens + 1];

			for (int start = 0; start < numTokens; start++)
			{
				final StringBuilder sb = new StringBuilder();
				for (int end = start + 1; end <= numTokens; end++)
				{
					if (end - start > 1)
						sb.append(' ');
					sb.append(example.languageInfo.tokens.get(end - 1));
					phrases[start][end] = sb.toString();
					chart[start][end] = new HashMap<>();
				}
			}
		}

		public long getCoarseParseTime()
		{
			return time;
		}

		public void infer()
		{

			final StopWatch watch = new StopWatch();
			watch.start();
			// parse with rules with tokens or RHS
			parseTokensAndPhrases();
			// complete bottom up parsing
			for (int len = 1; len <= numTokens; len++)
				for (int i = 0; i + len <= numTokens; i++)
					build(i, i + len);
			// prune away things that are not reachable from the top
			keepTopDownReachable();
			watch.stop();
			time = watch.getCurrTimeLong();
		}

		public boolean coarseAllows(final String cat, final int start, final int end)
		{
			return chart[start][end].containsKey(cat);
		}

		private void build(final int start, final int end)
		{
			handleBinaryRules(start, end);
			handleUnaryRules(start, end);
		}

		private void parseTokensAndPhrases()
		{
			for (int i = 0; i < numTokens; ++i)
			{
				addToChart(Rule.tokenCat, i, i + 1);
				addToChart(Rule.lemmaTokenCat, i, i + 1);
			}
			for (int i = 0; i < numTokens; i++)
				for (int j = i + 1; j <= numTokens; j++)
				{
					addToChart(Rule.phraseCat, i, j);
					addToChart(Rule.lemmaPhraseCat, i, j);
				}
		}

		private void addToChart(final String cat, final int start, final int end)
		{
			if (Parser.opts.verbose >= 5)
				LogInfo.logs("Adding to chart %s(%s,%s)", cat, start, end);
			MapUtils.putIfAbsent(chart[start][end], cat, new ArrayList<CategorySpan>());
		}

		private void addToChart(final String parentCat, final String childCat, final int start, final int end)
		{
			if (Parser.opts.verbose >= 5)
				LogInfo.logs("Adding to chart %s(%s,%s)-->%s(%s,%s)", parentCat, start, end, childCat, start, end);
			MapUtils.addToList(chart[start][end], parentCat, new CategorySpan(childCat, start, end));
		}

		private void addToChart(final String parentCat, final String leftCat, final String rightCat, final int start, final int i, final int end)
		{
			if (Parser.opts.verbose >= 5)
				LogInfo.logs("Adding to chart %s(%s,%s)-->%s(%s,%s) %s(%s,%s)", parentCat, start, end, leftCat, start, i, rightCat, i, end);
			MapUtils.addToList(chart[start][end], parentCat, new CategorySpan(leftCat, start, i));
			MapUtils.addToList(chart[start][end], parentCat, new CategorySpan(rightCat, i, end));
		}

		private void handleBinaryRules(final int start, final int end)
		{
			for (int i = start + 1; i < end; ++i)
			{
				final List<String> left = new ArrayList<>(chart[start][i].keySet());
				final List<String> right = new ArrayList<>(chart[i][end].keySet());
				if (i - start == 1)
					left.add(phrases[start][i]); // handle single terminal
				if (end - i == 1)
					right.add(phrases[i][end]); // handle single terminal

				for (final String l : left)
					for (final String r : right)
					{
						final Set<String> parentCats = rhsToLhsMap.get(Pair.newPair(l, r));

						if (parentCats != null)
							for (final String parentCat : parentCats)
								addToChart(parentCat, l, r, start, i, end);
					}
			}
		}

		private void handleUnaryRules(final int start, final int end)
		{

			// terminals on RHS
			for (final Rule rule : MapUtils.get(terminalsToRulesList, phrases[start][end], Collections.<Rule> emptyList()))
				addToChart(rule.lhs, start, end);
			// catUnaryRules
			for (final Rule rule : parser.catUnaryRules)
			{
				final String rhsCat = rule.rhs.get(0);
				if (chart[start][end].containsKey(rhsCat))
					addToChart(rule.lhs, rhsCat, start, end);
			}
		}

		public void keepTopDownReachable()
		{
			if (numTokens == 0)
				return;

			final Set<CategorySpan> reachable = new HashSet<>();
			collectReachable(reachable, new CategorySpan(Rule.rootCat, 0, numTokens));

			// Remove all derivations associated with (cat, start, end) that aren't reachable.
			for (int start = 0; start < numTokens; start++)
				for (int end = start + 1; end <= numTokens; end++)
				{
					final List<String> toRemoveCats = new LinkedList<>();
					for (final String cat : chart[start][end].keySet())
						if (!reachable.contains(new CategorySpan(cat, start, end)))
							toRemoveCats.add(cat);
					Collections.sort(toRemoveCats);
					for (final String cat : toRemoveCats)
					{
						if (Parser.opts.verbose >= 5)
							LogInfo.logs("Pruning chart %s(%s,%s)", cat, start, end);
						chart[start][end].remove(cat);
					}
				}
		}

		private void collectReachable(final Set<CategorySpan> reachable, final CategorySpan catSpan)
		{
			if (reachable.contains(catSpan))
				return;
			if (!chart[catSpan.start][catSpan.end].containsKey(catSpan.cat))
				// This should only happen for the root when there are no parses.
				return;
			reachable.add(catSpan);
			for (final CategorySpan childCatSpan : chart[catSpan.start][catSpan.end].get(catSpan.cat))
				collectReachable(reachable, childCatSpan);
		}
	}

	class CategorySpan
	{
		public final String cat;
		public final int start;
		public final int end;

		public CategorySpan(final String cat, final int start, final int end)
		{
			this.cat = cat;
			this.start = start;
			this.end = end;
		}

		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + (cat == null ? 0 : cat.hashCode());
			result = prime * result + end;
			result = prime * result + start;
			return result;
		}

		@Override
		public boolean equals(final Object obj)
		{
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			final CategorySpan other = (CategorySpan) obj;
			if (cat == null)
			{
				if (other.cat != null)
					return false;
			}
			else
				if (!cat.equals(other.cat))
					return false;
			if (end != other.end)
				return false;
			if (start != other.start)
				return false;
			return true;
		}
	}
}
