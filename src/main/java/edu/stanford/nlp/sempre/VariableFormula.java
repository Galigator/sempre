package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * Corresponds to a variable reference.
 *
 * @author Percy Liang
 */
public class VariableFormula extends PrimitiveFormula
{
	public final String name; // Name of variable.

	public VariableFormula(final String name)
	{
		this.name = name;
	}

	public LispTree toLispTree()
	{
		return LispTree.proto.newList("var", name);
	}

	@SuppressWarnings({ "equalshashcode" })
	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof VariableFormula))
			return false;
		final VariableFormula that = (VariableFormula) thatObj;
		return name.equals(that.name);
	}

	public int computeHashCode()
	{
		return name.hashCode();
	}
}
