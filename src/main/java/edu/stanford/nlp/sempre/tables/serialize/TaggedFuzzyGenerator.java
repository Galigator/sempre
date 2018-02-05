package edu.stanford.nlp.sempre.tables.serialize;

import edu.stanford.nlp.sempre.Dataset;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SemanticFn.CallInfo;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Pair;
import fig.exec.Execution;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Generate TSV files containing information about fuzzy matched objects.
 *
 * @author ppasupat
 */
public class TaggedFuzzyGenerator extends TSVGenerator implements Runnable
{

	public static void main(final String[] args)
	{
		Execution.run(args, "TaggedFuzzyGeneratorMain", new TaggedFuzzyGenerator(), Master.getOptionsParser());
	}

	private final Grammar grammar = new Grammar();

	@Override
	public void run()
	{
		// Read grammar
		grammar.read(Grammar.opts.inPaths);
		// Read dataset
		LogInfo.begin_track("Dataset.read");
		for (final Pair<String, String> pathPair : Dataset.opts.inPaths)
		{
			final String group = pathPair.getFirst();
			final String path = pathPair.getSecond();
			// Open output file
			final String filename = Execution.getFile("fuzzy-" + group + ".tsv");
			out = IOUtils.openOutHard(filename);
			dump(FIELDS);
			// Read LispTrees
			LogInfo.begin_track("Reading %s", path);
			final int maxExamples = Dataset.getMaxExamplesForGroup(group);
			final Iterator<LispTree> trees = LispTree.proto.parseFromFile(path);
			// Go through the examples
			int n = 0;
			while (n < maxExamples)
			{
				// Format: (example (id ...) (utterance ...) (targetFormula ...) (targetValue ...))
				final LispTree tree = trees.next();
				if (tree == null)
					break;
				if (tree.children.size() < 2 || !"example".equals(tree.child(0).value))
				{
					if ("metadata".equals(tree.child(0).value))
						continue;
					throw new RuntimeException("Invalid example: " + tree);
				}
				final Example ex = Example.fromLispTree(tree, path + ":" + n);
				ex.preprocess();
				LogInfo.begin_track("Example %s (%d): %s => %s", ex.id, n, ex.getTokens(), ex.targetValue);
				n++;
				dumpExample(ex, tree);
				LogInfo.end_track();
			}
			out.close();
			LogInfo.logs("Finished dumping to %s", filename);
			LogInfo.end_track();
		}
		LogInfo.end_track();
	}

	private static final String[] FIELDS = new String[] { "id", "type", "start", "end", "phrase", "fragment" };

	@Override
	protected void dump(final String... stuff)
	{
		assert stuff.length == FIELDS.length;
		super.dump(stuff);
	}

	private void dumpExample(final Example ex, final LispTree tree)
	{
		final int n = ex.numTokens();
		for (int i = 0; i < n; i++)
		{
			final StringBuilder sb = new StringBuilder(ex.token(i));
			for (int j = i; j < n; j++)
			{
				final String term = sb.toString();
				Derivation deriv = new Derivation.Builder().cat(Rule.phraseCat).start(i).end(j).rule(Rule.nullRule).children(Derivation.emptyList).withStringFormulaFrom(term).canonicalUtterance(term).createDerivation();
				final List<Derivation> children = new ArrayList<>();
				children.add(deriv);
				// Get the derived derivations
				for (final Rule rule : grammar.getRules())
				{
					final CallInfo c = new CallInfo(rule.lhs, i, j + 1, rule, children);
					final Iterator<Derivation> itr = rule.sem.call(ex, c);
					while (itr.hasNext())
					{
						deriv = itr.next();
						LogInfo.logs("Found %s %s -> %s", rule.lhs, term, deriv.formula);
						dump(ex.id, rule.lhs.substring(1), "" + i, "" + (j + 1), term, deriv.formula.toString());
					}
				}
				if (j + 1 < n)
					sb.append(" ").append(ex.token(j + 1));
			}
		}
	}

}
