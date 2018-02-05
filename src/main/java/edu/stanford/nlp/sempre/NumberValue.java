package edu.stanford.nlp.sempre;

import fig.basic.Fmt;
import fig.basic.LispTree;

/**
 * Represents a numerical value (optionally comes with a unit). In the future, might want to split this into an Integer version?
 *
 * @author Percy Liang
 */
public class NumberValue extends Value
{
	public static final String unitless = "fb:en.unitless";
	public static final String yearUnit = "fb:en.year";

	public final double value;
	public final String unit; // What measurement (e.g., "fb:en.meter" or unitless)

	public NumberValue(final double value)
	{
		this(value, unitless);
	}

	public NumberValue(final double value, final String unit)
	{
		this.value = value;
		this.unit = unit;
	}

	public NumberValue(final LispTree tree)
	{
		value = Double.parseDouble(tree.child(1).value);
		unit = 2 < tree.children.size() ? tree.child(2).value : unitless;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("number");
		tree.addChild(Fmt.D(value));
		if (!unit.equals(unitless))
			tree.addChild(unit);
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
		return Double.valueOf(value).hashCode();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final NumberValue that = (NumberValue) o;
		if (value != that.value)
			return false; // Warning: doing exact equality checking
		if (!unit.equals(that.unit))
			return false;
		return true;
	}
}
