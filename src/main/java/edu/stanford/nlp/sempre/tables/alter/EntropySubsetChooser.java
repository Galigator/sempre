package edu.stanford.nlp.sempre.tables.alter;

import edu.stanford.nlp.sempre.Value;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Choose a subset based on diversity score (entropy).
 */
public class EntropySubsetChooser implements SubsetChooser
{
	public static class Options
	{
		@Option(gloss = "check correctness")
		public boolean entropyCheckCorrectness = false;
	}

	public static Options opts = new Options();

	private final int numAlteredTables, numRetainedTables;
	private final boolean alsoTrySmallerSubsets;

	public EntropySubsetChooser(final int numAlteredTables, final int numRetainedTables, final boolean alsoTrySmallerSubsets)
	{
		this.numAlteredTables = numAlteredTables;
		this.numRetainedTables = numRetainedTables;
		this.alsoTrySmallerSubsets = alsoTrySmallerSubsets;
	}

	@Override
	public Subset chooseSubset(final String id, final DenotationData denotationData)
	{
		return chooseSubset(id, denotationData, Collections.emptyList());
	}

	@Override
	public Subset chooseSubset(final String id, final DenotationData denotationData, final Collection<Integer> forbiddenTables)
	{
		if (numRetainedTables == 0)
			return null;
		List<Integer> bestGraphIndices = null;
		double bestScore = 0;
		final int n = denotationData.getRepresentativeIndices().size();
		final EquivClassComputer computer1 = opts.entropyCheckCorrectness ? null : new EquivClassComputerNaive(denotationData);
		final EquivClassComputer computer2 = opts.entropyCheckCorrectness ? null : new EquivClassComputerGroup(denotationData);
		final EquivClassComputer computer3 = new EquivClassComputerFast(denotationData);

		Iterator<List<Integer>> itr;
		if (alsoTrySmallerSubsets)
			itr = new Subset.SubsetSizeAtMostKIterator(numAlteredTables, numRetainedTables);
		else
			itr = new Subset.SubsetSizeKIterator(numAlteredTables, numRetainedTables);
		while (itr.hasNext())
		{
			final List<Integer> graphIndices = itr.next();
			if (!Subset.areDisjoint(graphIndices, forbiddenTables))
				continue;
			// If N is the number of representatives,
			// H(C) = sum_i c_i/N log(N/c_i) = log(N) - (1/N) sum_i c_i log(c_i)
			// normalized = 1 - [sum_i c_i log(c_i)] / [N log(N)]
			final Collection<Integer> groupSizes = computer3.getGroupSizes(graphIndices);
			double accum = 0;
			for (final int c : groupSizes)
				accum += c * Math.log(c);
			final double entropy = 1 - accum / (n * Math.log(n));
			// Update
			if (entropy > bestScore)
			{
				bestGraphIndices = graphIndices;
				bestScore = entropy;
				if (BatchTableAlterer.opts.verbose >= 2)
					LogInfo.logs("entropy = %8.3f from tables %s", bestScore, bestGraphIndices);
			}
			// Check
			if (opts.entropyCheckCorrectness)
			{
				final List<Integer> naive = new ArrayList<>(computer1.getGroupSizes(graphIndices));
				Collections.sort(naive);
				final List<Integer> group = new ArrayList<>(computer2.getGroupSizes(graphIndices));
				Collections.sort(group);
				final List<Integer> fast = new ArrayList<>(computer3.getGroupSizes(graphIndices));
				Collections.sort(fast);
				if (!naive.equals(group))
				{
					LogInfo.logs("Incorrect (group) %s", graphIndices);
					LogInfo.logs("%s", naive);
					LogInfo.logs("%s", group);
					throw new RuntimeException();
				}
				if (!naive.equals(fast))
				{
					LogInfo.logs("Incorrect (fast) %s", graphIndices);
					LogInfo.logs("%s", naive);
					LogInfo.logs("%s", fast);
					throw new RuntimeException();
				}
			}
		}
		if (bestGraphIndices != null)
		{
			bestGraphIndices.add(0, 0);
			return new Subset(id, bestGraphIndices, bestScore);
		}
		else
			return new Subset(id, numRetainedTables, 0.0);
	}

	/**
	 * Given a subset s = {s1,...,sl} of graph indices {1,...,k}: - Group i's by the values of (denotations[i][s1], ..., denotations[i][sl]) - Return the list
	 * of group sizes The computation is done in amortized n * (k choose l) where n is the number of possible i's (number of representative formulas).
	 */
	public interface EquivClassComputer
	{
		public Collection<Integer> getGroupSizes(List<Integer> graphIndices);
	}

	public static class EquivClassComputerNaive implements EquivClassComputer
	{
		private final DenotationData denotationData;

		public EquivClassComputerNaive(final DenotationData denotationData)
		{
			this.denotationData = denotationData;
		}

		public Collection<Integer> getGroupSizes(final List<Integer> graphIndices)
		{
			final Map<List<Value>, Integer> counts = new HashMap<>();
			for (final int i : denotationData.getRepresentativeIndices())
			{
				final List<Value> denotationsForDeriv = new ArrayList<>();
				for (final int j : graphIndices)
					denotationsForDeriv.add(denotationData.getDenotation(i, j));
				MapUtils.incr(counts, denotationsForDeriv);
			}
			return counts.values();
		}
	}

