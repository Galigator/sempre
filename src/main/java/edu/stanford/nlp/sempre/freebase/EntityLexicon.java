package edu.stanford.nlp.sempre.freebase;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.cache.StringCache;
import edu.stanford.nlp.sempre.cache.StringCacheUtils;
import edu.stanford.nlp.sempre.freebase.index.FbEntitySearcher;
import edu.stanford.nlp.sempre.freebase.index.FbIndexField;
import edu.stanford.nlp.sempre.freebase.lexicons.EntrySource;
import edu.stanford.nlp.sempre.freebase.lexicons.LexicalEntry;
import edu.stanford.nlp.sempre.freebase.lexicons.LexicalEntry.EntityLexicalEntry;
import edu.stanford.nlp.sempre.freebase.lexicons.TokenLevelMatchFeatures;
import edu.stanford.nlp.sempre.freebase.utils.FileUtils;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.util.ArrayUtils;
import edu.stanford.nlp.util.StringUtils;
import fig.basic.MapUtils;
import fig.basic.Option;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.ParseException;

public final class EntityLexicon
{
	public enum SearchStrategy
	{
		exact, inexact, fbsearch
	}

	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
		@Option(gloss = "Number of results return by the lexicon")
		public int maxEntries = 1000;
		@Option(gloss = "Number of documents queried from Lucene")
		public int numOfDocs = 10000;
		@Option(gloss = "Path to the exact match lucene index directory")
		public String exactMatchIndex;
		@Option(gloss = "Path to the inexact match lucene index directory")
		public String inexactMatchIndex = "lib/lucene/4.4/inexact";
		@Option(gloss = "Cache path to the mid-to-id path")
		public String mid2idPath;
		@Option(gloss = "Path to entity popularity file")
		public String entityPopularityPath;
	}

	public static Options opts = new Options();

	private static EntityLexicon entityLexicon;

	public static EntityLexicon getInstance()
	{
		if (entityLexicon == null)
			entityLexicon = new EntityLexicon();
		return entityLexicon;
	}

	FbEntitySearcher exactSearcher; // Lucene
	FbEntitySearcher inexactSearcher; // Lucene

	FreebaseSearch freebaseSearch; // Google's API
	StringCache mid2idCache; // Google's API spits back mids, which we need to convert to ids
	Map<String, Double> entityPopularityMap;

	private EntityLexicon()
	{
		loadEntityPopularity();
	}

	public List<EntityLexicalEntry> lookupEntries(final String query, final SearchStrategy strategy) throws ParseException, IOException
	{
		if (strategy == null)
			throw new RuntimeException("No entity search strategy specified");
		switch (strategy)
		{
			case exact:
				if (exactSearcher == null)
					exactSearcher = new FbEntitySearcher(opts.exactMatchIndex, opts.numOfDocs, "exact");
				return lookupEntries(exactSearcher, query);
			case inexact:
				if (inexactSearcher == null)
					inexactSearcher = new FbEntitySearcher(opts.inexactMatchIndex, opts.numOfDocs, "inexact");
				return lookupEntries(inexactSearcher, query);
			case fbsearch:
				if (freebaseSearch == null)
					freebaseSearch = new FreebaseSearch();
				if (mid2idCache == null)
					mid2idCache = StringCacheUtils.create(opts.mid2idPath);
				return lookupFreebaseSearchEntities(query);
			default:
				throw new RuntimeException("Unknown entity search strategy: " + strategy);
		}
	}

	private void loadEntityPopularity()
	{
		entityPopularityMap = new HashMap<>();
		if (opts.entityPopularityPath == null)
			return;
		for (final String line : IOUtils.readLines(opts.entityPopularityPath))
		{
			final String[] tokens = line.split("\t");
			entityPopularityMap.put(tokens[0], Double.parseDouble(tokens[1]));
		}
	}

	public List<EntityLexicalEntry> lookupFreebaseSearchEntities(final String query)
	{
		final FreebaseSearch.ServerResponse response = freebaseSearch.lookup(query);
		final List<EntityLexicalEntry> entities = new ArrayList<>();
		if (response.error != null)
			throw new RuntimeException(response.error.toString());
		// num of words in query
		final int numOfQueryWords = query.split("\\s+").length;
		for (final FreebaseSearch.Entry e : response.entries)
		{
			if (entities.size() >= opts.maxEntries)
				break;
			// Note: e.id might not be the same one we're using (e.g., fb:en.john_f_kennedy_airport versus fb:en.john_f_kennedy_international_airport),
			// so get the one from our canonical mid2idCache
			final String id = mid2idCache.get(e.mid);
			if (id == null)
				continue; // Skip if no ID (probably not worth referencing)
			// skip if it is a long phrase that is not an exact match
			if (numOfQueryWords >= 4 && !query.toLowerCase().equals(e.name.toLowerCase()))
				continue;

			final int distance = editDistance(query.toLowerCase(), e.name.toLowerCase()); // Is this actually useful?
			final Counter<String> entityFeatures = TokenLevelMatchFeatures.extractFeatures(query, e.name);
			final double popularity = MapUtils.get(entityPopularityMap, id, 0d);
			entityFeatures.incrementCount("text_popularity", Math.log(popularity + 1));
			entities.add(new EntityLexicalEntry(query, query, Collections.singleton(e.name), new ValueFormula<>(new NameValue(id, e.name)), EntrySource.FBSEARCH, e.score, distance, new FreebaseTypeLookup().getEntityTypes(id), entityFeatures));
		}
		return entities;
	}

	public List<EntityLexicalEntry> lookupEntries(final FbEntitySearcher searcher, String textDesc) throws ParseException, IOException
	{

		final List<EntityLexicalEntry> res = new ArrayList<>();
		textDesc = textDesc.replaceAll("\\?", "\\\\?").toLowerCase();
		final List<Document> docs = searcher.searchDocs(textDesc);
		for (final Document doc : docs)
		{

			final Formula formula = Formula.fromString(doc.get(FbIndexField.ID.fieldName()));
			final String[] fbDescriptions = new String[] { doc.get(FbIndexField.TEXT.fieldName()) };
			final String typesDesc = doc.get(FbIndexField.TYPES.fieldName());

			final Set<String> types = new HashSet<>();
			if (typesDesc != null)
			{
				final String[] tokens = typesDesc.split(",");
				Collections.addAll(types, tokens);
			}

			final double popularity = Double.parseDouble(doc.get(FbIndexField.POPULARITY.fieldName()));
			final int distance = editDistance(textDesc.toLowerCase(), fbDescriptions[0].toLowerCase());
			final Counter<String> tokenEditDistanceFeatures = TokenLevelMatchFeatures.extractFeatures(textDesc, fbDescriptions[0]);

			if ((popularity > 0 || distance == 0) && TokenLevelMatchFeatures.diffSetSize(textDesc, fbDescriptions[0]) < 4)
				res.add(new EntityLexicalEntry(textDesc, textDesc, ArrayUtils.asSet(fbDescriptions), formula, EntrySource.LUCENE, popularity, distance, types, tokenEditDistanceFeatures));
		}
		Collections.sort(res, new LexicalEntryComparator());
		return res.subList(0, Math.min(res.size(), opts.maxEntries));
	}

	private int editDistance(final String query, final String name)
	{

		final String[] queryTokens = FileUtils.omitPunct(query).split("\\s+");
		final String[] nameTokens = FileUtils.omitPunct(name).split("\\s+");

		final StringBuilder querySb = new StringBuilder();
		for (final String queryToken : queryTokens)
			querySb.append(queryToken).append(" ");

		final StringBuilder nameSb = new StringBuilder();
		for (final String nameToken : nameTokens)
			nameSb.append(nameToken).append(" ");

		return StringUtils.editDistance(querySb.toString().trim(), nameSb.toString().trim());
	}

	public static class LexicalEntryComparator implements Comparator<LexicalEntry>
	{
		@Override
		public int compare(final LexicalEntry arg0, final LexicalEntry arg1)
		{

			if (arg0.popularity > arg1.popularity)
				return -1;
			if (arg0.popularity < arg1.popularity)
				return 1;
			if (arg0.distance < arg1.distance)
				return -1;
			if (arg0.distance > arg1.distance)
				return 1;
			return 0;
		}
	}
}
