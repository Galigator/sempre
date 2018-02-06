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

	public final double _value;
	public final String _unit; // What measurement (e.g., "fb:en.meter" or unitless)

	public NumberValue(final double value)
	{
		this(value, unitless);
	}

	public NumberValue(final double value, final String unit)
	{
		_value = value;
		_unit = unit;
	}

	public NumberValue(final LispTree tree)
	{
		_value = Double.parseDouble(tree.child(1).value);
		_unit = 2 < tree.children.size() ? tree.child(2).value : unitless;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("number");
		tree.addChild(Fmt.D(_value));
		if (!_unit.equals(unitless))
			tree.addChild(_unit);
		return tree;
	}

	@Override
	public String sortString()
	{
		return "" + _value;
	}

	@Override
	public String pureString()
	{
		return "" + _value;
	}

	@Override
	public int hashCode()
	{
		return Double.valueOf(_value).hashCode();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final NumberValue that = (NumberValue) o;
		if (_value != that._value)
			return false; // Warning: doing exact equality checking
		if (!_unit.equals(that._unit))
			return false;
		return true;
	}
}