	public static class EquivClassComputerGroup implements EquivClassComputer
	{
		private final int n, k;
		private final int[][] uniqueIds;
		private List<Integer> previousGraphIndices = new ArrayList<>();
		private final List<List<List<Integer>>> groupStack = new ArrayList<>();
		private final List<List<Integer>> initialGroup = new ArrayList<>();

		public EquivClassComputerGroup(final DenotationData denotationData)
		{
			n = denotationData.getRepresentativeIndices().size();
			k = denotationData.numAlteredTables;
			uniqueIds = new int[n][k + 1];
			for (int j = 1; j <= k; j++)
			{
				final Map<Value, Integer> uniqueDenotationsForTable = new HashMap<>();
				for (int i = 0; i < n; i++)
				{
					final int derivIndex = denotationData.getRepresentativeIndices().get(i);
					final Value denotation = denotationData.getDenotation(derivIndex, j);
					final Integer uniqueId = uniqueDenotationsForTable.get(denotation);
					if (uniqueId == null)
						uniqueDenotationsForTable.put(denotation, uniqueIds[i][j] = uniqueDenotationsForTable.size());
					else
						uniqueIds[i][j] = uniqueId;
				}
			}
			final List<Integer> group = new ArrayList<>();
			for (int i = 0; i < n; i++)
				group.add(i);
			initialGroup.add(group);
		}

		public Collection<Integer> getGroupSizes(final List<Integer> graphIndices)
		{
			// Reduce to common prefix
			int sizeAgreed = 0;
			while (sizeAgreed < previousGraphIndices.size() && sizeAgreed < graphIndices.size() && previousGraphIndices.get(sizeAgreed) == graphIndices.get(sizeAgreed))
				sizeAgreed++;
			while (groupStack.size() > sizeAgreed)
				groupStack.remove(groupStack.size() - 1);
			previousGraphIndices = new ArrayList<>(graphIndices);
			// Group the rest
			for (int j = sizeAgreed; j < graphIndices.size(); j++)
			{
				final int sj = graphIndices.get(j);
				final List<List<Integer>> previousGroups = groupStack.isEmpty() ? initialGroup : groupStack.get(j - 1);
				final List<List<Integer>> groups = new ArrayList<>();
				for (final List<Integer> group : previousGroups)
				{
					if (group.size() == 1)
					{
						groups.add(group);
						continue;
					}
					final Map<Integer, List<Integer>> idToGroups = new HashMap<>();
					for (final int index : group)
						MapUtils.addToList(idToGroups, uniqueIds[index][sj], index);
					groups.addAll(idToGroups.values());
				}
				groupStack.add(groups);
			}
			final List<Integer> groupSizes = new ArrayList<>();
			for (final List<Integer> group : groupStack.get(groupStack.size() - 1))
				groupSizes.add(group.size());
			return groupSizes;
		}
	}

	public static class EquivClassComputerFast implements EquivClassComputer
	{
		private final int n;
		private final int[][] uniqueIds;
		private List<Integer> previousGraphIndices = new ArrayList<>();
		private final List<Integer> groups = new ArrayList<>();
		private final List<int[]> breakpointStack = new ArrayList<>();
		private final int[] initialBreakpoint;

		public EquivClassComputerFast(final DenotationData denotationData)
		{
			n = denotationData.getRepresentativeIndices().size();
			uniqueIds = denotationData.toArray(denotationData.getRepresentativeIndices());
			initialBreakpoint = new int[] { 0, n };
			for (int i = 0; i < n; i++)
				groups.add(i);
		}

		public Collection<Integer> getGroupSizes(final List<Integer> graphIndices)
		{
			// Reduce to common prefix
			int sizeAgreed = 0;
			while (sizeAgreed < previousGraphIndices.size() && sizeAgreed < graphIndices.size() && previousGraphIndices.get(sizeAgreed) == graphIndices.get(sizeAgreed))
				sizeAgreed++;
			while (breakpointStack.size() > sizeAgreed)
				breakpointStack.remove(breakpointStack.size() - 1);
			previousGraphIndices = new ArrayList<>(graphIndices);
			// Group the rest
			for (int j = sizeAgreed; j < graphIndices.size(); j++)
			{
				final int sj = graphIndices.get(j);
				int top = 0;
				final int[] previousBreakpoints = breakpointStack.isEmpty() ? initialBreakpoint : breakpointStack.get(j - 1);
				final int[] newBreakpoints = new int[n + 1];
				newBreakpoints[top++] = 0;
				for (int u = 0; previousBreakpoints[u] < n; u++)
				{
					final int s = previousBreakpoints[u], t = previousBreakpoints[u + 1];
					if (t > s + 1)
					{
						Collections.sort(groups.subList(s, t), (x, y) -> uniqueIds[x][sj] - uniqueIds[y][sj]);
						for (int i = s + 1; i < t; i++)
							if (uniqueIds[groups.get(i - 1)][sj] != uniqueIds[groups.get(i)][sj])
								newBreakpoints[top++] = i;
					}
					newBreakpoints[top++] = t;
				}
				breakpointStack.add(newBreakpoints);
			}
			final List<Integer> groupSizes = new ArrayList<>();
			final int[] breakpoints = breakpointStack.get(breakpointStack.size() - 1);
			for (int u = 0; breakpoints[u] < n; u++)
				groupSizes.add(breakpoints[u + 1] - breakpoints[u]);
			return groupSizes;
		}
	}

}
