package edu.stanford.nlp.sempre.freebase;

import com.google.common.base.Strings;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.sempre.FeatureVector;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Json;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.freebase.FbFormulasInfo.UnaryFormulaInfo;
import edu.stanford.nlp.sempre.freebase.lexicons.EntrySource;
import edu.stanford.nlp.sempre.freebase.lexicons.LexicalEntry.LexiconValue;
import edu.stanford.nlp.sempre.freebase.lexicons.LexicalEntry.UnaryLexicalEntry;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.StopWatchSet;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * lexicon for unaries: "city"-->fb:location.citytown Loads the lexicon from a file and has some features
 */
public final class UnaryLexicon
{

	public static class Options
	{
		@Option(gloss = "Number of results return by the lexicon")
		public int maxEntries = 1000;
		@Option(gloss = "Path to unary lexicon file")
		public String unaryLexiconFilePath = "lib/fb_data/7/unaryInfoStringAndAlignment.txt";
		@Option(gloss = "Threshold for filtering unaries")
		public int unaryFilterThreshold = 5;
		@Option(gloss = "Verbosity")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	private static UnaryLexicon unaryLexicon;

	public static UnaryLexicon getInstance()
	{
		if (unaryLexicon == null)
			unaryLexicon = new UnaryLexicon();
		return unaryLexicon;
	}

	private final Map<String, List<UnaryLexicalEntry>> lexemeToEntryList = new HashMap<>();

	public static final String INTERSECTION = "intersection";
	public static final String NL_SIZE = "nl_size";
	public static final String FB_SIZE = "fb_size";

	private UnaryLexicon()
	{
		if (Strings.isNullOrEmpty(opts.unaryLexiconFilePath))
			throw new RuntimeException("Missing unary lexicon file");
		read();
		sortLexiconEntries();
	}

	private void sortLexiconEntries()
	{
		final Comparator<UnaryLexicalEntry> comparator = new UnaryLexicalEntryComparator();
		for (final List<UnaryLexicalEntry> uEntries : lexemeToEntryList.values())
			Collections.sort(uEntries, comparator);
	}

	private void read()
	{
		LogInfo.begin_track("Loading unary lexicon file " + opts.unaryLexiconFilePath);
		for (final String line : IOUtils.readLines(opts.unaryLexiconFilePath))
		{
			final LexiconValue lv = Json.readValueHard(line, LexiconValue.class);
			addEntry(lv.lexeme, lv.source, lv.formula, lv.features);
		}
		LogInfo.log("Number of lexemes: " + lexemeToEntryList.size());
		LogInfo.end_track();
	}

	private void addEntry(final String nl, final String source, final Formula formula, final Map<String, Double> featureMap)
	{

		final FbFormulasInfo ffi = FbFormulasInfo.getSingleton();
		if (ffi.getUnaryInfo(formula) != null)
		{
			final UnaryFormulaInfo uInfo = ffi.getUnaryInfo(formula);
			final UnaryLexicalEntry uEntry = new UnaryLexicalEntry(nl, nl, new TreeSet<>(uInfo.descriptions), formula, EntrySource.parseSourceDesc(source), uInfo.popularity, new TreeMap<>(featureMap), uInfo.types);
			MapUtils.addToList(lexemeToEntryList, nl, uEntry);
		}
		else
			if (opts.verbose >= 3)
				LogInfo.warnings("Missing info for unary: %s ", formula);
	}

	public void save(final String outFile) throws IOException
	{

		final PrintWriter writer = IOUtils.getPrintWriter(outFile);
		for (final String nl : lexemeToEntryList.keySet())
			for (final UnaryLexicalEntry uEntry : lexemeToEntryList.get(nl))
			{
				final LexiconValue lv = new LexiconValue(nl, uEntry.formula, uEntry.source.toString(), uEntry.alignmentScores);
				writer.println(Json.writeValueAsStringHard(lv));
			}
		writer.close();
	}

