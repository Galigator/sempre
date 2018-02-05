package edu.stanford.nlp.sempre.cprune;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.Rule;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CustomGrammar extends Grammar
{
	public static class Options
	{
		@Option(gloss = "Whether to decompose the templates into multiple rules")
		public boolean enableTemplateDecomposition = true;
	}

	public static Options opts = new Options();

	public static final Set<String> baseCategories = new HashSet<>(Arrays.asList(Rule.tokenCat, Rule.phraseCat, Rule.lemmaTokenCat, Rule.lemmaPhraseCat, "$Unary", "$Binary", "$Entity", "$Property"));

	ArrayList<Rule> baseRules = new ArrayList<>();
	// symbolicFormulas => symbolicFormula ID
	Map<String, Integer> symbolicFormulas = new HashMap<>();
	// indexedSymbolicFormula => customRuleString
	Map<String, Set<String>> customRules = new HashMap<>();
	// customRuleString => Binarized rules
	Map<String, Set<Rule>> customBinarizedRules = new HashMap<>();

	public void init(final Grammar initGrammar)
	{
		baseRules = new ArrayList<>();
		for (final Rule rule : initGrammar.getRules())
			if (baseCategories.contains(rule.lhs))
				baseRules.add(rule);
		freshCatIndex = initGrammar.getFreshCatIndex();
	}

	public List<Rule> getRules(final Collection<String> customRuleStrings)
	{
		final Set<Rule> ruleSet = new LinkedHashSet<>();
		ruleSet.addAll(baseRules);
		for (final String ruleString : customRuleStrings)
			ruleSet.addAll(customBinarizedRules.get(ruleString));
		return new ArrayList<>(ruleSet);
	}

	public Set<String> addCustomRule(final Derivation deriv, final Example ex)
	{
		final String indexedSymbolicFormula = getIndexedSymbolicFormula(deriv);
		if (customRules.containsKey(indexedSymbolicFormula))
			return customRules.get(indexedSymbolicFormula);

		final CPruneDerivInfo derivInfo = aggregateSymbols(deriv);
		final Set<String> crossReferences = new HashSet<>();
		for (final Symbol symbol : derivInfo.treeSymbols.values())
			if (symbol.frequency > 1)
				crossReferences.add(symbol.formula);
		computeCustomRules(deriv, crossReferences);
		customRules.put(indexedSymbolicFormula, new HashSet<>(derivInfo.customRuleStrings));

		LogInfo.begin_track("Add custom rules for formula: " + indexedSymbolicFormula);
		for (final String customRuleString : derivInfo.customRuleStrings)
		{
			if (customBinarizedRules.containsKey(customRuleString))
			{
				LogInfo.log("Custom rule exists: " + customRuleString);
				continue;
			}

			rules = new ArrayList<>();
			final LispTree tree = LispTree.proto.parseFromString(customRuleString);
			interpretRule(tree);
			customBinarizedRules.put(customRuleString, new HashSet<>(rules));

			// Debug
			LogInfo.begin_track("Add custom rule: " + customRuleString);
			for (final Rule rule : rules)
				LogInfo.log(rule.toString());
			LogInfo.end_track();
		}
		LogInfo.end_track();

		// Debug
		System.out.println("consistent_lf\t" + ex.id + "\t" + deriv.formula.toString());

		return customRules.get(indexedSymbolicFormula);
	}

	public static String getIndexedSymbolicFormula(final Derivation deriv)
	{
		return getIndexedSymbolicFormula(deriv, deriv.formula.toString());
	}

	/**
	 * Replace symbols (e.g., fb:row.row.name) with placeholders (e.g., Binary#1).
	 */
	public static String getIndexedSymbolicFormula(final Derivation deriv, String formula)
	{
		final CPruneDerivInfo derivInfo = aggregateSymbols(deriv);
		int index = 1;
		final List<Symbol> symbolList = new ArrayList<>(derivInfo.treeSymbols.values());
		for (final Symbol symbol : symbolList)
			symbol.computeIndex(formula);
		Collections.sort(symbolList);
		for (final Symbol symbol : symbolList)
		{
			if (formula.equals(symbol.formula))
				formula = symbol.category + "#" + index;
			formula = safeReplace(formula, symbol.formula, symbol.category + "#" + index);
			index += 1;
		}
		return formula;
	}

	// ============================================================
	// Private methods
	// ============================================================

	private static String safeReplace(String formula, String target, final String replacement)
	{
		// (argmin 1 1 ...) and (argmax 1 1 ...) are troublesome
		final String before = formula, targetBefore = target;
		formula = formula.replace("(argmin (number 1) (number 1)", "(ARGMIN");
		formula = formula.replace("(argmax (number 1) (number 1)", "(ARGMAX");
		target = target.replace("(argmin (number 1) (number 1)", "(ARGMIN");
		target = target.replace("(argmax (number 1) (number 1)", "(ARGMAX");
		formula = formula.replace(target + ")", replacement + ")");
		formula = formula.replace(target + " ", replacement + " ");
		formula = formula.replace("(ARGMIN", "(argmin (number 1) (number 1)");
		formula = formula.replace("(ARGMAX", "(argmax (number 1) (number 1)");
		if (CollaborativePruner.opts.verbose >= 2)
			LogInfo.logs("REPLACE: [%s | %s] %s | %s", targetBefore, replacement, before, formula);
		return formula;
	}

	/**
	 * Cache the symbols in deriv.tempState[cprune].treeSymbols
	 */
	private static CPruneDerivInfo aggregateSymbols(final Derivation deriv)
	{
		final Map<String, Object> tempState = deriv.getTempState();
		if (tempState.containsKey("cprune"))
			return (CPruneDerivInfo) tempState.get("cprune");
		final CPruneDerivInfo derivInfo = new CPruneDerivInfo();
		tempState.put("cprune", derivInfo);

		final Map<String, Symbol> treeSymbols = new LinkedHashMap<>();
		derivInfo.treeSymbols = treeSymbols;
		if (baseCategories.contains(deriv.cat))
		{
			final String formula = deriv.formula.toString();
			treeSymbols.put(formula, new Symbol(deriv.cat, formula, 1));
		}
		else
			for (final Derivation child : deriv.children)
			{
				final CPruneDerivInfo childInfo = aggregateSymbols(child);
				for (final Symbol symbol : childInfo.treeSymbols.values())
					if (derivInfo.treeSymbols.containsKey(symbol.formula))
						treeSymbols.get(symbol.formula).frequency += symbol.frequency;
					else
						treeSymbols.put(symbol.formula, symbol);
			}
		return derivInfo;
	}

	private CPruneDerivInfo computeCustomRules(final Derivation deriv, final Set<String> crossReferences)
	{
		final CPruneDerivInfo derivInfo = (CPruneDerivInfo) deriv.getTempState().get("cprune");
		final Map<String, Symbol> ruleSymbols = new LinkedHashMap<>();
		derivInfo.ruleSymbols = ruleSymbols;
		derivInfo.customRuleStrings = new ArrayList<>();
		final String formula = deriv.formula.toString();

		if (baseCategories.contains(deriv.cat))
		{
			// Leaf node induces no custom rule
			derivInfo.containsCrossReference = crossReferences.contains(formula);
			// Propagate the symbol of this derivation to the parent
			ruleSymbols.putAll(derivInfo.treeSymbols);
		}
		else
		{
			derivInfo.containsCrossReference = false;
			for (final Derivation child : deriv.children)
			{
				final CPruneDerivInfo childInfo = computeCustomRules(child, crossReferences);
				derivInfo.containsCrossReference = derivInfo.containsCrossReference || childInfo.containsCrossReference;
			}

			for (final Derivation child : deriv.children)
			{
				final CPruneDerivInfo childInfo = (CPruneDerivInfo) child.getTempState().get("cprune");
				ruleSymbols.putAll(childInfo.ruleSymbols);
				derivInfo.customRuleStrings.addAll(childInfo.customRuleStrings);
			}

			if (opts.enableTemplateDecomposition == false || derivInfo.containsCrossReference)
			{
				// If this node contains a cross reference
				if (deriv.isRootCat())
					// If this is the root node, then generate a custom rule
					derivInfo.customRuleStrings.add(getCustomRuleString(deriv, derivInfo));
			}
			else
				if (!deriv.cat.startsWith("$Intermediate"))
				{
					// Generate a custom rule for this node
					derivInfo.customRuleStrings.add(getCustomRuleString(deriv, derivInfo));

					// Propagate this derivation as a category to the parent
					ruleSymbols.clear();
					ruleSymbols.put(formula, new Symbol(hash(deriv), deriv.formula.toString(), 1));
				}
		}
		return derivInfo;
	}

	private String getCustomRuleString(final Derivation deriv, final CPruneDerivInfo derivInfo)
	{
		String formula = deriv.formula.toString();
		final List<Symbol> rhsSymbols = new ArrayList<>(derivInfo.ruleSymbols.values());
		for (final Symbol symbol : rhsSymbols)
			symbol.computeIndex(formula);
		Collections.sort(rhsSymbols);

		String lhs = null;
		if (derivInfo.containsCrossReference)
			lhs = deriv.cat;
		else
			lhs = deriv.isRootCat() ? "$ROOT" : hash(deriv);

		final LinkedList<String> rhsList = new LinkedList<>();
		int index = 1;
		for (final Symbol symbol : rhsSymbols)
		{
			if (formula.equals(symbol.formula))
				formula = "(IdentityFn)";
			else
			{
				formula = safeReplace(formula, symbol.formula, "(var s" + index + ")");
				formula = "(lambda s" + index + " " + formula + ")";
			}
			rhsList.addFirst(symbol.category);
			index += 1;
		}
		String rhs = null;
		if (rhsList.size() > 0)
			rhs = "(" + String.join(" ", rhsList) + ")";
		else
		{
			rhs = "(nothing)";
			formula = "(ConstantFn " + formula + ")";
		}
		return "(rule " + lhs + " " + rhs + " " + formula + ")";
	}

	private String hash(final Derivation deriv)
	{
		if (baseCategories.contains(deriv.cat))
			return deriv.cat;

		final String formula = getSymbolicFormula(deriv);
		if (!symbolicFormulas.containsKey(formula))
		{
			symbolicFormulas.put(formula, symbolicFormulas.size() + 1);
			final String hashString = "$Formula" + symbolicFormulas.get(formula);
			LogInfo.log("Add symbolic formula: " + hashString + " = " + formula + "  (" + deriv.cat + ")");
		}
		return "$Formula" + symbolicFormulas.get(formula);
	}

	private static String getSymbolicFormula(final Derivation deriv)
	{
		final CPruneDerivInfo derivInfo = aggregateSymbols(deriv);
		String formula = deriv.formula.toString();
		for (final Symbol symbol : derivInfo.treeSymbols.values())
		{
			if (formula.equals(symbol.formula))
				formula = symbol.category;
			formula = safeReplace(formula, symbol.formula, symbol.category);
		}
		return formula;
	}

}
