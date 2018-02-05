package edu.stanford.nlp.sempre.tables;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.tables.serialize.LazyLoadedExampleList;
import edu.stanford.nlp.sempre.tables.serialize.SerializedDataset;
import edu.stanford.nlp.sempre.tables.test.CustomExample;
import edu.stanford.nlp.sempre.tables.test.TableFormulaCanonicalizer;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Evaluate if the predicted formula matches one of the annotated formulas. Use example ID to look up the formula annotation. The annotationPath option can
 * point to one of the following: - an annotated LispTree file with targetFormula / alternativeFormula fields (formula shorthands are allowed) - a gzip dump
 * file created by tables.serialize.SerializedDumper - a directory containing gzip dump files created by tables.serialize.SerializedDumper
 *
 * @author ppasupat
 */
public class TableFormulaEvaluator extends TableValueEvaluator
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 0;
		@Option(gloss = "Path for formula annotation")
		public String annotationPath = null;
		@Option(gloss = "Whether to fall back to ValueEvaluator when the example id does not exist")
		public boolean fallBackToValueEvaluator = true;
	}

	public static Options opts = new Options();

	protected Collection<String> availableIds;

	// Map from ID string to target formulas.
	// Use only when the file is an annotated LispTree file.
	protected Map<String, List<Formula>> idToTargetFormulas;

	// Map from ID string to LazyLoadedExampleList and example index.
	// Use only when the file is dumped from SerializedDumper
	protected Map<String, Pair<LazyLoadedExampleList, Integer>> idToSerializedIndex;

	public TableFormulaEvaluator()
	{
		// Load annotation file
		if (opts.annotationPath == null || opts.annotationPath.isEmpty())
			throw new RuntimeException("Annotation file not specified.");
		// Determine file type
		if (new File(opts.annotationPath).isDirectory() || opts.annotationPath.endsWith(".gz"))
			readSerializedFile();
		else
			readAnnotationFile();
	}

	protected void readSerializedFile()
	{
		idToSerializedIndex = new HashMap<>();
		final SerializedDataset dataset = new SerializedDataset();
		if (new File(opts.annotationPath).isDirectory())
			dataset.readDir(opts.annotationPath);
		else
			dataset.read("annotated", opts.annotationPath);
		for (final String group : dataset.groups())
		{
			final LazyLoadedExampleList examples = dataset.examples(group);
			final List<String> ids = examples.getAllIds();
			for (int i = 0; i < ids.size(); i++)
				idToSerializedIndex.put(ids.get(i), new Pair<>(examples, i));
		}
		availableIds = idToSerializedIndex.keySet();
	}

	protected void readAnnotationFile()
	{
		LogInfo.begin_track("Reading annotated examples");
		idToTargetFormulas = new HashMap<>();
		CustomExample.getDataset(Arrays.asList(new Pair<>("annotated", opts.annotationPath)), ex ->
		{
			final List<Formula> targetFormulas = new ArrayList<>();
			// Canonicalize formulas
			if (ex.targetFormula != null)
				targetFormulas.add(TableFormulaCanonicalizer.canonicalizeFormula(ex.targetFormula));
			if (ex.alternativeFormulas != null)
				for (final Formula alternativeFormula : ex.alternativeFormulas)
					targetFormulas.add(TableFormulaCanonicalizer.canonicalizeFormula(alternativeFormula));
			idToTargetFormulas.put(ex.id, targetFormulas);
		});
		availableIds = idToTargetFormulas.keySet();
		LogInfo.end_track();
	}

	/**
	 * Get formula compatibility. Fall back to ValueEvaluator if the example is not in the annotation file.
	 */
	public double getCompatibility(final Example targetEx, final Derivation deriv)
	{
		if (availableIds.contains(targetEx.id))
		{
			if (idToTargetFormulas == null)
				return getCompatibilitySerializedStrict(targetEx, deriv.formula);
			else
				return getCompatibilityAnnotationStrict(targetEx, deriv.formula);
		}
		else
			return opts.fallBackToValueEvaluator ? getCompatibility(targetEx.targetValue, deriv.value) : 0;
	}

	// Add a little cache
	LoadingCache<Example, List<Formula>> canonicalizedCache = CacheBuilder.newBuilder().maximumSize(20).build(new CacheLoader<Example, List<Formula>>()
	{
		@Override
		public List<Formula> load(final Example ex) throws Exception
		{
			LogInfo.logs("Canonicalizing %s", ex.id);
			final List<Formula> canonicalized = new ArrayList<>();
			final Pair<LazyLoadedExampleList, Integer> identifier = idToSerializedIndex.get(ex.id);
			final Example annotated = identifier.getFirst().get(identifier.getSecond());
			for (final Derivation targetDeriv : annotated.predDerivations)
				canonicalized.add(TableFormulaCanonicalizer.canonicalizeFormula(targetDeriv.formula));
			LogInfo.logs("Canonicalized %d formulas", canonicalized.size());
			return canonicalized;
		}
	});

	public double getCompatibilitySerializedStrict(final Example targetEx, Formula formula)
	{
		try
		{
			final List<Formula> canonicalized = canonicalizedCache.get(targetEx);
			formula = TableFormulaCanonicalizer.canonicalizeFormula(formula);
			for (final Formula targetFormula : canonicalized)
				if (targetFormula.equals(formula))
					return 1;
			return 0;
		}
		catch (final ExecutionException e)
		{
			throw new RuntimeException(e.getCause());
		}
	}

	public double getCompatibilityAnnotationStrict(final Example targetEx, Formula formula)
	{
		final List<Formula> targetFormulas = idToTargetFormulas.get(targetEx.id);
		if (targetFormulas == null)
			throw new RuntimeException("Example ID " + targetEx.id + " not found in annotated data");
		formula = TableFormulaCanonicalizer.canonicalizeFormula(formula);
		for (final Formula targetFormula : targetFormulas)
			if (targetFormula.equals(formula))
				return 1;
		return 0;
	}

	public boolean containsId(final String id)
	{
		return availableIds.contains(id);
	}

	// ============================================================
	// DEBUG
	// ============================================================

	public void log(final Example targetEx, Formula formula)
	{
		final List<Formula> targetFormulas = idToTargetFormulas.get(targetEx.id);
		if (targetFormulas.isEmpty())
			LogInfo.logs("Gold: NONE");
		else
			for (final Formula targetFormula : targetFormulas)
				LogInfo.logs("Gold: %s", targetFormula);
		formula = TableFormulaCanonicalizer.canonicalizeFormula(formula);
		LogInfo.logs("Predicted: %s", formula);
	}
}
