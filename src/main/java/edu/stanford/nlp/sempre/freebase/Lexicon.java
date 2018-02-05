package edu.stanford.nlp.sempre.freebase;

import edu.stanford.nlp.sempre.cache.StringCache;
import edu.stanford.nlp.sempre.cache.StringCacheUtils;
import edu.stanford.nlp.sempre.freebase.lexicons.LexicalEntry;
import edu.stanford.nlp.sempre.freebase.lexicons.LexicalEntry.LexicalEntrySerializer;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.queryparser.classic.ParseException;

public final class Lexicon
{
	public static class Options
	{
		@Option(gloss = "The path for the cache")
		public String cachePath;
	}

	public static Options opts = new Options();

	private static Lexicon lexicon;

	public static Lexicon getSingleton()
	{
		try
		{
			if (lexicon == null)
				lexicon = new Lexicon();
			return lexicon;
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public StringCache cache;

	private final EntityLexicon entityLexicon;
	private final UnaryLexicon unaryLexicon;
	private final BinaryLexicon binaryLexicon;

	public EntityLexicon getEntityLexicon()
	{
		return entityLexicon;
	}

	private Lexicon() throws IOException
	{
		LogInfo.begin_track("Lexicon()");
		// TODO(joberant): why is BinaryLexicon special? -- wait why is it special?
		entityLexicon = EntityLexicon.getInstance();
		unaryLexicon = UnaryLexicon.getInstance();
		binaryLexicon = BinaryLexicon.getInstance();
		LogInfo.end_track();

		if (opts.cachePath != null)
			cache = StringCacheUtils.create(opts.cachePath);
	}

	public List<? extends LexicalEntry> lookupUnaryPredicates(final String query) throws IOException
	{
		return unaryLexicon.lookupEntries(query);
	}

	public List<? extends LexicalEntry> lookupBinaryPredicates(final String query) throws IOException
	{
		return binaryLexicon.lookupEntries(query);
	}

	public List<? extends LexicalEntry> lookupEntities(final String query, final EntityLexicon.SearchStrategy strategy) throws IOException, ParseException
	{
		List<? extends LexicalEntry> entries = getCache("entity", query);
		if (entries == null)
			putCache("entity", query, entries = entityLexicon.lookupEntries(query, strategy));
		return entries;
	}

	private List<LexicalEntry> getCache(final String mode, final String query)
	{
		if (cache == null)
			return null;
		final String key = mode + ":" + query;
		String response;
		synchronized (cache)
		{
			response = cache.get(key);
		}
		if (response == null)
			return null;
		final LispTree tree = LispTree.proto.parseFromString(response);
		final List<LexicalEntry> entries = new ArrayList<>();
		for (int i = 0; i < tree.children.size(); i++)
			entries.add(LexicalEntrySerializer.entryFromLispTree(tree.child(i)));
		return entries;
	}

	private void putCache(final String mode, final String query, final List<? extends LexicalEntry> entries)
	{
		if (cache == null)
			return;
		final String key = mode + ":" + query;
		final LispTree result = LispTree.proto.newList();
		for (final LexicalEntry entry : entries)
			result.addChild(LexicalEntrySerializer.entryToLispTree(entry));
		synchronized (cache)
		{
			cache.put(key, result.toString());
		}
	}
}
