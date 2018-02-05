package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import fig.basic.LispTree;
import java.util.List;

/**
 * Computes the extreme elements of a set |head| according to the degree given by |relation|.
 *
 * @author Percy Liang
 */
public class SuperlativeFormula extends Formula
{
	public enum Mode
	{
		argmin, argmax
	}

	public final Mode mode;
	public final Formula rank; // rank-th item
	public final Formula count; // Number of items to fetch
	public final Formula head;
	public final Formula relation; // Apply relation(head, degree) and sort by degree.

	public SuperlativeFormula(final Mode mode_, final Formula rank_, final Formula count_, final Formula head_, final Formula relation_)
	{
		mode = mode_;
		rank = rank_;
		count = count_;
		head = head_;
		relation = relation_;
	}

	public static Mode parseMode(final String mode)
	{
		if ("argmin".equals(mode))
			return Mode.argmin;
		if ("argmax".equals(mode))
			return Mode.argmax;
		return null;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild(mode + "");
		tree.addChild(rank.toLispTree());
		tree.addChild(count.toLispTree());
		tree.addChild(head.toLispTree());
		tree.addChild(relation.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
		{
			rank.forEach(func);
			count.forEach(func);
			head.forEach(func);
			relation.forEach(func);
		}
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? new SuperlativeFormula(mode, rank.map(func), count.map(func), head.map(func), relation.map(func)) : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		final List<Formula> res = func.apply(this);
		if (res.isEmpty() || alwaysRecurse)
		{
			res.addAll(rank.mapToList(func, alwaysRecurse));
			res.addAll(count.mapToList(func, alwaysRecurse));
			res.addAll(head.mapToList(func, alwaysRecurse));
			res.addAll(relation.mapToList(func, alwaysRecurse));
		}
		return res;
	}

	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof SuperlativeFormula))
			return false;
		final SuperlativeFormula that = (SuperlativeFormula) thatObj;
		if (mode != that.mode)
			return false;
		if (!rank.equals(that.rank))
			return false;
		if (!count.equals(that.count))
			return false;
		if (!head.equals(that.head))
			return false;
		if (!relation.equals(that.relation))
			return false;
		return true;
	}

	@Override
	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + mode.toString().hashCode();
		hash = hash * 0xd3a2646c + rank.hashCode();
		hash = hash * 0xd3a2646c + count.hashCode();
		hash = hash * 0xd3a2646c + head.hashCode();
		hash = hash * 0xd3a2646c + relation.hashCode();
		return hash;
	}
}
