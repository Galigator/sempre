package edu.stanford.nlp.sempre.freebase;

import fig.basic.IOUtils;
import fig.basic.LogInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public final class Utils
{
	private Utils()
	{
	}

	public static final String ttlPrefix = "@prefix fb: <http://rdf.freebase.com/ns/>.";

	// Somewhat of a crude approximation.
	public static boolean isUrl(final String s)
	{
		return s.startsWith("<");
	}

	// Virtuoso can't deal with this; these are probably useless anyway.
	public static boolean identifierContainsStrangeCharacters(final String s)
	{
		return !s.startsWith("\"") && s.contains("$");
	}

	// Convert a string from the ns: namespace to the fb: namespace.
	// "ns:en.barack_obama" => "fb:en.barack_obama"
	public static String nsToFb(final String s)
	{
		if (s.startsWith("ns:"))
			return "fb:" + s.substring(3);
		return s;
	}

	// "\"/en/distributive_writing\"" => "fb:en.distributive_writing"
	public static String stringToRdf(final String arg2)
	{
		if (!arg2.startsWith("\"/") || !arg2.endsWith("\""))
			throw new RuntimeException("Bad: " + arg2);
		return "fb:" + arg2.substring(2, arg2.length() - 1).replaceAll("/", ".");
	}

	public static String[] parseTriple(final String line)
	{
		if (!line.endsWith("."))
			return null;
		final String[] tokens = line.substring(0, line.length() - 1).split("\t");
		if (tokens.length != 3)
			return null;
		tokens[0] = Utils.nsToFb(tokens[0]);
		tokens[1] = Utils.nsToFb(tokens[1]);
		tokens[2] = Utils.nsToFb(tokens[2]);
		return tokens;
	}

	public static int parseInt(final String arg2)
	{
		if (!arg2.endsWith("^^xsd:int"))
			throw new RuntimeException("Arg2 is not a valid integer: " + arg2);
		final int closingQuoteIndex = arg2.lastIndexOf('"');
		return Integer.parseInt(arg2.substring(1, closingQuoteIndex));
	}

	public static String parseStr(final String arg2)
	{
		if (!arg2.endsWith("@en"))
			throw new RuntimeException("Arg2 is not a valid String: " + arg2);
		final int closingQuoteIndex = arg2.lastIndexOf('"');
		return arg2.substring(1, closingQuoteIndex);
	}

	public static void writeTriple(final PrintWriter out, final String arg1, final String property, final String arg2)
	{
		out.println(arg1 + "\t" + property + "\t" + arg2 + ".");
	}

	// For some reason, the Freebase topic dumps don't have properly formatted numbers.
	// We need to replace
	//   fb:m.012_53     fb:people.person.height_meters  1.57.
	// with
	//   fb:m.012_53     fb:people.person.height_meters  "1.57"^^xsd:double.
	// This function operates on the second argument (value).
	public static String quoteValues(final String value)
	{
		if (value.equals("true"))
			return "\"true\"^^xsd:boolean";
		if (value.equals("false"))
			return "\"false\"^^xsd:boolean";

		// Short circuit: not numeric
		if (value.startsWith("\"") || value.length() > 0 && Character.isLetter(value.charAt(0)))
			return value;

		// Try to convert to integer
		try
		{
			Integer.parseInt(value);
			return "\"" + value + "\"^^xsd:int";
		}
		catch (final NumberFormatException e)
		{
		}
		// Try to convert to double
		try
		{
			Double.parseDouble(value);
			return "\"" + value + "\"^^xsd:double";
		}
		catch (final NumberFormatException e)
		{
		}
		return value;
	}

	public static Map<String, String> readCanonicalIdMap(final String canonicalIdMapPath)
	{
		return readCanonicalIdMap(canonicalIdMapPath, Integer.MAX_VALUE);
	}

	public static Map<String, String> readCanonicalIdMap(final String canonicalIdMapPath, final int maxInputLines)
	{
		final Map<String, String> canonicalIdMap = new HashMap<>();
		LogInfo.begin_track("Read %s", canonicalIdMapPath);
		try
		{
			final BufferedReader in = IOUtils.openIn(canonicalIdMapPath);
			String line;
			int numInputLines = 0;
			while (numInputLines < maxInputLines && (line = in.readLine()) != null)
			{
				numInputLines++;
				if (numInputLines % 10000000 == 0)
					LogInfo.logs("Read %s lines", numInputLines);
				final String[] tokens = line.split("\t");
				if (tokens.length != 2)
					throw new RuntimeException("Bad format: " + line);
				canonicalIdMap.put(tokens[0], tokens[1]);
			}
			in.close();
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
		LogInfo.end_track();
		return canonicalIdMap;
	}
}
