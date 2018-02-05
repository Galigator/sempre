package edu.stanford.nlp.sempre.tables;

/**
 * Represents a table cell. Information about the cell is kept in the |properties| field.
 *
 * @author ppasupat
 */
public final class TableCell
{
	public final TableColumn parentColumn;
	public final TableRow parentRow;
	public final TableCellProperties properties;

	private TableCell(final TableCellProperties properties, final TableColumn column, final TableRow row)
	{
		parentColumn = column;
		parentRow = row;
		this.properties = properties;
	}

	public static TableCell createAndAddTo(final TableCellProperties properties, final TableColumn column, final TableRow row)
	{
		final TableCell answer = new TableCell(properties, column, row);
		column.children.add(answer);
		row.children.add(answer);
		return answer;
	}

	@Override
	public String toString()
	{
		return properties.nameValue.toString();
	}
}
