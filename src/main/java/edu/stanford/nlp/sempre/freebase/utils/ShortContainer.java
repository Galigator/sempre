package edu.stanford.nlp.sempre.freebase.utils;

import java.io.Serializable;

public class ShortContainer implements Serializable
{

	/**
	 *
	 */
	private static final long serialVersionUID = -911790554283478225L;

	private short count;

	public ShortContainer(final short count)
	{
		this.count = count;
	}

	public void inc()
	{
		count++;
	}

	public void dec()
	{
		count--;
	}

	public void inc(final short n)
	{
		count += n;
	}

	public void dec(final short n)
	{
		count -= n;
	}

	public short value()
	{
		return count;
	}

	public String toString()
	{
		return new Short(count).toString();
	}
}
