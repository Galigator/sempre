package edu.stanford.nlp.sempre.freebase;

import edu.stanford.nlp.sempre.AggregateFormula;
import edu.stanford.nlp.sempre.Colorizer;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Executor;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.MarkFormula;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.NotFormula;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.ReverseFormula;
import edu.stanford.nlp.sempre.SuperlativeFormula;
import edu.stanford.nlp.sempre.ValueFormula;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.exec.Execution;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Luke Zettlemoyer's lambda calculus data format into our example files.
 * 
 * @author Percy Liang
 * @author Ziang Xie
 */

public class LambdaCalculusConverter implements Runnable
{
	public static class Options
	{
		@Option(gloss = "Input path (lambda calculus)")
		public String inPath = "overnight/geo/geosents280-typed.ccg.test.new";
		@Option(gloss = "Specification of translations")
		public String specPath = "overnight/geo/geo.spec";
		@Option(gloss = "Specification of variable names")
		public String varPath = "overnight/geo/geo.vars";
		@Option(gloss = "Specification of primitive types")
		public String primPath = "overnight/geo/geo.primitives";
		@Option(gloss = "Specification of formula replacements")
		public String replacePath = "overnight/geo/geo.replace";
		@Option(gloss = "Specification of manual conversions")
		public String manualConversionsPath = "overnight/geo/geo.manual_conversions";
		@Option(gloss = "Output path (examples)")
		public String outPath = "overnight/geo/geo.out.json";
		@Option(gloss = "Specific example to parse and run")
		public int runInd = -1;
		@Option(gloss = "Output path for lexicon grammar")
		public String lexiconPath = "overnight/geo/geo.out.grammar";
		@Option(gloss = "Verbose output (for debugging)")
		public boolean verbose = false; // TODO Currently unused
	}

	public static Options opts = new Options();

	// Mapping between predicates
	Map<String, Formula> predicatesMap = new HashMap<>();
	// Mapping from variables in the input (e.g. "$1") to our variables (e.g. "x")
	Map<String, Formula> varMap = new HashMap<>();
	// Mapping from types (e.g. "i" in "population:i") to semparse primitive types
	Map<String, String> primitiveMap = new HashMap<>();
	// Hardcoded replacements for mis-specified formulas in the input
	Map<String, String> replaceMap = new HashMap<>();
	// Hardcoded conversions for where the converter fails
	Map<String, String> manualConversionsMap = new HashMap<>();

	// Colorize output
	Colorizer color = new Colorizer();

	// Examples to be converted
	List<Example> examples = new ArrayList<>();
	// Indices of examples that were executed without error
	List<Integer> validExampleIds = new ArrayList<>();
	List<Integer> failedExampleIds = new ArrayList<>();
	// Use this to later sort the valid examples
	List<Integer> validExampleLengths = new ArrayList<>();

	@Override
	public void run()
	{
		readPrereqs();

		convertExamples();
		executeExamples();

		if (opts.runInd < 0)
		{
			writeExamples();
			printSummary();
		}
	}

	public void readPrereqs()
	{
		LogInfo.begin_track("Reading prereq");
		readSpec();
		readPrimitives();
		readVars();
		readStringMap(opts.replacePath, replaceMap);
		readStringMap(opts.manualConversionsPath, manualConversionsMap);
		LogInfo.end_track();
	}

	void readSpec()
	{
		for (final String line : IOUtils.readLinesHard(opts.specPath))
		{
			if (line.startsWith("#"))
				continue;
			if (line.equals(""))
				continue;
			final String[] tokens = line.split(" ", 2);
			predicatesMap.put(tokens[0], Formula.fromString(tokens[1]));
		}
	}

	void readPrimitives()
	{
		for (final String line : IOUtils.readLinesHard(opts.primPath))
		{
			if (line.startsWith("#"))
				continue;
			if (line.equals(""))
				continue;
			final String[] tokens = line.split(" ", 2);
			primitiveMap.put(tokens[0], tokens[1]);
		}
	}

