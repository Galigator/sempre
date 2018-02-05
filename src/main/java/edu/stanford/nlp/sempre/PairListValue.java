package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represent a binary using a list of pairs.
 *
 * @author ppasupat
 */
public class PairListValue extends Value
{
	public final List<Pair<Value, Value>> pairs;

	public PairListValue(final LispTree tree)
	{
		pairs = new ArrayList<>();
		for (int i = 1; i < tree.children.size(); i++)
			pairs.add(new Pair<>(Values.fromLispTree(tree.child(i).child(0)), Values.fromLispTree(tree.child(i).child(1))));
	}

	public PairListValue(final List<Pair<Value, Value>> pairs)
	{
		this.pairs = pairs;
	}

	protected static final LispTree NULL_LEAF = LispTree.proto.newLeaf(null);

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("pairs");
		for (final Pair<Value, Value> pair : pairs)
		{
			final Value first = pair.getFirst(), second = pair.getSecond();
			tree.addChild(LispTree.proto.newList(first == null ? NULL_LEAF : first.toLispTree(), second == null ? NULL_LEAF : second.toLispTree()));
		}
		return tree;
	}

	public void log()
	{
		for (final Pair<Value, Value> pair : pairs)
			LogInfo.logs("%s | %s", pair.getFirst(), pair.getSecond());
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final PairListValue that = (PairListValue) o;
		return pairs.equals(that.pairs);
	}

	@Override
	public int hashCode()
	{
		return pairs.hashCode();
	}

	// Sorted on string representation
	public PairListValue getSorted()
	{
		final List<Pair<Value, Value>> sorted = new ArrayList<>(pairs);
		Collections.sort(sorted, (final Pair<Value, Value> p1, final Pair<Value, Value> p2) -> getQuickStringOfPair(p1).compareTo(getQuickStringOfPair(p2)));
		return new PairListValue(sorted);
	}

	private static String getQuickStringOfPair(final Pair<Value, Value> pair)
	{
		final Value v1 = pair.getFirst(), v2 = pair.getSecond();
		return (v1 == null ? "null" : v1.sortString()) + " " + (v2 == null ? "null" : v2.sortString());
	}
}
