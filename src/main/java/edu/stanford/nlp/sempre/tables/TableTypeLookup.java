package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.TypeLookup;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;

/**
 * Look up types for entities and properties in TableKnowledgeGraph. (Delegate all decisions to TableTypeSystem.)
 *
 * @author ppasupat
 */
public class TableTypeLookup implements TypeLookup
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	@Override
	public SemType getEntityType(final String entity)
	{
		if (opts.verbose >= 1)
			LogInfo.logs("TableTypeLookup.getEntityType %s", entity);
		final SemType type = TableTypeSystem.getEntityTypeFromId(entity);
		if (type == null && opts.verbose >= 1)
			LogInfo.logs("TableTypeLookup.getEntityType FAIL %s", entity);
		return type;
	}

	@Override
	public SemType getPropertyType(final String property)
	{
		if (opts.verbose >= 1)
			LogInfo.logs("TableTypeLookup.getPropertyType %s", property);
		final SemType type = TableTypeSystem.getPropertyTypeFromId(property);
		if (type == null && opts.verbose >= 1)
			LogInfo.logs("TableTypeLookup.getPropertyType FAIL %s", property);
		return type;
	}

	// Test cases
	public static void main(final String[] args)
	{
		final TypeLookup typeLookup = new TableTypeLookup();
		final String formulaString = "(lambda x ((reverse >) ((reverse fb:cell.cell.number) (var x))))";
		//"(lambda x (< (< ((reverse <) ((reverse fb:row.row.next) (var x))))))";
		final Formula formula = Formulas.fromLispTree(LispTree.proto.parseFromString(formulaString));
		LogInfo.logs("%s", formulaString);
		LogInfo.logs("%s", TypeInference.inferType(formula, typeLookup));
	}

}
