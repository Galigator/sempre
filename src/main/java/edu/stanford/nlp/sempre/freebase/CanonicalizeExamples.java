package edu.stanford.nlp.sempre.freebase;

import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.exec.Execution;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Replaces all names (e.g., fb:m.02mjmr) with canonical identifiers (e.g., fb:en.barack_obama) For creating the dataset.
 *
 * @author Percy Liang
 */
public class CanonicalizeExamples implements Runnable
{
	@Option(required = true, gloss = "File mapping ids to canonical ids")
	public String canonicalIdMapPath;
	@Option(required = true, gloss = "Input path to examples to canonicalize (output to same directory) with .canonicalized extension")
	public List<String> examplePaths;

	Map<String, String> canonicalIdMap;

	private String convert(String name)
	{
		boolean reverse = false;
		if (name.startsWith("!"))
		{
			name = name.substring(1);
			reverse = true;
		}
		name = MapUtils.get(canonicalIdMap, name, name);
		if (reverse)
			name = "!" + name;
		return name;
	}

	public Formula convert(final Formula formula)
	{
		return formula.map(formula1 ->
		{
			String name = Formulas.getNameId(formula1);
			if (name != null)
			{
				name = convert(name);
				return Formulas.newNameFormula(name);
			}
			return null;
		});
	}

	public void run()
	{
		canonicalIdMap = edu.stanford.nlp.sempre.freebase.Utils.readCanonicalIdMap(canonicalIdMapPath);

		for (final String inPath : examplePaths)
		{
			final String outPath = inPath + ".canonicalized";
			LogInfo.logs("Converting %s => %s", inPath, outPath);
			final Iterator<LispTree> it = LispTree.proto.parseFromFile(inPath);
			final PrintWriter out = IOUtils.openOutHard(outPath);
			while (it.hasNext())
			{
				final LispTree tree = it.next();
				if (!"example".equals(tree.child(0).value))
					throw new RuntimeException("Bad: " + tree);
				for (int i = 1; i < tree.children.size(); i++)
				{
					final LispTree subtree = tree.child(i);
					if ("targetFormula".equals(subtree.child(0).value))
						for (int j = 1; j < subtree.children.size(); j++)
						{
							Formula formula = Formulas.fromLispTree(subtree.child(j));
							formula = convert(formula);
							subtree.children.set(j, formula.toLispTree()); // Use converted formula
						}
				}
				out.println(tree.toStringWrap(100));
			}
			out.close();
		}
	}

	public static void main(final String[] args)
	{
		Execution.run(args, new CanonicalizeExamples());
	}
}
