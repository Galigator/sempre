package edu.stanford.nlp.sempre.tables.serialize;

import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.DescriptionValue;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.Value;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generate a TSV file for the dataset release.
 * 
 * @author ppasupat
 */
public class TSVGenerator
{
	protected PrintWriter out;

	protected void dump(final String... stuff)
	{
		out.println(String.join("\t", stuff));
	}

	protected static String serialize(final String x)
	{
		if (x == null || x.isEmpty())
			return "";
		final StringBuilder sb = new StringBuilder();
		for (final char y : x.toCharArray())
			if (y == '\n')
				sb.append("\\n");
			else
				if (y == '\\')
					sb.append("\\\\");
				else
					if (y == '|')
						sb.append("\\p");
					else
						sb.append(y);
		return sb.toString().replaceAll("\\s", " ").trim();
	}

	protected static String serialize(final List<String> xs)
	{
		final List<String> serialized = new ArrayList<>();
		for (final String x : xs)
			serialized.add(serialize(x));
		return String.join("|", serialized);
	}

	protected static String serialize(final Value value)
	{
		if (value instanceof ListValue)
		{
			final List<String> xs = new ArrayList<>();
			for (final Value v : ((ListValue) value).values)
				xs.add(serialize(v));
			return String.join("|", xs);
		}
		else
			if (value instanceof DescriptionValue)
				return serialize(((DescriptionValue) value).value);
			else
				if (value instanceof NameValue)
					return serialize(((NameValue) value)._description);
				else
					if (value instanceof NumberValue)
						return "" + ((NumberValue) value)._value;
					else
						if (value instanceof DateValue)
							return ((DateValue) value).isoString();
						else
							throw new RuntimeException("Unknown value type: " + value);
	}

	protected static String serializeId(final Value value)
	{
		if (value instanceof ListValue)
		{
			final List<String> xs = new ArrayList<>();
			for (final Value v : ((ListValue) value).values)
				xs.add(serializeId(v));
			return String.join("|", xs);
		}
		else
			if (value instanceof NameValue)
				return serialize(((NameValue) value)._id);
			else
				throw new RuntimeException("Unknown value type: " + value);
	}
}
