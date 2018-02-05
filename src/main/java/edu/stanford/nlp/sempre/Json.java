package edu.stanford.nlp.sempre;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;

/**
 * Simple wrappers and sane defaults for Jackson.
 *
 * @author Roy Frostig
 */
public final class Json
{
	private Json()
	{
	}

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	static
	{
		OBJECT_MAPPER.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
	}

	public static ObjectMapper getMapper()
	{
		return OBJECT_MAPPER;
	}

	private static ObjectWriter getWriter(final Class<?> view)
	{
		if (view != null)
			return getMapper().writerWithView(view);
		else
			return getMapper().writer();
	}

	private static ObjectReader getReader(final Class<?> view)
	{
		if (view != null)
			return getMapper().readerWithView(view);
		else
			return getMapper().reader();
	}

	// TODO (rf):
	// - readValueHard from InputStream, Reader, JsonParser, and File
	//   (all forwards)

	public static <T> T readValueHard(final String json, final Class<T> klass)
	{
		return readValueHard(json, klass, Object.class);
	}

	public static <T> T readValueHard(final String json, final Class<T> klass, final Class<?> view)
	{
		try
		{
			return getReader(view).withType(klass).readValue(json);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static <T> T readValueHard(final String json, final TypeReference<T> typeRef)
	{
		return readValueHard(json, typeRef, Object.class);
	}

	public static <T> T readValueHard(final String json, final TypeReference<T> typeRef, final Class<?> view)
	{
		try
		{
			return getReader(view).withType(typeRef).readValue(json);
		}
		catch (final JsonMappingException e)
		{
			throw new RuntimeException(e);
		}
		catch (final JsonParseException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static <T> T readValueHard(final Reader r, final Class<T> klass)
	{
		return readValueHard(r, klass, Object.class);
	}

	public static <T> T readValueHard(final Reader r, final Class<T> klass, final Class<?> view)
	{
		try
		{
			return getReader(view).withType(klass).readValue(r);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static <T> T readValueHard(final Reader r, final TypeReference<T> typeRef)
	{
		return readValueHard(r, typeRef, Object.class);
	}

	public static <T> T readValueHard(final Reader r, final TypeReference<T> typeRef, final Class<?> view)
	{
		try
		{
			return getReader(view).withType(typeRef).readValue(r);
		}
		catch (final JsonMappingException e)
		{
			throw new RuntimeException(e);
		}
		catch (final JsonParseException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static Map<String, Object> readMapHard(final String json)
	{
		return readMapHard(json, Object.class);
	}

	public static Map<String, Object> readMapHard(final String json, final Class<?> view)
	{
		try
		{
			return getReader(view).withType(Map.class).readValue(json);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static String prettyWriteValueAsStringHard(final Object o)
	{
		try
		{
			return getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(o);
		}
		catch (final JsonProcessingException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static String writeValueAsStringHard(final Object o)
	{
		return writeValueAsStringHard(o, Object.class);
	}

	public static String writeValueAsStringHard(final Object o, final Class<?> view)
	{
		try
		{
			return getWriter(view).writeValueAsString(o);
		}
		catch (final JsonProcessingException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static byte[] writeValueAsBytesHard(final Object o)
	{
		return writeValueAsBytesHard(o, Object.class);
	}

	public static byte[] writeValueAsBytesHard(final Object o, final Class<?> view)
	{
		try
		{
			return getWriter(view).writeValueAsBytes(o);
		}
		catch (final JsonProcessingException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static void prettyWriteValueHard(final File f, final Object o)
	{
		try
		{
			getMapper().writerWithDefaultPrettyPrinter().writeValue(f, o);
		}
		catch (final JsonMappingException e)
		{
			e.printStackTrace();
		}
		catch (final JsonGenerationException e)
		{
			e.printStackTrace();
		}
		catch (final IOException e)
		{
			e.printStackTrace();
		}
	}

	public static void writeValueHard(final File f, final Object o)
	{
		writeValueHard(f, o, Object.class);
	}

	public static void writeValueHard(final File f, final Object o, final Class<?> view)
	{
		try
		{
			getWriter(view).writeValue(f, o);
		}
		catch (final JsonMappingException e)
		{
			e.printStackTrace();
		}
		catch (final JsonGenerationException e)
		{
			e.printStackTrace();
		}
		catch (final IOException e)
		{
			e.printStackTrace();
		}
	}

	public static void writeValueHard(final OutputStream out, final Object o)
	{
		writeValueHard(out, o, Object.class);
	}

	public static void writeValueHard(final OutputStream out, final Object o, final Class<?> view)
	{
		try
		{
			getWriter(view).writeValue(out, o);
		}
		catch (final JsonMappingException e)
		{
			throw new RuntimeException(e);
		}
		catch (final JsonGenerationException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static void writeValueHard(final JsonGenerator jg, final Object o)
	{
		writeValueHard(jg, o, Object.class);
	}

	public static void writeValueHard(final JsonGenerator jg, final Object o, final Class<?> view)
	{
		try
		{
			getWriter(view).writeValue(jg, o);
		}
		catch (final JsonMappingException e)
		{
			throw new RuntimeException(e);
		}
		catch (final JsonGenerationException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static void writeValueHard(final Writer w, final Object o)
	{
		writeValueHard(w, o, Object.class);
	}

	public static void writeValueHard(final Writer w, final Object o, final Class<?> view)
	{
		try
		{
			getWriter(view).writeValue(w, o);
		}
		catch (final JsonMappingException e)
		{
			throw new RuntimeException(e);
		}
		catch (final JsonGenerationException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}
}
