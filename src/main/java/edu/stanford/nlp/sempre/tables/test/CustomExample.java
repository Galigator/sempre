package edu.stanford.nlp.sempre.tables.test;

import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.Values;
import edu.stanford.nlp.sempre.tables.StringNormalizationUtils;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import fig.basic.Evaluation;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import fig.exec.Execution;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom version of Example. - Allow additional keys "warning", "error", and "alternativeFormula" - Allow shorthand annotation for targetFormula
 *
 * @author ppasupat
 */
public class CustomExample extends Example
{
	public static class Options
	{
		// Format: "3-5,10,12-20"
		@Option(gloss = "Verbosity")
		public int verbose = 2;
		@Option(gloss = "Filter only these examples")
		public String filterExamples = null;
		@Option
		public boolean allowNoAnnotation = false;
	}

	public static Options opts = new Options();

	public List<String> warnings = new ArrayList<>();
	public List<String> errors = new ArrayList<>();
	public List<Formula> alternativeFormulas = new ArrayList<>();

	public CustomExample(final Example ex)
	{
		// Copy everything from ex
		super(ex.id, ex.utterance, ex.context, ex.targetFormula, ex.targetValue, ex.languageInfo);
	}

	static final Set<String> usefulTags = new HashSet<>(Arrays.asList("id", "utterance", "targetFormula", "targetValue", "targetValues", "context"));

	/**
	 * Convert LispTree to Example with additional tags
	 */
	public static CustomExample fromLispTree(final LispTree tree, final String defaultId)
	{
		final Builder b = new Builder().setId(defaultId);

		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("id".equals(label))
				b.setId(arg.child(1).value);
			else
				if ("utterance".equals(label))
					b.setUtterance(arg.child(1).value);
				else
					if ("targetFormula".equals(label))
					{
						final LispTree canonicalized = canonicalizeFormula(arg.child(1));
						b.setTargetFormula(Formulas.fromLispTree(canonicalized));
					}
					else
						if ("targetValue".equals(label) || "targetValues".equals(label))
						{
							if (arg.children.size() != 2)
								throw new RuntimeException("Expect one target value");
							b.setTargetValue(Values.fromLispTree(arg.child(1)));
						}
						else
							if ("context".equals(label))
								b.setContext(new ContextValue(arg));
		}
		b.setLanguageInfo(new LanguageInfo());

