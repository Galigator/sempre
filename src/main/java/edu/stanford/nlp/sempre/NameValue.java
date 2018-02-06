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
	public final String _id; // Identifier (e.g., "fb:en.barack_obama")
	public final String _description; // Readable description (e.g., "Barack Obama")

	public NameValue(final LispTree tree)
	{
		_id = tree.child(1).value;
		if (tree.children.size() > 2)
			_description = tree.child(2).value;
		else
			_description = null;
		assert _id != null : tree;
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
		_id = id;
		_description = description;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("name");
		tree.addChild(_id);
		if (_description != null)
			tree.addChild(_description);
		return tree;
	}

	@Override
	public String sortString()
	{
		return _id;
	}

	@Override
	public String pureString()
	{
		return _description == null ? _id : _description;
	}

	@Override
	public int hashCode()
	{
		return _id.hashCode();
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
		return _id.equals(that._id);
	}
}
