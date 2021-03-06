package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.StrUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a table (has a header and a list of rows). (table (State Capital) ((name fb:en.california) (name fb:en.sacramento)) ((name fb:en.oregon) (name
 * fb:en.salem))) Future: contain information about which columns are important (the head of a phrase)?
 *
 * @author Percy Liang
 */
public class TableValue extends Value
{
	public final List<String> header;
	public final List<List<Value>> rows;

	public int numRows()
	{
		return rows.size();
	}

	public int numCols()
	{
		return header.size();
	}

	public TableValue(final LispTree tree)
	{
		header = new ArrayList<>();
		rows = new ArrayList<>();
		// Read header
		final LispTree headerTree = tree.child(1);
		for (final LispTree item : headerTree.children)
			header.add(item.value);
		// Read rows
		for (int i = 2; i < tree.children.size(); i++)
		{
			final List<Value> row = new ArrayList<>();
			for (final LispTree item : tree.child(i).children)
				row.add(Values.fromLispTree(item));
			rows.add(row);
		}
	}

	public TableValue(final List<String> header, final List<List<Value>> rows)
	{
		this.header = header;
		this.rows = rows;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("table");
		final LispTree headerTree = LispTree.proto.newList();
		for (final String item : header)
			headerTree.addChild(item);
		tree.addChild(headerTree);
		for (final List<Value> row : rows)
		{
			final LispTree rowTree = LispTree.proto.newList();
			for (final Value value : row)
				rowTree.addChild(value == null ? LispTree.proto.newLeaf(null) : value.toLispTree());
			tree.addChild(rowTree);
		}
		return tree;
	}

	public void log()
	{
		LogInfo.begin_track("%s", StrUtils.join(header, "\t"));
		for (final List<Value> row : rows)
			LogInfo.logs("%s", StrUtils.join(row, "\t"));
		LogInfo.end_track();
	}

	// Note: don't compare the headers right now
	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final TableValue that = (TableValue) o;
		if (!rows.equals(that.rows))
			return false;
		return true;
	}

	@Override
	public int hashCode()
	{
		return rows.hashCode();
	}
}
