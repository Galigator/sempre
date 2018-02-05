package edu.stanford.nlp.sempre.interactive;

import com.google.common.collect.ImmutableList;
import edu.stanford.nlp.sempre.ActionFormula;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.IdentityFn;
import edu.stanford.nlp.sempre.Json;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.Parser;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SemanticFn;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Ref;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.collections.Lists;

/**
 * Utilities for interactive learning
 *
 * @author sidaw
 */
public final class InteractiveUtils
{
	public static class Options
	{
		@Option(gloss = "use the best formula when no match or not provided")
		public boolean useBestFormula = false;

		@Option(gloss = "path to the citations")
		public String citationPath;

		@Option(gloss = "verbose")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	private InteractiveUtils()
	{
	}

	// dont spam my log when reading things in the beginning...
	public static boolean fakeLog = false;

	public static Derivation stripDerivation(Derivation deriv)
	{
		while (deriv.rule.sem instanceof IdentityFn)
			deriv = deriv.child(0);
		return deriv;
	}

	public static Derivation stripBlock(Derivation deriv)
	{
		if (opts.verbose > 0)
			LogInfo.logs("StripBlock %s %s %s", deriv, deriv.rule, deriv.cat);
		while ((deriv.rule.sem instanceof BlockFn || deriv.rule.sem instanceof IdentityFn) && deriv.children.size() == 1)
			deriv = deriv.child(0);
		return deriv;
	}

	public static List<Derivation> derivsfromJson(final String jsonDef, final Parser parser, final Params params, final Ref<Master.Response> refResponse)
	{
		@SuppressWarnings("unchecked")
		final List<Object> body = Json.readValueHard(jsonDef, List.class);
		// string together the body definition
		final List<Derivation> allDerivs = new ArrayList<>();
		int numFailed = 0;
		for (final Object obj : body)
		{
			@SuppressWarnings("unchecked")
			final List<String> pair = (List<String>) obj;
			final String utt = pair.get(0);
			final String formula = pair.get(1);

			if (formula.equals("()"))
			{
				LogInfo.logs("Error: Got empty formula");
				continue;
			}

			final Example.Builder b = new Example.Builder();
			// b.setId("session:" + sessionId);
			b.setUtterance(utt);
			final Example ex = b.createExample();
			ex.preprocess();

			LogInfo.logs("Parsing body: %s", ex.utterance);
			((InteractiveBeamParser) parser).parseWithoutExecuting(params, ex, false);

			boolean found = false;
			final Formula targetFormula = Formulas.fromLispTree(LispTree.proto.parseFromString(formula));
			for (final Derivation d : ex.predDerivations)
				// LogInfo.logs("considering: %s", d.formula.toString());
				if (d.formula.equals(targetFormula))
				{
					found = true;
					allDerivs.add(stripDerivation(d));
					break;
				}
			if (!found && !formula.equals("?"))
			{
				LogInfo.errors("matching formula not found: %s :: %s", utt, formula);
				numFailed++;
			}
			// just making testing easier, use top derivation when we formula is not
			// given
			if (!found && ex.predDerivations.size() > 0 && (formula.equals("?") || formula == null || opts.useBestFormula))
				allDerivs.add(stripDerivation(ex.predDerivations.get(0)));
			else
				if (!found)
				{
					final Derivation res = new Derivation.Builder().formula(targetFormula)
							// setting start to -1 is important,
							// which grammarInducer interprets to mean we do not want partial
							// rules
							.withCallable(new SemanticFn.CallInfo("$Action", -1, -1, null, new ArrayList<>())).createDerivation();
					allDerivs.add(res);
				}
		}
		if (refResponse != null)
		{
			refResponse.value.stats.put("num_failed", numFailed);
			refResponse.value.stats.put("num_body", body.size());
		}
		// LogInfo.logs("returning deriv list %s, \n %s", allDerivs.toString(),
		// jsonDef);
		return allDerivs;
	}

	public static List<String> utterancefromJson(final String jsonDef, final boolean tokenize)
	{
		@SuppressWarnings("unchecked")
		final List<Object> body = Json.readValueHard(jsonDef, List.class);
		// string together the body definition
		final List<String> utts = new ArrayList<>();
		for (int i = 0; i < body.size(); i++)
		{
			final Object obj = body.get(i);
			@SuppressWarnings("unchecked")
			final List<String> pair = (List<String>) obj;
			final String utt = pair.get(0);

			final Example.Builder b = new Example.Builder();
			// b.setId("session:" + sessionId);
			b.setUtterance(utt);
			final Example ex = b.createExample();
			ex.preprocess();

			if (tokenize)
			{
				utts.addAll(ex.getTokens());
				if (i != body.size() - 1 && !utts.get(utts.size() - 1).equals(";"))
					utts.add(";");
			}
			else
				utts.add(String.join(" ", ex.getTokens()));

		}
		return utts;
	}

	public static synchronized void addRuleInteractive(final Rule rule, final Parser parser)
	{
		LogInfo.logs("addRuleInteractive: %s", rule);
		if (parser instanceof InteractiveBeamParser)
			parser.addRule(rule);
		else
			throw new RuntimeException("interactively adding rule not supported for paser " + parser.getClass().toString());
	}

	static Rule blockRule(final ActionFormula.Mode mode)
	{
		final BlockFn b = new BlockFn(mode);
		b.init(LispTree.proto.parseFromString("(BlockFn sequential)"));
		return new Rule("$Action", Lists.newArrayList("$Action", "$Action"), b);
	}

	public static Derivation combine(final List<Derivation> children)
	{
		final ActionFormula.Mode mode = ActionFormula.Mode.sequential;
		if (children.size() == 1)
			return children.get(0);
		final Formula f = new ActionFormula(mode, children.stream().map(d -> d.formula).collect(Collectors.toList()));
		final Derivation res = new Derivation.Builder().formula(f)
				// setting start to -1 is important,
				// which grammarInducer interprets to mean we do not want partial rules
				.withCallable(new SemanticFn.CallInfo("$Action", -1, -1, blockRule(mode), ImmutableList.copyOf(children))).createDerivation();
		return res;
	}

	public static String getParseStatus(final Example ex)
	{
		return GrammarInducer.getParseStatus(ex).toString();
	}

	public static void cite(final Derivation match, final Example ex)
	{
		final CitationTracker tracker = new CitationTracker(ex.id, ex);
		tracker.citeAll(match);
	}
}
