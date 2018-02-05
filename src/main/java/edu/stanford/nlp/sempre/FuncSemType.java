package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * FuncSemType really is used to represent a pair type (t1, t2) (despite its name). The lisp tree representation is (-> retType argType).
 */
public class FuncSemType extends SemType
{
	public final SemType argType;
	public final SemType retType;

	public FuncSemType(final SemType argType, final SemType retType)
	{
		if (argType == null)
			throw new RuntimeException("Null argType");
		if (retType == null)
			throw new RuntimeException("Null retType");
		this.argType = argType;
		this.retType = retType;
	}

	public FuncSemType(final String argType, final String retType)
	{
		this(new AtomicSemType(argType), new AtomicSemType(retType));
	}

	public boolean isValid()
	{
		return true;
	}

	public SemType meet(final SemType that)
	{
		if (that instanceof TopSemType)
			return this;
		if (!(that instanceof FuncSemType))
			return SemType.bottomType;
		// Perform the meet elementwise (remember, treat this as a pair type).
		final FuncSemType thatFunc = (FuncSemType) that;
		final SemType newArgType = argType.meet(thatFunc.argType);
		if (!newArgType.isValid())
			return SemType.bottomType;
		final SemType newRetType = retType.meet(thatFunc.retType);
		if (!newRetType.isValid())
			return SemType.bottomType;
		return new FuncSemType(newArgType, newRetType);
	}

	public SemType apply(final SemType that)
	{
		if (argType.meet(that).isValid())
			return retType;
		return SemType.bottomType;
	}

	public FuncSemType reverse()
	{
		return new FuncSemType(retType, argType);
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("->");
		tree.addChild(argType.toLispTree());
		tree.addChild(retType.toLispTree());
		return tree;
	}
}
