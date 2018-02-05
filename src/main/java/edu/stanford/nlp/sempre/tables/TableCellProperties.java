package edu.stanford.nlp.sempre.tables;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.Value;

/**
 * Store various properties of a cell. Contract: There is only one TableCellProperties for each unique id.
 *
 * @author ppasupat
 */
public class TableCellProperties
{
	public final String id;
	public final String originalString;
	public final NameValue nameValue;
	public final Multimap<Value, Value> metadata;

	public TableCellProperties(final String id, final String originalString)
	{
		this.id = id;
		this.originalString = originalString;
		nameValue = new NameValue(id, originalString);
		metadata = ArrayListMultimap.create();
	}

	/** Create a copy without the columns field. */
	public TableCellProperties(final TableCellProperties old)
	{
		id = old.id;
		originalString = old.originalString;
		nameValue = old.nameValue;
		metadata = ArrayListMultimap.create(old.metadata);
	}

	@Override
	public boolean equals(final Object o)
	{
		if (!(o instanceof TableCellProperties))
			return false;
		return id.equals(((TableCellProperties) o).id);
	}

	@Override
	public int hashCode()
	{
		return id.hashCode();
	}
}
