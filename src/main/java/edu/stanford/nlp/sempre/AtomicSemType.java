package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

// Represents an atomic type (strings, entities, numbers, dates, etc.).
public class AtomicSemType extends SemType
{
	public final String name;

	public AtomicSemType(final String name)
	{
		if (name == null)
			throw new RuntimeException("Null name");
		this.name = name;
	}

	public boolean isValid()
	{
		return true;
	}

	public SemType meet(final SemType that)
	{
		if (that instanceof TopSemType)
			return this;
		if (that instanceof UnionSemType)
			return that.meet(this);
		if (that instanceof AtomicSemType)
		{
			final String name1 = name;
			final String name2 = ((AtomicSemType) that).name;
			if (name1.equals(name2))
				return this; // Shortcut: the same
			if (SemTypeHierarchy.singleton.getSupertypes(name1).contains(name2))
				return this;
			if (SemTypeHierarchy.singleton.getSupertypes(name2).contains(name1))
				return that;
			return SemType.bottomType;
		}
		return SemType.bottomType;
	}

	public SemType apply(final SemType that)
	{
		return SemType.bottomType;
	}

	public SemType reverse()
	{
		return SemType.bottomType;
	}

	public LispTree toLispTree()
	{
		return LispTree.proto.newLeaf(name);
	}
}