	void readVars()
	{
		for (final String line : IOUtils.readLinesHard(opts.varPath))
		{
			if (line.startsWith("#"))
				continue;
			if (line.equals(""))
				continue;
			final String[] tokens = line.split(" ", 2);
			varMap.put(tokens[0], Formula.fromString(tokens[1]));
		}
	}

	void readStringMap(final String path, final Map<String, String> map)
	{
		boolean readOriginal = false;
		String original = "";
		for (final String line : IOUtils.readLinesHard(path))
		{
			if (line.startsWith("#"))
				continue;
			if (line.equals(""))
				continue;
			if (!readOriginal)
			{
				readOriginal = true;
				original = line;
			}
			else
			{
				readOriginal = false;
				map.put(original, line);
			}
		}
	}

	Formula toPredicate(String func)
	{
		boolean reverse = false;
		while (func.startsWith("!"))
		{
			LogInfo.log(func);
			reverse = !reverse;
			func = func.substring(1);
		}
		if (!predicatesMap.containsKey(func))
			func = removeGeoType(func);
		if (func.startsWith("$"))
			return Formula.fromString(String.format("(var %s)", toVariable(func).toString()));
		if (!predicatesMap.containsKey(func))
			throw new RuntimeException("Unknown predicate: " + func);
		final Formula form = predicatesMap.get(func);
		if (reverse)
			return new ReverseFormula(form);
		else
			return form;
	}

	Formula toLambdaVar(final String var)
	{
		return toVariable(var);
	}

	boolean isVar(final String var)
	{
		return var != null && var.startsWith("$");
	}

	Formula toVariable(String var)
	{
		var = removeGeoType(var);
		if (!varMap.containsKey(var))
			throw new RuntimeException("Unknown variable: " + var);
		return varMap.get(var);
	}

	// FIXME Not general to converters
	String removeGeoType(final String pred)
	{
		return pred.split(":", 2)[0];
	}

	String getGeoType(final String pred)
	{
		final String[] parts = pred.split(":", 2);
		if (parts.length > 0)
			return parts[1];
		return "";
	}

	Formula toJoin(final String func, final Formula arg)
	{
		return new JoinFormula(toPredicate(func), arg);
	}

	Formula toLambda(final String var, final Formula body)
	{
		return new LambdaFormula(toLambdaVar(var).toString(), body);
	}

	Formula toMark(final String var, final Formula body)
	{
		return new MarkFormula(toPredicate(var).toString(), body);
	}

	Formula toAndFormula(final List<LispTree> clauses, final boolean[] hit, final String headVar, final List<String> existsVars)
	{
		// (and (river:t $1) (loc:t $1 $0))
		Formula formula = null;
		for (int i = 0; i < clauses.size(); i++)
		{
			if (hit[i])
			{
				LogInfo.log("hit " + i);
				continue;
			}
			// if (!hasHeadVar(clauses.get(i), headVar)) continue;
			hit[i] = true;
			final LispTree tree = clauses.get(i);
			final Formula newFormula = toFormula(tree, headVar, existsVars);
			if (formula == null)
				formula = newFormula;
			else
				formula = new MergeFormula(MergeFormula.Mode.and, formula, newFormula);
		}
		return formula;
	}

	Formula toOrFormula(final List<LispTree> clauses, final boolean[] hit, final String headVar, final List<String> existsVars)
	{
		// (or (town:t $1) (city:t $1))
		LogInfo.log("# or clauses: " + clauses.size());
		Formula formula = null;
		for (int i = 0; i < clauses.size(); i++)
		{
			if (hit[i])
			{
				LogInfo.log("hit " + i);
				continue;
			}
			// if (!hasHeadVar(clauses.get(i), headVar)) continue;
			hit[i] = true;
			final LispTree tree = clauses.get(i);
			final Formula newFormula = toFormula(tree, headVar, existsVars);
			if (formula == null)
				formula = newFormula;
			else
				formula = new MergeFormula(MergeFormula.Mode.or, formula, newFormula);
		}
		return formula;
	}

	boolean validVar(final String var, final String headVar, final List<String> existsVars)
	{
		if (var == null)
			return false;
		if (!var.startsWith("$"))
			return true;

		boolean valid = false;
		if (var.equals(headVar))
			valid = true;
		if (existsVars != null && existsVars.contains(var))
			valid = true;
		return valid;
	}

