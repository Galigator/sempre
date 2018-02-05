package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import fig.basic.LispTree;
import java.util.List;

/**
 * (not expression) returns the truth value which is opposite of expression.
 *
 * @author Percy Liang
 */
public class NotFormula extends Formula
{
	public final Formula child;

	public NotFormula(final Formula child_)
	{
		child = child_;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("not");
		tree.addChild(child.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
			child.forEach(func);
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? new NotFormula(child.map(func)) : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		final List<Formula> res = func.apply(this);
		if (res.isEmpty() || alwaysRecurse)
			res.addAll(child.mapToList(func, alwaysRecurse));
		return res;
	}

	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof NotFormula))
			return false;
		final NotFormula that = (NotFormula) thatObj;
		if (!child.equals(that.child))
			return false;
		return true;
	}

	@Override
	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + child.hashCode();
		return hash;
	}
}
