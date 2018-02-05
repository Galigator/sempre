package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import fig.basic.LogInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class ListValue extends Value
{
	public final List<Value> values;

	public ListValue(final LispTree tree)
	{
		values = new ArrayList<>();
		for (int i = 1; i < tree.children.size(); i++)
			values.add(Values.fromLispTree(tree.child(i)));
	}

	public ListValue(final List<Value> values)
	{
		this.values = values;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("list");
		for (final Value value : values)
			tree.addChild(value == null ? LispTree.proto.newLeaf(null) : value.toLispTree());
		return tree;
	}

	public void log()
	{
		for (final Value value : values)
			LogInfo.logs("%s", value);
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final ListValue that = (ListValue) o;
		return values.equals(that.values);
	}

	@Override
	public int hashCode()
	{
		return values.hashCode();
	}

	// Sorted on string representation
	public ListValue getSorted()
	{
		final List<Value> sorted = new ArrayList<>(values);
		Collections.sort(sorted, (final Value v1, final Value v2) -> (v1 == null ? "null" : v1.sortString()).compareTo(v2 == null ? "null" : v2.sortString()));
		return new ListValue(sorted);
	}

	// Unique
	public ListValue getUnique()
	{
		final List<Value> sorted = new ArrayList<>(new HashSet<>(values));
		Collections.sort(sorted, (final Value v1, final Value v2) -> (v1 == null ? "null" : v1.sortString()).compareTo(v2 == null ? "null" : v2.sortString()));
		return new ListValue(sorted);
	}
}
