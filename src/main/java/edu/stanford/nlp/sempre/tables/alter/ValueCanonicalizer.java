package edu.stanford.nlp.sempre.tables.alter;

import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.Value;
import java.util.ArrayList;
import java.util.List;

public class ValueCanonicalizer
{

	public static final ErrorValue ERROR = new ErrorValue("ERROR");

	public static Value canonicalize(final Value value)
	{
		if (value instanceof ErrorValue)
			return ERROR;
		else
			if (value instanceof ListValue)
			{
				final List<Value> stuff = ((ListValue) value).values;
				final List<Value> canonical = new ArrayList<>();
				for (final Value x : stuff)
					if (x instanceof DateValue)
					{
						final DateValue date = (DateValue) x;
						if (date.month == -1 && date.day == -1)
							canonical.add(new NumberValue(date.year));
						else
							canonical.add(x);
					}
					else
						canonical.add(x);
				final ListValue canonList = new ListValue(canonical).getUnique();
				return canonList.values.size() == 1 ? canonList.values.get(0) : canonList;
			}
			else
				return value; // Probably infinite value
	}

}
