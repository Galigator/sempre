package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.Value;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a table column. The column header is used as the relation name.
 *
 * @author ppasupat
 */
public class TableColumn
{
	public final List<TableCell> children;
	public final String originalString;
	public final String columnName;
	public final int index;
	// Relation Name
	public final NameValue relationNameValue, relationConsecutiveNameValue;
	// Children Cell's Type (EntitySemType)
	public final String cellTypeString;
	public final NameValue cellTypeValue;
	public final SemType cellSemType;

	public TableColumn(final String originalString, final String columnName, final int index)
	{
		children = new ArrayList<>();
		this.originalString = originalString;
		this.columnName = columnName;
		this.index = index;
		relationNameValue = new NameValue(TableTypeSystem.getRowPropertyName(columnName), originalString);
		relationConsecutiveNameValue = new NameValue(TableTypeSystem.getRowConsecutivePropertyName(columnName), originalString);
		cellTypeString = TableTypeSystem.getCellType(columnName);
		cellTypeValue = new NameValue(cellTypeString, originalString);
		cellSemType = SemType.newAtomicSemType(cellTypeString);
	}

	/** Create a copy without the children field. */
	public TableColumn(final TableColumn old)
	{
		children = new ArrayList<>();
		originalString = old.originalString;
		columnName = old.columnName;
		index = old.index;
		relationNameValue = old.relationNameValue;
		relationConsecutiveNameValue = old.relationConsecutiveNameValue;
		cellTypeString = old.cellTypeString;
		cellTypeValue = old.cellTypeValue;
		cellSemType = old.cellSemType;
	}

	public static Set<String> getReservedFieldNames()
	{
		final Set<String> usedNames = new HashSet<>();
		usedNames.add("next");
		usedNames.add("index");
		return usedNames;
	}

	@Override
	public String toString()
	{
		return relationNameValue.toString();
	}

	public boolean hasConsecutive()
	{
		NameValue previousCell = null;
		for (final TableCell child : children)
		{
			if (child.properties.nameValue.equals(previousCell))
				return true;
			previousCell = child.properties.nameValue;
		}
		return false;
	}

	public Collection<Value> getAllNormalization()
	{
		final Set<Value> normalizations = new HashSet<>();
		for (final TableCell cell : children)
			normalizations.addAll(cell.properties.metadata.keySet());
		return normalizations;
	}
}
