package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// Represents the union of a set of base types.
public class UnionSemType extends SemType
{
	public final List<SemType> baseTypes;

	public boolean isValid()
	{
		return baseTypes.size() > 0;
	}

	// Constructors
	public UnionSemType()
	{
		baseTypes = new ArrayList<>();
	}

	public UnionSemType(final SemType... baseTypes)
	{
		this.baseTypes = new ArrayList<>();
		for (final SemType baseType : baseTypes)
			if (baseType.isValid())
				this.baseTypes.add(baseType);
	}

	public UnionSemType(final Collection<SemType> baseTypes)
	{
		this.baseTypes = new ArrayList<>();
		for (final SemType baseType : baseTypes)
			if (baseType.isValid())
				this.baseTypes.add(baseType);
	}

	public SemType meet(final SemType that)
	{
		if (that instanceof TopSemType)
			return this;
		final List<SemType> result = new ArrayList<>();
		for (final SemType baseType : baseTypes)
			result.add(baseType.meet(that));
		return new UnionSemType(result).simplify();
	}

	public SemType apply(final SemType that)
	{
		final List<SemType> result = new ArrayList<>();
		for (final SemType baseType : baseTypes)
			result.add(baseType.apply(that));
		return new UnionSemType(result).simplify();
	}

	public SemType reverse()
	{
		final List<SemType> result = new ArrayList<>();
		for (final SemType baseType : baseTypes)
			result.add(baseType.reverse());
		return new UnionSemType(result).simplify();
	}

	public LispTree toLispTree()
	{
		final LispTree result = LispTree.proto.newList();
		result.addChild("union");
		for (final SemType baseType : baseTypes)
			result.addChild(baseType.toLispTree());
		return result;
	}

	public SemType simplify()
	{
		if (baseTypes.size() == 0)
			return SemType.bottomType;
		if (baseTypes.size() == 1)
			return baseTypes.get(0);
		if (baseTypes.contains(SemType.topType))
			return SemType.topType;
		return this;
	}
}
