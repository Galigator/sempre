package edu.stanford.nlp.sempre;

import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.StringDoubleVec;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Lexicon maps phrases (e.g., born) to lexical entries, which contain a formula (e.g., fb:people.person.place_of_birth) and a type. This class is meant to be
 * simpler/faster than the normal UnaryLexicon/BinaryLexicon. Note: this class exists because it was annoying to add types into the normal UnaryLexicon and I
 * didn't want to break backward compatibility with the rest of the code. Also, the normal lexicon is really slow with all those hash maps. The lexicon here
 * only knows about Formulas and fuzzy matching. In the lexicon, we have rawPhrase. In the user query, we have phrase.
 *
 * @author Percy Liang
 */
public final class SimpleLexicon
{
	public static class Entry
	{
		// rawPhrase was the original phrase in the Lexicon
		public Entry(final String rawPhrase, final Formula formula, final SemType type, final StringDoubleVec features)
		{
			this.rawPhrase = rawPhrase;
			this.formula = formula;
			this.type = type;
			this.features = features;
		}

		public final String rawPhrase;
		public final Formula formula;
		public final SemType type;
		public final StringDoubleVec features;

		@Override
		public String toString()
		{
			return "[" + rawPhrase + " => " + formula + " : " + type + "]";
		}
	}

	public static class Options
	{
		@Option(gloss = "Path to load lexicon files from")
		public List<String> inPaths;
		@Option(gloss = "Types to allow suffix (last word) matche (for people names")
		public List<String> matchSuffixTypes;
	}

	public static Options opts = new Options();

	private static SimpleLexicon lexicon;

	public static SimpleLexicon getSingleton()
	{
		if (lexicon == null)
			lexicon = new SimpleLexicon();
		return lexicon;
	}

	private SimpleLexicon()
	{
		if (opts.inPaths == null)
			return;
		for (final String path : opts.inPaths)
			read(path);
	}

	// Mapping from phrase
	Map<String, List<Entry>> entries = new HashMap<>();

	public void read(final String path)
	{
		LogInfo.begin_track("SimpleLexicon.read(%s)", path);
		try
		{
			final BufferedReader in = IOUtils.openIn(path);
			String line;
			int numLines = 0;
			final int oldNumEntries = entries.size();
			while ((line = in.readLine()) != null)
			{
				final Map<String, Object> map = Json.readMapHard(line);
				numLines++;

				final String rawPhrase = (String) map.get("lexeme");
				final Formula formula = Formula.fromString((String) map.get("formula"));

				// Type
				final String typeStr = (String) map.get("type");
				final SemType type = typeStr != null ? SemType.fromString(typeStr) : TypeInference.inferType(formula);

				// Features
				StringDoubleVec features = null;
				final Map<String, Double> featureMap = (Map<String, Double>) map.get("features");
				if (featureMap != null)
				{
					features = new StringDoubleVec();
					for (final Map.Entry<String, Double> e : featureMap.entrySet())
						features.add(e.getKey(), e.getValue());
					features.trimToSize();
				}

				// Add verbatim feature
				final Entry entry = new Entry(rawPhrase, formula, type, features);
				final String phrase = entry.rawPhrase.toLowerCase();
				MapUtils.addToList(entries, phrase, entry);

				// For last names
				final String[] parts = phrase.split(" ");
				if (opts.matchSuffixTypes != null && opts.matchSuffixTypes.contains(typeStr) && parts.length > 1)
				{
					final StringDoubleVec newFeatures = new StringDoubleVec();
					if (features != null)
						for (final StringDoubleVec.Entry e : features)
							newFeatures.add(e.getFirst(), e.getSecond());
					newFeatures.add("isSuffix", 1);
					newFeatures.trimToSize();
					final Entry newEntry = new Entry(rawPhrase, formula, type, newFeatures);
					MapUtils.addToList(entries, parts[parts.length - 1], newEntry);
				}
				// In the future, add other mechanisms for lemmatization.
			}
			LogInfo.logs("Read %s lines, generated %d entries (now %d total)", numLines, entries.size() - oldNumEntries, entries.size());
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
		LogInfo.end_track();
	}

	public List<Entry> lookup(final String phrase)
	{
		return MapUtils.get(entries, phrase, Collections.EMPTY_LIST);
	}
}