	Formula toPrimitive(final String s)
	{
		final String[] parts = s.split(":", 2);
		if (parts.length < 2)
			return null;
		final String type = parts[1];
		final String primitive = primitiveMap.get(type);
		if (primitive == null)
			return null;
		// if (primitive.equals("string"))
		// parts[0] = capitalizeWords(parts[0]);
		// return Formula.fromString(String.format("(%s %s)", primitive, parts[0]));
		if (primitive.equals("string"))
			return Formula.fromString(String.format("(!fb:type.object.name fb:en.%s)", parts[0]));

		return Formula.fromString(String.format("(%s %s)", primitive, parts[0]));
	}

	Formula handleExists(final LispTree tree, final String headVar, final List<String> existsVars)
	{
		// (exists $1 (and (river:t $1) (loc:t $1 $0)))
		final String eVar = tree.child(1).value;
		existsVars.add(eVar);
		// FIXME Currently assuming format where exists contains and statement with
		// n clauses where first n - 1 clauses describe exists var and last clause
		// relates exists var to the head var
		final List<LispTree> andTreeList = tree.child(2).children;
		// FIXME Don't assume and tree
		LispTree newAndTree = LispTree.proto.newList();

		int predTreeInd = -1;
		int numPredTrees = 0;
		for (int k = 0; k < andTreeList.size(); k++)
		{
			final String childStr = andTreeList.get(k).toString();
			if (childStr.contains(headVar))
			{
				predTreeInd = k;
				numPredTrees++;
			}
		}

		final String func = tree.child(2).child(0).value;
		if (numPredTrees == 0 || func.equals("exists"))
			return toLambda(eVar, toFormula(tree.child(2), eVar, existsVars));

		newAndTree.children = new ArrayList<>(andTreeList.subList(0, andTreeList.size()));
		final LispTree predTree = newAndTree.children.remove(predTreeInd);
		if (newAndTree.children.size() == 2)
			newAndTree = newAndTree.child(1);

		final LispTree newPredTree = LispTree.proto.parseFromString(predTree.toString().replace(eVar, newAndTree.toString()));

		final Formula form = toFormula(newPredTree, eVar, existsVars);
		existsVars.remove(eVar);
		return form;
	}

