package edu.stanford.nlp.sempre.freebase.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.freebase.FreebaseTypeLookup;
import org.testng.annotations.Test;

/**
 * Test type inference on Freebase schema.
 * 
 * @author Percy Liang
 */
public class FreebaseTypeInferenceTest
{
	// For testing
	private static Formula F(final String str)
	{
		return Formula.fromString(str);
	}

	private static SemType T(final String str)
	{
		return SemType.fromString(str);
	}

	private static SemType FT(final String str)
	{
		return TypeInference.inferType(F(str));
	}

	void check(final String fstr, final String tstr)
	{
		System.out.println("check " + fstr + " " + tstr);
		assertEquals(T(tstr).toString(), FT(fstr).toString());
	}

	@Test
	public void simpleSemType()
	{
		// TODO(pliang): replace with NullTypeLookup()?
		FreebaseTypeLookup.opts.entityTypesPath = null; // Disable entity lookup
		TypeInference.setTypeLookup(new FreebaseTypeLookup());

		check("(fb:location.location.area (>= (number 200)))", "fb:location.location");

		check("(number 3)", "fb:type.number");
		check("(string foo)", "fb:type.text");
		check("(date 1981 1 1)", "fb:type.datetime");
		check("fb:en.barack_obama", "fb:common.topic"); // Don't have getEntityTypes
		check("fb:people.person.place_of_birth", "(-> fb:location.location fb:people.person)");

		// Join
		check("(fb:type.object.type fb:location.location)", "fb:location.location");
		check("(fb:people.person.place_of_birth (fb:type.object.type fb:location.location))", "fb:people.person");
		check("(!fb:people.person.place_of_birth (fb:type.object.type fb:location.location))", "(union)");

		// Merge
		check("(and (fb:type.object.type fb:common.topic) (fb:people.person.place_of_birth fb:en.seattle))", "fb:people.person");
		check("(and (fb:type.object.type fb:location.location) (fb:people.person.place_of_birth fb:en.seattle))", "(union)");

		// Mark
		check("(mark x (fb:people.person.parents (var x)))", "fb:people.person");
		check("(mark x (fb:people.person.place_of_birth (var x)))", "(union)");

		// Lambda
		check("(lambda x (fb:people.person.place_of_birth (var x)))", "(-> fb:location.location fb:people.person)");
		check("(lambda x (!fb:people.person.place_of_birth (var x)))", "(-> fb:people.person fb:location.location)");
		check("(lambda x (fb:people.person.place_of_birth (var x)))", "(-> fb:location.location fb:people.person)");
		check("(lambda x (!fb:people.person.place_of_birth (var x)))", "(-> fb:people.person fb:location.location)");
		check("(lambda x (!fb:people.person.profession (fb:people.person.place_of_birth (var x))))", "(-> fb:location.location fb:people.profession)");
		check("(lambda b ((var b) (fb:type.object.type fb:people.person)))", "(-> (-> fb:people.person top) top)");
		// Note: and the other way doesn't work, since we don't propagate everything.
		check("(lambda b (and (fb:type.object.type fb:location.location) ((var b) (fb:type.object.type fb:people.person))))", "(-> (-> fb:people.person fb:location.location) fb:location.location)");
		check("(lambda x (lambda y ((var x) (var y))))", "(-> (-> top top) (-> top top))");
		check("(lambda x (lambda x (fb:people.person.place_of_birth (var x))))", "(-> top (-> fb:location.location fb:people.person))"); // No variable capture

		// Aggregation
		check("(lambda x (not (var x)))", "(-> fb:type.any fb:type.any)");
		check("(lambda x (count (var x)))", "(-> fb:type.any fb:type.number)");
		check("(lambda x (count (fb:people.person.place_of_birth (var x))))", "(-> fb:location.location fb:type.number)");

		// Arithmetic
		check("(+ (number 3) (number 4))", "fb:type.number");
		check("(+ (date 1981 1 1) (string 4))", "(union)");
		check("(- (date 1982 1 1) (date 1981 1 1))", "fb:type.datetime"); // Future: should be a different duration type

		// Reverse
		check("(reverse fb:people.person.place_of_birth)", "(-> fb:people.person fb:location.location)");

		// Superlative
		check("(argmax 1 1 (fb:type.object.type fb:people.person) fb:people.person.date_of_birth)", "fb:people.person");
		check("(argmax 1 1 (fb:type.object.type fb:common.topic) fb:people.person.date_of_birth)", "fb:people.person");
		check("(argmax 1 1 (fb:type.object.type fb:common.topic) (reverse (lambda x (number 3))))", "fb:common.topic");
		check("(lambda x (lambda y (argmax 1 1 (var x) (var y))))", "(-> fb:type.any (-> (-> (union fb:type.number fb:type.datetime) fb:type.any) fb:type.any))");

		// Call
		check("(call Math.cos (number 0))", "fb:type.float");
		check("(call Math.cos (string abc))", "(union)");
		check("(lambda x (lambda y (call .concat (var x) (var y))))", "(-> fb:type.text (-> fb:type.text fb:type.text))");
		check("(lambda x (call .length (var x)))", "(-> fb:type.text fb:type.int)");
	}

	public static void main(final String[] args)
	{
		new FreebaseTypeInferenceTest().simpleSemType();
	}
}
