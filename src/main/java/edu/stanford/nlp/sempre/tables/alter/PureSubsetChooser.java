package edu.stanford.nlp.sempre.tables.alter;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class PureSubsetChooser implements SubsetChooser
{

	private final int numAlteredTables, numRetainedTables;
	private final boolean alsoTrySmallerSubsets;

	public PureSubsetChooser(final int numAlteredTables, final int numRetainedTables, final boolean alsoTrySmallerSubsets)
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
		if (numRetainedTables == 0 || !denotationData.isAnnotated())
			return null;
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
			int numGroupsMixingWithAnnotated = 0;
			for (final int i : denotationData.getRepresentativeIndices())
			{
				boolean match = true;
				for (final int j : graphIndices)
					if (!denotationData.getDenotation(i, j).equals(denotationData.getAnnotatedDenotation(j)))
					{
						match = false;
						break;
					}
				if (match)
					numGroupsMixingWithAnnotated++;
			}
			if (numGroupsMixingWithAnnotated == 1)
			{
				graphIndices.add(0, 0);
				return new Subset(id, graphIndices, -graphIndices.size());
			}
		}
		return null;
	}

}
