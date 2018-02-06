package edu.stanford.nlp.sempre.tables.match;

import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.FuzzyMatchFn.FuzzyMatchFnMode;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import edu.stanford.nlp.sempre.tables.TableCellProperties;
import edu.stanford.nlp.sempre.tables.TableColumn;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Perform fuzzy matching on the table knowledge graph.
 *
 * @author ppasupat
 */
public class EditDistanceFuzzyMatcher extends FuzzyMatcher
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
		@Option(gloss = "Also return the union of matched formulas")
		public boolean alsoReturnUnion = false;
		@Option(gloss = "Also return fb:row.consecutive...")
		public boolean alsoReturnConsecutive = false;
		@Option(gloss = "Also match parts")
		public boolean alsoMatchPart = false;
		@Option(gloss = "Also add normalization to matched binaries")
		public boolean alsoAddNormalization = false;
		@Option(gloss = "Maximum edit distance ratio")
		public double fuzzyMatchMaxEditDistanceRatio = 0;
		@Option(gloss = "Allow the query phrase to match part of the table cell content")
		public boolean fuzzyMatchSubstring = false;
		@Option(gloss = "Minimum query phrase length (number of characters) to invoke substring matching")
		public int fuzzyMatchSubstringMinQueryLength = 3;
		@Option(gloss = "If the number of cells matching the query exceeds this, don't return individual matches (but still return the union)")
		public int fuzzyMatchMaxTotalMatches = 5;
		@Option(gloss = "If the number of cells having the query as a substring exceeds this, don't perform substring matches")
		public int fuzzyMatchMaxSubstringMatches = 5;
		@Option(gloss = "Ignore cells with more than this number of characters when doing substring matching")
		public int fuzzyMatchSubstringMaxCellLength = 70;
	}

	public static Options opts = new Options();

	public EditDistanceFuzzyMatcher(final TableKnowledgeGraph graph)
	{
		super(graph);
		precompute();
	}

	protected final Map<String, Set<Formula>> phraseToEntityFormulas = new HashMap<>(), phraseToUnaryFormulas = new HashMap<>(), phraseToBinaryFormulas = new HashMap<>();
	protected final Map<String, Set<Formula>> substringToEntityFormulas = new HashMap<>(), substringToUnaryFormulas = new HashMap<>(), substringToBinaryFormulas = new HashMap<>();
	protected final Set<Formula> allEntityFormulas = new HashSet<>(), allUnaryFormulas = new HashSet<>(), allBinaryFormulas = new HashSet<>();

	protected void precompute()
	{
		// unary and binary
		for (final TableColumn column : graph.columns)
		{
			final Formula unary = getUnaryFormula(column);
			final Formula binary = getBinaryFormula(column);
			final Formula consecutive = opts.alsoReturnConsecutive && column.hasConsecutive() ? getConsecutiveBinaryFormula(column) : null;
			final List<Formula> normalizedBinaries = opts.alsoAddNormalization ? getNormalizedBinaryFormulas(column) : null;
			allUnaryFormulas.add(unary);
			allBinaryFormulas.add(binary);
			if (consecutive != null)
				allBinaryFormulas.add(consecutive);
			if (normalizedBinaries != null)
				allBinaryFormulas.addAll(normalizedBinaries);
			for (final String s : getAllCollapsedForms(column.originalString))
			{
				MapUtils.addToSet(phraseToUnaryFormulas, s, unary);
				MapUtils.addToSet(phraseToBinaryFormulas, s, binary);
				if (consecutive != null)
					MapUtils.addToSet(phraseToBinaryFormulas, s, consecutive);
				if (normalizedBinaries != null)
					for (final Formula f : normalizedBinaries)
						MapUtils.addToSet(phraseToBinaryFormulas, s, f);
			}
			if (opts.fuzzyMatchSubstring)
				for (final String s : getAllSubstringCollapsedForms(column.originalString))
				{
					MapUtils.addToSet(substringToUnaryFormulas, s, unary);
					MapUtils.addToSet(substringToBinaryFormulas, s, binary);
					if (consecutive != null)
						MapUtils.addToSet(substringToBinaryFormulas, s, consecutive);
					if (normalizedBinaries != null)
						for (final Formula f : normalizedBinaries)
							MapUtils.addToSet(substringToBinaryFormulas, s, f);
				}
		}
		// entity
		for (final TableCellProperties properties : graph.cellProperties)
		{
			final Formula entity = getEntityFormula(properties);
			allEntityFormulas.add(entity);
			for (final String s : getAllCollapsedForms(properties.originalString))
				MapUtils.addToSet(phraseToEntityFormulas, s, entity);
			if (opts.fuzzyMatchSubstring)
				for (final String s : getAllSubstringCollapsedForms(properties.originalString))
					MapUtils.addToSet(substringToEntityFormulas, s, entity);
		}
		// part (treated as extra entities)
		if (opts.alsoMatchPart)
			for (final NameValue value : graph.cellParts)
			{
				final Formula partEntity = getEntityFormula(value);
				allEntityFormulas.add(partEntity);
				for (final String s : getAllCollapsedForms(value._description))
					MapUtils.addToSet(phraseToEntityFormulas, s, partEntity);
				if (opts.fuzzyMatchSubstring)
					for (final String s : getAllSubstringCollapsedForms(value._description))
						MapUtils.addToSet(substringToEntityFormulas, s, partEntity);
			}
		// debug print
		if (opts.verbose >= 5)
		{
			debugPrint("phrase Entity", phraseToEntityFormulas);
			debugPrint("phrase Unary", phraseToUnaryFormulas);
			debugPrint("phrase Binary", phraseToBinaryFormulas);
			debugPrint("substring Entity", substringToEntityFormulas);
			debugPrint("substring Unary", substringToUnaryFormulas);
			debugPrint("substring Binary", substringToBinaryFormulas);
		}
	}

	void debugPrint(final String message, final Map<String, Set<Formula>> target)
	{
		LogInfo.begin_track("%s", message);
		for (final Map.Entry<String, Set<Formula>> entry : target.entrySet())
			LogInfo.logs("[%s] %s", entry.getKey(), entry.getValue());
		LogInfo.end_track();
	}

	// ============================================================
	// Collapse Strings + Compute Substrings
	// ============================================================

	static Collection<String> getAllSubstringCollapsedForms(final String original)
	{
		// Compute all substrings (based on spaces)
		final Set<String> collapsedForms = new HashSet<>();
		if (original.length() > opts.fuzzyMatchSubstringMaxCellLength)
			return collapsedForms;
		final String[] tokens = original.trim().split("[^A-Za-z0-9]+");
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.length; i++)
		{
			sb.setLength(0);
			for (int j = i; j < tokens.length; j++)
			{
				final String phrase = sb.append(" " + tokens[j]).toString();
				collapsedForms.addAll(getAllCollapsedForms(phrase));
			}
		}
		return collapsedForms;
	}

	static Collection<String> getAllCollapsedForms(final String original)
	{
		final Set<String> collapsedForms = new HashSet<>();
		collapsedForms.add(StringNormalizationUtils.collapseNormalize(original));
		final String normalized = StringNormalizationUtils.aggressiveNormalize(original);
		collapsedForms.add(StringNormalizationUtils.collapseNormalize(normalized));
		collapsedForms.remove("");
		return collapsedForms;
	}

	static String getCanonicalCollapsedForm(final String original)
	{
		return StringNormalizationUtils.collapseNormalize(original);
	}

	// ============================================================
	// Edit Distance
	// ============================================================

	static int editDistance(final String a, final String b)
	{
		final int m = a.length() + 1, n = b.length() + 1;
		int[] dists = new int[m], newDists = new int[m];
		for (int i = 0; i < m; i++)
			dists[i] = i;
		for (int j = 1; j < n; j++)
		{
			newDists[0] = j;
			for (int i = 1; i < m; i++)
				newDists[i] = Math.min(Math.min(dists[i] + 1, // Insert
						newDists[i - 1] + 1), // Delete
						dists[i - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)); // Replace
			final int[] swap = dists;
			dists = newDists;
			newDists = swap;
		}
		return dists[m - 1];
	}

	static double editDistanceRatio(final String a, final String b)
	{
		if (a.isEmpty() && b.isEmpty())
			return 0.0;
		return editDistance(a, b) * 2.0 / (a.length() + b.length());
	}

	// ============================================================
	// Caching fuzzy matches of a whole sentence
	// ============================================================

	Map<Pair<String, FuzzyMatchFnMode>, FuzzyMatchCache> cacheMap = new HashMap<>();

	protected FuzzyMatchCache cacheSentence(final List<String> sentence, final FuzzyMatchFnMode mode)
	{
		final String joined = String.join(" ", sentence);
		FuzzyMatchCache cache = cacheMap.get(new Pair<>(joined, mode));
		if (cache != null)
			return cache;
		// Compute a new FuzzyMatchCache
		cache = new FuzzyMatchCache();
		// aggregateCache[i,j] = all formulas matched by sentence[i'<=i:j'>=j], (i',j') != (i,j)
		final FuzzyMatchCache aggregateCache = new FuzzyMatchCache();
		for (int s = sentence.size(); s >= 1; s--)
			for (int i = 0; i + s <= sentence.size(); i++)
			{
				final int j = i + s;
				final String term = String.join(" ", sentence.subList(i, j));
				final Collection<Formula> formulas = new HashSet<>();
				if (!(FuzzyMatcher.opts.ignorePunctuationBoundedQueries && !checkPunctuationBoundaries(term)))
				{
					final String normalized = getCanonicalCollapsedForm(term);
					// Exact matches
					final Collection<Formula> exactMatched = getFuzzyExactMatchedFormulas(normalized, mode);
					if (exactMatched != null)
						formulas.addAll(exactMatched);
					// Substring matches
					if (opts.fuzzyMatchSubstring && normalized.length() >= opts.fuzzyMatchSubstringMinQueryLength)
					{
						final Collection<Formula> substringMatched = getFuzzySubstringMatchedFormulas(normalized, mode);
						if (substringMatched != null && substringMatched.size() <= opts.fuzzyMatchMaxSubstringMatches)
							formulas.addAll(substringMatched);
					}
				}
				cache.put(i, j, formulas);
				if (s > 1)
				{
					aggregateCache.addAll(i + 1, j, formulas);
					aggregateCache.addAll(i + 1, j, aggregateCache.get(i, j));
					aggregateCache.addAll(i, j - 1, formulas);
					aggregateCache.addAll(i, j - 1, aggregateCache.get(i, j));
				}
			}
		if (opts.verbose >= 3)
		{
			LogInfo.begin_track("Caching[%s] %s", mode, sentence);
			for (int s = 1; s <= sentence.size(); s++)
				for (int i = 0; i + s <= sentence.size(); i++)
				{
					final int j = i + s;
					final Collection<Formula> formulas = cache.get(i, j);
					if (formulas == null || formulas.isEmpty())
						continue;
					LogInfo.logs("{%s:%s} %s", i, j, sentence.subList(i, j));
					LogInfo.logs("%s", formulas);
				}
			LogInfo.end_track();
		}
		// Filter: If sentence[i:j] and sentence[i'<=i:j'>=j], (i',j') != (i,j),
		//         both matches formula f, remove f from sentence[i:j]
		// This is done to reduce over-generation
		for (int s = sentence.size(); s >= 1; s--)
			for (int i = 0; i + s <= sentence.size(); i++)
			{
				final int j = i + s;
				cache.removeAll(i, j, aggregateCache.get(i, j));
				final Collection<Formula> allMatched = cache.get(i, j);
				if (allMatched == null)
					continue;
				final Collection<Formula> unions = opts.alsoReturnUnion ? getUnions(allMatched) : null;
				if (allMatched.size() > opts.fuzzyMatchMaxTotalMatches)
					cache.clear(i, j);
				if (unions != null)
					cache.addAll(i, j, unions);
			}
		if (opts.verbose >= 3)
		{
			LogInfo.begin_track("Caching[%s] %s", mode, sentence);
			for (int s = 1; s <= sentence.size(); s++)
				for (int i = 0; i + s <= sentence.size(); i++)
				{
					final int j = i + s;
					final Collection<Formula> formulas = cache.get(i, j);
					if (formulas == null || formulas.isEmpty())
						continue;
					LogInfo.logs("[%s:%s] %s", i, j, sentence.subList(i, j));
					LogInfo.logs("%s", formulas);
				}
			LogInfo.end_track();
		}
		cacheMap.put(new Pair<>(joined, mode), cache);
		return cache;
	}

	// ============================================================
	// Main fuzzy matching interface
	// ============================================================

	/**
	 * Helper: Get the union of all fb:cell... and the union of all fb:part...
	 */
	protected Collection<Formula> getUnions(final Collection<Formula> formulas)
	{
		final Collection<Formula> unions = new ArrayList<>();
		Formula formula;
		// fb:cell...
		if ((formula = getUnion(formulas, TableTypeSystem.CELL_NAME_PREFIX)) != null)
			unions.add(formula);
		// fb:part...
		if ((formula = getUnion(formulas, TableTypeSystem.PART_NAME_PREFIX)) != null)
			unions.add(formula);
		return unions;
	}

	protected Formula getUnion(final Collection<Formula> formulas, final String prefix)
	{
		if (formulas == null || formulas.size() <= 1)
			return null;
		final List<Formula> sortedFormulaList = new ArrayList<>();
		for (final Formula formula : formulas)
			if (prefix == null || formula.toString().startsWith(prefix))
				sortedFormulaList.add(formula);
		if (sortedFormulaList.size() <= 1)
			return null;
		Collections.sort(sortedFormulaList, (final Formula v1, final Formula v2) -> v1.toString().compareTo(v2.toString()));
		Formula union = sortedFormulaList.get(0);
		for (int i = 1; i < sortedFormulaList.size(); i++)
			if (union.toString().compareTo(sortedFormulaList.get(i).toString()) <= 0)
				union = new MergeFormula(MergeFormula.Mode.or, union, sortedFormulaList.get(i));
			else
				union = new MergeFormula(MergeFormula.Mode.or, sortedFormulaList.get(i), union);
		return union;
	}

	@Override
	protected Collection<Formula> getFuzzyMatchedFormulasInternal(final String term, final FuzzyMatchFnMode mode)
	{
		final String normalized = getCanonicalCollapsedForm(term);
		final Collection<Formula> allMatched = new HashSet<>();
		// Exact matches
		final Collection<Formula> exactMatched = getFuzzyExactMatchedFormulas(normalized, mode);
		if (exactMatched != null)
			allMatched.addAll(exactMatched);
		// Substring matches
		if (opts.fuzzyMatchSubstring && normalized.length() >= opts.fuzzyMatchSubstringMinQueryLength)
		{
			final Collection<Formula> substringMatched = getFuzzySubstringMatchedFormulas(normalized, mode);
			if (substringMatched != null && substringMatched.size() <= opts.fuzzyMatchMaxSubstringMatches)
				allMatched.addAll(substringMatched);
		}
		final Formula union = opts.alsoReturnUnion ? getUnion(allMatched, null) : null;
		if (allMatched.size() > opts.fuzzyMatchMaxTotalMatches)
			allMatched.clear();
		if (union != null)
			allMatched.add(union);
		return allMatched;
	}

	protected Collection<Formula> getFuzzyExactMatchedFormulas(final String normalized, final FuzzyMatchFnMode mode)
	{
		Map<String, Set<Formula>> target;
		switch (mode)
		{
			case ENTITY:
				target = phraseToEntityFormulas;
				break;
			case UNARY:
				target = phraseToUnaryFormulas;
				break;
			case BINARY:
				target = phraseToBinaryFormulas;
				break;
			default:
				throw new RuntimeException("Unknown FuzzyMatchMode " + mode);
		}
		final Set<Formula> filtered = filterFuzzyMatched(normalized, target);
		// Debug print
		if (opts.verbose >= 3 && filtered != null && !filtered.isEmpty())
		{
			LogInfo.begin_track("(EXACT) Normalized: %s (%d)", normalized, filtered.size());
			for (final Formula formula : filtered)
				LogInfo.logs("%s", formula);
			LogInfo.end_track();
		}
		return filtered;
	}

	protected Collection<Formula> getFuzzySubstringMatchedFormulas(final String normalized, final FuzzyMatchFnMode mode)
	{
		Map<String, Set<Formula>> target;
		switch (mode)
		{
			case ENTITY:
				target = substringToEntityFormulas;
				break;
			case UNARY:
				target = substringToUnaryFormulas;
				break;
			case BINARY:
				target = substringToBinaryFormulas;
				break;
			default:
				throw new RuntimeException("Unknown FuzzyMatchMode " + mode);
		}
		final Set<Formula> filtered = filterFuzzyMatched(normalized, target);
		// Debug print
		if (opts.verbose >= 3 && filtered != null && !filtered.isEmpty())
		{
			LogInfo.begin_track("(SUBSTRING) Normalized: %s (%d)", normalized, filtered.size());
			for (final Formula formula : filtered)
				LogInfo.logs("%s", formula);
			LogInfo.end_track();
		}
		return filtered;
	}

	protected Set<Formula> filterFuzzyMatched(final String normalized, final Map<String, Set<Formula>> phraseToFormulas)
	{
		Set<Formula> filtered;
		if (opts.fuzzyMatchMaxEditDistanceRatio == 0)
			filtered = phraseToFormulas.get(normalized);
		else
		{
			filtered = new HashSet<>();
			for (final Map.Entry<String, Set<Formula>> entry : phraseToFormulas.entrySet())
				if (editDistanceRatio(entry.getKey(), normalized) < opts.fuzzyMatchMaxEditDistanceRatio)
					filtered.addAll(entry.getValue());
		}
		return filtered;
	}

	@Override
	protected Collection<Formula> getAllFormulasInternal(final FuzzyMatchFnMode mode)
	{
		switch (mode)
		{
			case ENTITY:
				return allEntityFormulas;
			case UNARY:
				return allUnaryFormulas;
			case BINARY:
				return allBinaryFormulas;
			default:
				throw new RuntimeException("Unknown FuzzyMatchMode " + mode);
		}
	}

	// ============================================================
	// Test
	// ============================================================

	public static void main(final String[] args)
	{
		System.out.println(editDistance("unionist", "unionists"));
	}
}
