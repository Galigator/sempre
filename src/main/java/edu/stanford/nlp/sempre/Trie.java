package edu.stanford.nlp.sempre;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Used to access rules efficiently by walking down their RHS.
 * 
 * @author Percy Liang
 */
public class Trie
{
	public ArrayList<Rule> rules = new ArrayList<>();
	Map<String, Trie> children = new LinkedHashMap<>();
	// Set of LHS categories of all rules in this subtree
	public Set<String> cats = new LinkedHashSet<>();

	public Trie next(final String item)
	{
		return children.get(item);
	}

	public void add(final Rule rule)
	{
		add(rule, 0);
	}

	private void add(final Rule rule, final int i)
	{
		cats.add(rule.lhs);

		if (i == rule.rhs.size())
		{
			if (!rules.contains(rule)) // filter exact match
				rules.add(rule);
			return;
		}

		final String item = rule.rhs.get(i);
		Trie child = children.get(item);
		if (child == null)
			children.put(item, child = new Trie());
		child.add(rule, i + 1);
	}
}