	// hit: ignore these clauses in (and ...) constructions
	public Formula toFormula(final LispTree tree, String headVar, final List<String> existsVars)
	{

		if (tree.isLeaf())
		{
			final Formula s = toPrimitive(tree.toString());
			if (s != null)
				return s;
			return toPredicate(tree.toString());
		}

		String func = tree.child(0).value;

		if (func.equals("lambda"))
			// (lambda $0 e (and (river:t $0) (loc:t $0 arkansas:s)))
			return toFormula(tree.child(3), tree.child(1).value, existsVars);
		else
			if (func.equals("count"))
			{
				// (count $0 (and (river:t $0) (loc:t $0 washington:s)))
				final String countVar = tree.child(1).value;
				if (headVar == null)
					headVar = countVar;
				if (!countVar.equals(headVar) && !existsVars.contains(countVar))
					return toLambda(countVar, new AggregateFormula(AggregateFormula.Mode.count, toFormula(tree.child(2), countVar, existsVars)));
				else
					return new AggregateFormula(AggregateFormula.Mode.count, toFormula(tree.child(2), countVar, existsVars));
			}
			else
				if (func.equals("sum"))
				{
					// (sum $0 (and (state:t $0) (next_to:t $0 texas:s)) (population:i $0))
					final String numFunc = tree.child(3).child(0).value;
					return new AggregateFormula(AggregateFormula.Mode.sum, toJoin(numFunc, toFormula(tree.child(2), tree.child(1).value, existsVars)));
				}
				else
					if (func.equals("argmax") || func.equals("argmin"))
					{
						// (argmax $0 (state:t $0) (density:i $0))
						// (argmin $1 (river:t $1) (len:i $1))
						SuperlativeFormula.Mode mode = SuperlativeFormula.Mode.argmax;
						if (func.equals("argmin"))
							mode = SuperlativeFormula.Mode.argmin;
						final String superVar = tree.child(1).value;
						final Formula headFormula = toFormula(tree.child(2), superVar, existsVars);
						Formula degreeFormula = toFormula(tree.child(3), superVar, existsVars);
						final NumberValue one = new NumberValue(1.0);
						final Formula rankFormula = new ValueFormula(one);
						final Formula countFormula = new ValueFormula(one);
						// FIXME Hack to handle "most"/"least" expressions
						if (tree.child(3).child(0).value.equals("count"))
							degreeFormula = new ReverseFormula(degreeFormula);
						// FIXME Currently assumes 1 1
						return new SuperlativeFormula(mode, rankFormula, countFormula, headFormula, degreeFormula);
					}
					else
						if (func.equals("exists"))
						{
							if (headVar == null)
								headVar = tree.child(1).value;
							return handleExists(tree, headVar, existsVars);
						}
						else
							if (func.equals("not"))
								return new NotFormula(toFormula(tree.child(1), headVar, existsVars));
							else
								if (func.equals("and"))
									return toAndFormula(tree.children.subList(1, tree.children.size()), new boolean[tree.children.size() - 1], headVar, existsVars);
								else
									if (func.equals("or"))
										return toOrFormula(tree.children.subList(1, tree.children.size()), new boolean[tree.children.size() - 1], headVar, existsVars);
									else
										// Find head var
										if (tree.children.size() == 2)
										{ // Unary
											if (isVar(tree.child(1).value))
											{
												// FIXME HACK
												if (!getGeoType(func).equals("t"))
													return toPredicate("!" + func);
												return toPredicate(func);
											}
											else
												return toJoin(func, toFormula(tree.child(1), headVar, existsVars));
										}
										else
											if (tree.children.size() == 3)
											{ // Binary
												// FIXME Move elsewhere, there's both a binary and unary "capital:t"
												if (func.equals("capital:t"))
													func = "is_capital:t";

												final Formula form1 = toFormula(tree.child(1), headVar, existsVars);
												final Formula form2 = toFormula(tree.child(2), headVar, existsVars);
												final String first = tree.child(1).value;
												final String second = tree.child(2).value;

												// FIXME So ugly
												final boolean secondIsHead = isVar(second) && !existsVars.contains(second);
												final boolean firstIsHead = isVar(first) && (!existsVars.contains(first) || !secondIsHead);
												if (firstIsHead)
													return toJoin(func, form2);
												else // secondIsHead
													return toJoin("!" + func, form1);
												// else
												// return toJoin(func, form1);
											}
											else
												throw new RuntimeException("Bad arity: " + tree);
	}

	void countTokens(final LispTree tree, final Map<String, Integer> counts)
	{
		if (tree.isLeaf())
			MapUtils.incr(counts, tree.value, 1);
		else
			for (final LispTree child : tree.children)
				countTokens(child, counts);
	}

	String preprocessPredicates(final String lisp, final String utterance)
	{
		String lispLine = lisp;
		// Replacements
		boolean replaced = false;
		if (replaceMap.containsKey(lispLine))
		{
			lispLine = replaceMap.get(lispLine);
			replaced = true;
		}

		// FIXME Assumes that only one "major" in the line
		// FIXME Specific to geoquery
		if (utterance.contains("major river"))
			lispLine = lispLine.replace("major", "major_river");
		else
			if (utterance.contains("major lake"))
				lispLine = lispLine.replace("major", "major_lake");
			else
				lispLine = lispLine.replace("major", "major_city");

		if (utterance.contains("river"))
			if (!replaced)
				lispLine = lispLine.replace("loc:t", "river_loc:t");

		lispLine = lispLine.replace("new_york:s", "new_york_state:s");

		return lispLine;
	}

