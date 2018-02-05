package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * Represents a string value.
 * 
 * @author Percy Liang
 **/
public class StringValue extends Value
{
	public final String value;

	public StringValue(final String value)
	{
		this.value = value;
	}

	public StringValue(final LispTree tree)
	{
		value = tree.child(1).value;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("string");
		tree.addChild(value);
		return tree;
	}

	@Override
	public String sortString()
	{
		return "\"" + value + "\"";
	}

	@Override
	public String pureString()
	{
		return value;
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
		final StringValue that = (StringValue) o;
		return value.equals(that.value);
	}
}
