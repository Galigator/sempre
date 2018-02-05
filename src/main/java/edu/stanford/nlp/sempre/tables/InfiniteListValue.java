package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.Values;
import fig.basic.LispTree;
import java.util.ArrayList;
import java.util.List;

/**
 * Represent a list of infinitely many values. The list is represented by a List of Objects.
 *
 * @author ppasupat
 */
public class InfiniteListValue extends Value
{

	final List<Object> representation;
	final int hashCode;

	public InfiniteListValue(final List<Object> representation)
	{
		this.representation = representation;
		hashCode = representation.hashCode();
	}

	public InfiniteListValue(final String s)
	{
		this(LispTree.proto.parseFromString(s));
	}

	public InfiniteListValue(final LispTree tree)
	{
		representation = new ArrayList<>();
		for (final LispTree child : tree.children)
			try
			{
				final Value value = Values.fromLispTree(child);
				representation.add(value);
			}
			catch (final Exception e)
			{
				representation.add(child.toString());
			}
		hashCode = representation.hashCode();
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		for (final Object x : representation)
			if (x instanceof Value)
				tree.addChild(((Value) x).toLispTree());
			else
				tree.addChild(x.toString());
		return tree;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final InfiniteListValue that = (InfiniteListValue) o;
		return representation.equals(that.representation);
	}

	@Override
	public int hashCode()
	{
		return hashCode;
	}

}