	void convertExamples()
	{
		String line;
		final int runInd = opts.runInd;

		try
		{
			Example.Builder ex = null;
			try (final BufferedReader in = IOUtils.openIn(opts.inPath))
			{
				boolean gotUtterance = false;
				int newId = 0;
				String utterance = "";

				while ((line = in.readLine()) != null)
				{
					if (line.equals("") || line.startsWith("//"))
						continue;
					if (ex == null)
					{
						newId++;
						ex = new Example.Builder();
						ex.setId("" + newId);
						gotUtterance = false;
					}

					if (!gotUtterance)
					{
						ex.setUtterance(line);
						utterance = line;
						gotUtterance = true;
					}
					else
					{
						if (runInd > 0 && newId != runInd)
						{
							ex = null;
							continue;
						}

						final LispTree tree = LispTree.proto.parseFromString(preprocessPredicates(line, utterance));
						LogInfo.logs(color.colorize("IN [%d]: %s", "blue"), newId, utterance);
						LogInfo.logs(color.colorize("IN [%d]: %s", "purple"), newId, tree);
						final ArrayList<String> existsVars = new ArrayList<>();

						if (manualConversionsMap.containsKey(line))
						{
							LogInfo.logs("MANUAL CONVERSION=%s", line);
							ex.setTargetFormula(Formula.fromString(manualConversionsMap.get(line)));
						}
						else
						{
							LogInfo.log("AUTOMATIC CONVERSION");
							ex.setTargetFormula(toFormula(tree, null, existsVars));
						}
						LogInfo.logs(color.colorize("OUT [%d]: %s", "yellow"), newId, ex.createExample().targetFormula.toLispTree());
						examples.add(ex.createExample());
						ex = null;
					}
				}
			}
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	void executeExamples()
	{
		final int runInd = opts.runInd;
		// FIXME Should be passed in
		SparqlExecutor.opts.endpointUrl = "http://localhost:3094/sparql";
		SparqlExecutor.opts.cachePath = "SparqlExecutor.cache";
		SparqlExecutor.opts.lambdaAllowDiagonals = false; //jonathan
		final SparqlExecutor executor = new SparqlExecutor();

		int exInd = 1;
		if (runInd > 0)
			exInd = runInd;
		for (final Example ex : examples)
		{
			// Useful for just testing specific portion of examples
			if (runInd > 0 && exInd != runInd)
				break;

			LogInfo.logs(color.colorize("[%d] %s", "blue"), exInd, ex.utterance);
			LogInfo.logs(color.colorize("[%d] %s", "yellow"), exInd, ex.targetFormula.toString());
			try
			{
				final Executor.Response response = executor.execute(ex.targetFormula, null);
				LogInfo.logs("\t\t [%d] %s", exInd, response.value.toString());
				validExampleIds.add(exInd);
				validExampleLengths.add(ex.utterance.length());
			}
			catch (final RuntimeException e)
			{
				LogInfo.error(e);
				failedExampleIds.add(exInd);
			}
			exInd++;
		}
	}

	void writeExamples()
	{
		// Sort validExampleIds by the length of the utterance of the example
		//    Collections.sort(validExampleIds, new Comparator<Integer>() {
		//      public int compare(Integer left, Integer right) {
		//        return Integer.compare(validExampleLengths.get(validExampleIds.indexOf(left)),
		//                validExampleLengths.get(validExampleIds.indexOf(right)));
		//      }
		//    });

		try (final PrintWriter out = IOUtils.openOutHard(opts.outPath))
		{
			out.println("["); // Print out as a list
			final String indent = "  ";
			for (int k = 0; k < validExampleIds.size(); k++)
			{
				final int j = validExampleIds.get(k) - 1;
				final Example ex = examples.get(j);
				if (k < validExampleIds.size() - 1)
					out.println(indent + ex.toJson() + ",");
				else
					out.println(indent + ex.toJson());
			}
			out.println("]"); // Print out as a list
		}
	}

	void printSummary()
	{
		LogInfo.logs("%d input examples", examples.size());
		LogInfo.logs("%d successful executions", validExampleIds.size());
		LogInfo.logs("Failed executions (%d):", failedExampleIds.size());
		for (final int k : failedExampleIds)
		{
			final Example ex = examples.get(k - 1);
			LogInfo.log(ex.toJson());
		}
	}

	public static void main(final String[] args)
	{
		Execution.run(args, new LambdaCalculusConverter(), "lcc", LambdaCalculusConverter.opts);
	}
}
