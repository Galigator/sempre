package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

public class UriValue extends Value
{
	public final String value;

	public UriValue(final LispTree tree)
	{
		value = tree.child(1).value;
	}

	public UriValue(final String value)
	{
		this.value = value;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("url");
		tree.addChild(value != null ? value : "");
		return tree;
	}

	@Override
	public String sortString()
	{
		return "" + value;
	}

	@Override
	public String pureString()
	{
		return "" + value;
	}

	@Override
	public int hashCode()
	{
		return value.hashCode();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final UriValue that = (UriValue) o;
		return value.equals(that.value);
	}
}