		final CustomExample ex = new CustomExample(b.createExample());
		boolean error = false;

		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("warning".equals(label))
				ex.warnings.add(arg.child(1).value);
			else
				if ("error".equals(label))
				{
					error = true;
					ex.errors.add(arg.child(1).value);
				}
				else
					if ("alternativeFormula".equals(label))
					{
						final LispTree canonicalized = canonicalizeFormula(arg.child(1));
						ex.alternativeFormulas.add(Formulas.fromLispTree(canonicalized));
					}
					else
						if (!usefulTags.contains(label))
							throw new RuntimeException("Invalid example argument: " + arg);
		}

		// Check formula and error
		if (ex.targetFormula == null && !error && !opts.allowNoAnnotation)
			throw new RuntimeException("Either error or targetFormula must be present.");
		if (ex.targetFormula != null && error)
			throw new RuntimeException("Cannot use error when targetFormula is present.");

		return ex;
	}

	static final Map<String, String> formulaMacros;
	static
	{
		formulaMacros = new HashMap<>();
		formulaMacros.put("@type", CanonicalNames.TYPE);
		formulaMacros.put("@row", TableTypeSystem.ROW_TYPE);
		formulaMacros.put("@next", TableTypeSystem.ROW_NEXT_VALUE._id);
		formulaMacros.put("@!next", "!" + TableTypeSystem.ROW_NEXT_VALUE._id);
		formulaMacros.put("@index", TableTypeSystem.ROW_INDEX_VALUE._id);
		formulaMacros.put("@!index", "!" + TableTypeSystem.ROW_INDEX_VALUE._id);
		formulaMacros.put("@p.num", TableTypeSystem.CELL_NUMBER_VALUE._id);
		formulaMacros.put("@!p.num", "!" + TableTypeSystem.CELL_NUMBER_VALUE._id);
		formulaMacros.put("@p.date", TableTypeSystem.CELL_DATE_VALUE._id);
		formulaMacros.put("@!p.date", "!" + TableTypeSystem.CELL_DATE_VALUE._id);
		formulaMacros.put("@p.num2", TableTypeSystem.CELL_NUM2_VALUE._id);
		formulaMacros.put("@!p.num2", "!" + TableTypeSystem.CELL_NUM2_VALUE._id);
		formulaMacros.put("@p.part", TableTypeSystem.CELL_PART_VALUE._id);
		formulaMacros.put("@!p.part", "!" + TableTypeSystem.CELL_PART_VALUE._id);
	}

	static final Pattern regexProperty = Pattern.compile("r\\.(.*)");
	static final Pattern regexReversedProperty = Pattern.compile("!r\\.(.*)");
	static final Pattern regexEntity = Pattern.compile("c\\.(.*)");
	static final Pattern regexPart = Pattern.compile("q\\.(.*)");

	/**
	 * Return a new LispTree representing the canonicalized version of the original formula
	 */
	public static LispTree canonicalizeFormula(final LispTree orig)
	{
		if (orig.isLeaf())
		{
			final String value = orig.value;
			// 45 --> (number 45)
			if (StringNormalizationUtils.parseNumberStrict(value) != null)
				return LispTree.proto.newList("number", value);
			// value with "@" --> canonicalized name
			if (value.contains("@"))
			{
				final String canonicalName = formulaMacros.get(value);
				if (canonicalName == null)
					throw new RuntimeException("Unrecognized macro: " + value);
				return LispTree.proto.newLeaf(canonicalName);
			}
			// c.xxx or c_xxx.yyy --> canonicalized name
			Matcher match;
			if ((match = regexProperty.matcher(value)).matches())
				return LispTree.proto.newLeaf(TableTypeSystem.getRowPropertyName(match.group(1)));
			if ((match = regexReversedProperty.matcher(value)).matches())
				return LispTree.proto.newLeaf("!" + TableTypeSystem.getRowPropertyName(match.group(1)));
			if ((match = regexEntity.matcher(value)).matches())
				return LispTree.proto.newLeaf(TableTypeSystem.CELL_NAME_PREFIX + "." + match.group(1));
			if ((match = regexPart.matcher(value)).matches())
				return LispTree.proto.newLeaf(TableTypeSystem.PART_NAME_PREFIX + "." + match.group(1));
			if (value.contains(".") && !(value.startsWith("fb:") || value.startsWith("!fb:")))
				throw new RuntimeException("Unhandled '.': " + value);
			return orig;
		}
		else
		{
			final LispTree answer = LispTree.proto.newList();
			// Handle special cases
			final LispTree head = orig.child(0);
			if ("date".equals(head.value))
				for (final LispTree child : orig.children)
					answer.addChild(LispTree.proto.newLeaf(child.value));
			else
				for (final LispTree child : orig.children)
					answer.addChild(canonicalizeFormula(child));
			return answer;
		}
	}

	// ============================================================
	// Read dataset
	// ============================================================

	public interface ExampleProcessor
	{
		void run(CustomExample ex);
	}

	public static boolean checkFilterExamples(final int n)
	{
		if (opts.filterExamples == null || opts.filterExamples.isEmpty())
			return true;
		for (final String range : opts.filterExamples.split(","))
		{
			final String[] tokens = range.split("-");
			if (tokens.length == 1)
				if (Integer.parseInt(tokens[0]) == n)
					return true;
			if (tokens.length == 2)
				if (Integer.parseInt(tokens[0]) <= n && Integer.parseInt(tokens[1]) >= n)
					return true;
		}
		return false;
	}

	public static List<CustomExample> getDataset(final List<Pair<String, String>> pathPairs, final ExampleProcessor processor)
	{
		LogInfo.begin_track_printAll("Dataset.read");
		final Evaluation evaluation = new Evaluation();
		final List<CustomExample> examples = new ArrayList<>();
		for (final Pair<String, String> pathPair : pathPairs)
		{
			final String group = pathPair.getFirst();
			final String path = pathPair.getSecond();
			Execution.putOutput("group", group);

			LogInfo.begin_track("Reading %s", path);
			final Iterator<LispTree> trees = LispTree.proto.parseFromFile(path);

			while (trees.hasNext())
			{
				// Format: (example (id ...) (utterance ...) (targetFormula ...) (targetValue ...))
				final LispTree tree = trees.next();
				if ("metadata".equals(tree.child(0).value))
					continue;
				if (!checkFilterExamples(examples.size()))
				{ // Skip -- for debugging
					examples.add(null);
					continue;
				}
				if (opts.verbose >= 2)
					LogInfo.begin_track("Reading Example %s", examples.size());
				if (tree.children.size() < 2 && !"example".equals(tree.child(0).value))
					throw new RuntimeException("Invalid example: " + tree);
				CustomExample ex = null;
				Execution.putOutput("example", examples.size());
				try
				{
					ex = CustomExample.fromLispTree(tree, path + ":" + examples.size()); // Specify a default id if it doesn't exist
					ex.preprocess();
				}
				catch (final Exception e)
				{
					final StringWriter sw = new StringWriter();
					e.printStackTrace(new PrintWriter(sw));
					LogInfo.warnings("Example %s: CONTAINS ERROR! %s:\n%s", ex == null ? ex : ex.id, e, sw);
				}
				if (opts.verbose >= 2)
					ex.log();
				else
					if (opts.verbose >= 1)
						LogInfo.logs("Example %s (%d): %s => %s", ex.id, examples.size(), ex.getTokens(), ex.targetValue);
				examples.add(ex);
				if (opts.verbose >= 2)
				{
					for (final String warning : ex.warnings)
						LogInfo.logs("WARNING: %s", warning);
					for (final String error : ex.errors)
						LogInfo.logs("ERROR: %s", error);
				}
				if (processor != null)
					processor.run(ex);
				if (opts.verbose >= 2)
					LogInfo.end_track();
				if (ex != null && ex.evaluation != null)
				{
					LogInfo.logs("Current: %s", ex.evaluation.summary());
					evaluation.add(ex.evaluation);
					LogInfo.logs("Cumulative(%s): %s", group, evaluation.summary());
				}
			}
			LogInfo.end_track();
			LogInfo.logs("Stats for %s: %s", group, evaluation.summary());
			evaluation.logStats(group);
			evaluation.putOutput(group);
		}
		LogInfo.end_track();
		return examples;
	}

	public static List<CustomExample> getDataset(final List<Pair<String, String>> pathPairs)
	{
		return getDataset(pathPairs, null);
	}

	public static List<CustomExample> getDataset(final String path)
	{
		return getDataset(Collections.singletonList(new Pair<>("train", path)), null);
	}

}
