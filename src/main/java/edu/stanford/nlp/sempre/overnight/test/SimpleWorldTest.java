package edu.stanford.nlp.sempre.overnight.test;

import static org.testng.AssertJUnit.assertEquals;

import org.testng.annotations.Test;

/**
 * Test simple world from overnight framework. Creates a small database using SimpleWorld, and does sanity checks on the induced knowledge graph
 * 
 * @author Yushi Wang
 */
public class SimpleWorldTest
{
	@Test
	public void externalWorldTest()
	{
		edu.stanford.nlp.sempre.overnight.SimpleWorld.opts.domain = "external";
		edu.stanford.nlp.sempre.overnight.SimpleWorld.opts.dbPath = "lib/data/overnight/test/unittest.db";
		edu.stanford.nlp.sempre.overnight.SimpleWorld.opts.verbose = 1;
		edu.stanford.nlp.sempre.overnight.SimpleWorld.recreateWorld();

		assertEquals(edu.stanford.nlp.sempre.overnight.SimpleWorld.sizeofDB(), 12);
	}

	public static void main(final String[] args)
	{
		new SimpleWorldTest().externalWorldTest();
	}
}
