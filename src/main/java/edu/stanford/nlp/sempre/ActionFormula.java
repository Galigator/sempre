package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import fig.basic.LispTree;
import java.util.List;

/**
 * An ActionFormula represent a compositional action used in the interactive package : is used as a prefix to denote an ActionFormula primitive (: actioname
 * args) sequential (:s ActionFormula ActionFormula ...) repeat (:loop Number ActionFormula) conditional (:if Set ActionFormula) block scoping (:blk
 * ActionFormula)
 * 
 * @author sidaw
 */
public class ActionFormula extends Formula
{
	public enum Mode
	{
		primitive(":"), // (: remove *)
		sequential(":s"), // (:s (: add red top) (: remove this))
		repeat(":loop"), // (:loop (count (has color green)) (: add red top))
		conditional(":if"), // (:if (count (has color green)) (: add red top))
		whileloop(":while"), // (:while (count (has color green)) (: add red top))
		forset(":for"), // (:for (and this (color red)) (:s (: add red top) (: add
		// yellow top) (: remove)))
		foreach(":foreach"), // (:foreach * (add ((reverse color) this) top))

		// primitives for declaring variables
		// let(":let"), // (:let X *),
		// set(":set"), // (:set X *)

		block(":blk"), // start a block of code (like {}) with a new scope
		blockr(":blkr"), // also return a result after finishing the block
		isolate(":isolate"), other(":?");

		private final String value;

		Mode(final String value)
		{
			this.value = value;
		}

		@Override
		public String toString()
		{
			return value;
		}
	};

	public final Mode mode;
	public final List<Formula> args;

	public ActionFormula(final Mode mode, final List<Formula> args)
	{
		this.mode = mode;
		this.args = args;
	}

	public static Mode parseMode(final String mode)
	{
		if (mode == null)
			return null;
		for (final Mode m : Mode.values())
			// LogInfo.logs("mode string %s \t== %s \t!= %s", m.toString(), mode,
			// m.name());
			if (m.toString().equals(mode))
				return m;
		if (mode.startsWith(":"))
			throw new RuntimeException("Unsupported ActionFormula mode");
		return null;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild(mode.toString());
		for (final Formula arg : args)
			tree.addChild(arg.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
			for (final Formula arg : args)
				arg.forEach(func);
	}

	@Override
	public Formula map(final Function<Formula, Formula> transform)
	{
		final Formula result = transform.apply(this);
		if (result != null)
			return result;
		final List<Formula> newArgs = Lists.newArrayList();
		for (final Formula arg : args)
			newArgs.add(arg.map(transform));
		return new ActionFormula(mode, newArgs);
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> transform, final boolean alwaysRecurse)
	{
		final List<Formula> res = transform.apply(this);
		if (res.isEmpty() || alwaysRecurse)
			for (final Formula arg : args)
				res.addAll(arg.mapToList(transform, alwaysRecurse));
		return res;
	}

	@SuppressWarnings({ "equalshashcode" })
	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof ActionFormula))
			return false;
		final ActionFormula that = (ActionFormula) thatObj;
		if (!mode.equals(that.mode))
			return false;
		if (!args.equals(that.args))
			return false;
		return true;
	}

	@Override
	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + mode.hashCode();
		hash = hash * 0xd3a2646c + args.hashCode();
		return hash;
	}
}
