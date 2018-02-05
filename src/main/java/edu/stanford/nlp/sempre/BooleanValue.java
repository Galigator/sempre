package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * Represents a boolean.
 * 
 * @author Percy Liang
 **/
public class BooleanValue extends Value
{
	public final boolean value;

	public BooleanValue(final boolean value)
	{
		this.value = value;
	}

	public BooleanValue(final LispTree tree)
	{
		value = Boolean.parseBoolean(tree.child(1).value);
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("boolean");
		tree.addChild(value + "");
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
		return Boolean.valueOf(value).hashCode();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final BooleanValue that = (BooleanValue) o;
		return value == that.value;
	}
}
