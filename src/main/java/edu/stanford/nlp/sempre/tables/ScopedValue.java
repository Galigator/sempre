package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.Value;
import fig.basic.LispTree;

/**
 * Represent a binary with a restrict domain (scope).
 *
 * @author ppasupat
 */
public class ScopedValue extends Value
{
	public final Value head, relation;

	public ScopedValue(final Value head, final Value relation)
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
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final ScopedValue that = (ScopedValue) o;
		return head.equals(that.head) && relation.equals(that.relation);
	}

	@Override
	public int hashCode()
	{
		return head.hashCode() + relation.hashCode();
	}

}
