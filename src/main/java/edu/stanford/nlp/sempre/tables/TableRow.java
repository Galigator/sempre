package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a table row. In the knowledge graph, a table row is a nameless node. The final denotation cannot be row nodes.
 *
 * @author ppasupat
 */
public class TableRow
{
	public final List<TableCell> children;
	public final int index;
	public final NumberValue indexValue;
	public final NameValue nameValue;

	public TableRow(final int index)
	{
		children = new ArrayList<>();
		this.index = index;
		indexValue = new NumberValue(index);
		nameValue = new NameValue(TableTypeSystem.getRowName(index), "" + index);
	}

	@Override
	public String toString()
	{
		return nameValue.toString();
	}
}