	public List<UnaryLexicalEntry> lookupEntries(final String textDesc) throws IOException
	{

		final List<UnaryLexicalEntry> entries = lexemeToEntryList.get(textDesc.toLowerCase());
		if (entries != null)
		{
			final List<UnaryLexicalEntry> res = new ArrayList<>();
			for (int i = 0; i < Math.min(entries.size(), opts.maxEntries); ++i)
				if (valid(entries.get(i)))
					res.add(entries.get(i));
			return res;
		}
		return Collections.emptyList();
	}

	/** Checks if an entry is valid (e.g. we filter if intersection is too small) */
	private boolean valid(final UnaryLexicalEntry lexicalEntry)
	{
		return lexicalEntry.source != EntrySource.ALIGNMENT || MapUtils.getDouble(lexicalEntry.alignmentScores, INTERSECTION, 0.0) >= opts.unaryFilterThreshold;
	}

	public void sortLexiconByFeedback(final Params params)
	{
		StopWatchSet.begin("UnaryLexicon.sortLexiconByFeedback");
		LogInfo.log("Number of entries: " + lexemeToEntryList.size());
		final UnaryLexEntrybyFeaturesComparator comparator = new UnaryLexEntrybyFeaturesComparator(params);
		for (final String lexeme : lexemeToEntryList.keySet())
		{
			Collections.sort(lexemeToEntryList.get(lexeme), comparator);
			if (LexiconFn.opts.verbose > 0)
			{
				LogInfo.logs("Sorted list for lexeme=%s", lexeme);
				for (final UnaryLexicalEntry uEntry : lexemeToEntryList.get(lexeme))
				{
					final FeatureVector fv = new FeatureVector();
					LexiconFn.getUnaryEntryFeatures(uEntry, fv);
					LogInfo.logs("Entry=%s, dotprod=%s", uEntry, fv.dotProduct(comparator.params));
				}
			}
		}
		StopWatchSet.end();
	}

	public class UnaryLexEntrybyFeaturesComparator implements Comparator<UnaryLexicalEntry>
	{
		public final Params params;

		public UnaryLexEntrybyFeaturesComparator(final Params params)
		{
			this.params = params;
		}

		@Override
		public int compare(final UnaryLexicalEntry entry1, final UnaryLexicalEntry entry2)
		{

			final FeatureVector features1 = new FeatureVector();
			final FeatureVector features2 = new FeatureVector();
			LexiconFn.getUnaryEntryFeatures(entry1, features1);
			LexiconFn.getUnaryEntryFeatures(entry2, features2);
			final double score1 = features1.dotProduct(params);
			final double score2 = features2.dotProduct(params);
			if (score1 > score2)
				return -1;
			if (score1 < score2)
				return +1;
			// back off to usual thing
			final double entry1Intersection = MapUtils.getDouble(entry1.alignmentScores, INTERSECTION, 0.0);
			final double entry2Intersection = MapUtils.getDouble(entry2.alignmentScores, INTERSECTION, 0.0);
			if (entry1Intersection > entry2Intersection)
				return -1;
			if (entry1Intersection < entry2Intersection)
				return 1;
			if (entry1.popularity > entry2.popularity)
				return -1;
			if (entry1.popularity < entry2.popularity)
				return 1;
			return 0;
		}
	}

	public static class UnaryLexicalEntryComparator implements Comparator<UnaryLexicalEntry>
	{

		public static final String INTERSECTION = "intersection";

		@Override
		public int compare(final UnaryLexicalEntry entry1, final UnaryLexicalEntry entry2)
		{

			final double entry1Intersection = MapUtils.getDouble(entry1.alignmentScores, INTERSECTION, 0.0);
			final double entry2Intersection = MapUtils.getDouble(entry2.alignmentScores, INTERSECTION, 0.0);
			if (entry1Intersection > entry2Intersection)
				return -1;
			if (entry1Intersection < entry2Intersection)
				return 1;
			if (entry1.popularity > entry2.popularity)
				return -1;
			if (entry1.popularity < entry2.popularity)
				return 1;
			// todo - this is to break ties - make more efficient
			final int stringComparison = entry1.formula.toString().compareTo(entry2.formula.toString());
			if (stringComparison < 0)
				return -1;
			if (stringComparison > 0)
				return +1;
			return 0;
		}
	}
}
