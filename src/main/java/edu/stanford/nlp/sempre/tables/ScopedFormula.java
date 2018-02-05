package edu.stanford.nlp.sempre.tables;

import com.google.common.base.Function;
import edu.stanford.nlp.sempre.Formula;
import fig.basic.LispTree;
import java.util.List;

/**
 * Represent a binary with a restrict domain (scope).
 *
 * @author ppasupat
 */
public class ScopedFormula extends Formula
{
	public final Formula head;
	public final Formula relation;

	public ScopedFormula(final Formula head, final Formula relation)
	{
		this.head = head;
		this.relation = relation;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("scoped");
		tree.addChild(head.toLispTree());
		tree.addChild(relation.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
		{
			head.forEach(func);
			relation.forEach(func);
		}
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? new ScopedFormula(head.map(func), relation.map(func)) : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		final List<Formula> res = func.apply(this);
		if (res.isEmpty() || alwaysRecurse)
		{
			res.addAll(head.mapToList(func, alwaysRecurse));
			res.addAll(relation.mapToList(func, alwaysRecurse));
		}
		return res;
	}

	@SuppressWarnings({ "equalshashcode" })
	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof ScopedFormula))
			return false;
		final ScopedFormula that = (ScopedFormula) thatObj;
		if (!head.equals(that.head))
			return false;
		if (!relation.equals(that.relation))
			return false;
		return true;
	}

	@Override
	public int computeHashCode()
	{
		int hash = 0x7e2a16;
		hash = hash * 0xd3b2646c + head.hashCode();
		hash = hash * 0xd3b2646c + relation.hashCode();
		return hash;
	}

}
