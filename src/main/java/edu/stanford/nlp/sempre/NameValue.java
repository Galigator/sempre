package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import fig.basic.LogInfo;

/**
 * Represents a logical predicate.
 * 
 * @author Percy Liang
 */
public class NameValue extends Value
{
	public final String id; // Identifier (e.g., "fb:en.barack_obama")
	public final String description; // Readable description (e.g., "Barack Obama")

	public NameValue(final LispTree tree)
	{
		id = tree.child(1).value;
		if (tree.children.size() > 2)
			description = tree.child(2).value;
		else
			description = null;
		assert id != null : tree;
	}

	public NameValue(final String id)
	{
		this(id, null);
	}

	public NameValue(String id, final String description)
	{
		if (id == null)
		{
			LogInfo.errors("Got null id, description is %s", description);
			id = "fb:en.null";
		}
		this.id = id;
		this.description = description;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("name");
		tree.addChild(id);
		if (description != null)
			tree.addChild(description);
		return tree;
	}

	@Override
	public String sortString()
	{
		return id;
	}

	@Override
	public String pureString()
	{
		return description == null ? id : description;
	}

	@Override
	public int hashCode()
	{
		return id.hashCode();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final NameValue that = (NameValue) o;
		// Note: only check id, not description
		return id.equals(that.id);
	}
}
