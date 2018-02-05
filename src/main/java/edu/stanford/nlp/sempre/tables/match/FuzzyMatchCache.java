package edu.stanford.nlp.sempre.tables.match;

import edu.stanford.nlp.sempre.Formula;
import fig.basic.Pair;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class FuzzyMatchCache
{

	Map<Pair<Integer, Integer>, Collection<Formula>> entries = new HashMap<>();

	public void put(final int startIndex, final int endIndex, final Collection<Formula> formulas)
	{
		entries.put(new Pair<>(startIndex, endIndex), formulas);
	}

	public void add(final int startIndex, final int endIndex, final Formula formula)
	{
		Collection<Formula> current = entries.get(new Pair<>(startIndex, endIndex));
		if (current == null)
			entries.put(new Pair<>(startIndex, endIndex), current = new HashSet<>());
		current.add(formula);
	}

	public void addAll(final int startIndex, final int endIndex, final Collection<Formula> formulas)
	{
		Collection<Formula> current = entries.get(new Pair<>(startIndex, endIndex));
		if (current == null)
			entries.put(new Pair<>(startIndex, endIndex), current = new HashSet<>());
		current.addAll(formulas);
	}

	public void clear(final int startIndex, final int endIndex)
	{
		entries.remove(new Pair<>(startIndex, endIndex));
	}

	public void removeAll(final int startIndex, final int endIndex, final Collection<Formula> formulas)
	{
		final Collection<Formula> current = entries.get(new Pair<>(startIndex, endIndex));
		if (current == null)
			return;
		current.removeAll(formulas);
		if (current.isEmpty())
			entries.remove(new Pair<>(startIndex, endIndex));
	}

	public Collection<Formula> get(final int startIndex, final int endIndex)
	{
		final Collection<Formula> answer = entries.get(new Pair<>(startIndex, endIndex));
		return answer == null ? Collections.emptySet() : answer;
	}

}
