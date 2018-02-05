package edu.stanford.nlp.sempre.interactive;

import com.google.common.base.Strings;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Json;
import edu.stanford.nlp.sempre.Rule;
import fig.basic.IOUtils;
import fig.basic.LogInfo;
import java.io.File;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks rule usage via a citation system. A rule is cited when a user makes use of that rule in a derivation.
 * 
 * @author sidaw
 */
public class CitationTracker
{
	public static final String IDPrefix = "id:";
	public static final String HeadPrefix = "head:";
	public static final String BodyPrefix = "body:";
	String uid = "undefined";
	Example ex;

	public CitationTracker(final String uid, final Example ex)
	{
		this.uid = uid;
		this.ex = ex;
	}

	public synchronized void citeRule(final Rule rule)
	{
		writeLog(rule);
		writeSummary(rule);
	}

	// The summary is ONE SINGLE line of json, has cite, self, and head
	private synchronized void writeSummary(final Rule rule)
	{
		final String author = getAuthor(rule);
		final String summaryPath = Paths.get(InteractiveUtils.opts.citationPath, encode(author), encode(getHead(rule)) + ".json").toString();
		final File file = new File(summaryPath);
		file.getParentFile().mkdirs();

		Map<String, Object> summary;
		try
		{
			final String line = IOUtils.readLineEasy(summaryPath);
			if (line == null)
				summary = defaultMap(rule);
			else
				summary = Json.readMapHard(line);

			final boolean selfcite = author.equals(uid);
			if (!selfcite)
			{
				summary.put("cite", (Integer) summary.get("cite") + 1);
				rule.source.cite++;
			}
			else
			{
				summary.put("self", (Integer) summary.get("self") + 1);
				rule.source.self++;
			}

		}
		catch (final Exception e)
		{
			summary = defaultMap(rule);
			e.printStackTrace();
		}
		final String jsonStr = Json.writeValueAsStringHard(summary);
		final PrintWriter out = IOUtils.openOutHard(file);
		out.println(jsonStr);
		out.close();
	}

	private Map<String, Object> defaultMap(final Rule rule)
	{
		final Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("cite", 0);
		summary.put("self", 0);
		summary.put("private", true);
		summary.put("head", getHead(rule));
		summary.put("body", getBody(rule));
		return summary;
	}

	private synchronized void writeLog(final Rule rule)
	{
		final String head = getHead(rule);
		final String author = getAuthor(rule);
		final String logPath = Paths.get(InteractiveUtils.opts.citationPath, encode(author), encode(head) + ".json.log").toString();
		final File file = new File(logPath);
		file.getParentFile().mkdirs();

		final Map<String, Object> jsonMap = new LinkedHashMap<>();
		jsonMap.put("user", uid);
		// jsonMap.put("body", decode(getBody(rule)));
		jsonMap.put("time", LocalDateTime.now().toString());
		jsonMap.put("tokens", ex.getTokens());
		// jsonMap.put("head", decode(headCode));
		jsonMap.put("author", author);

		final String jsonStr = Json.writeValueAsStringHard(jsonMap);
		final PrintWriter out = IOUtils.openOutAppendHard(file);
		out.println(jsonStr);
		out.close();
	}

	public void citeAll(final Derivation deriv)
	{
		if (deriv.rule != null && deriv.rule.isInduced())
		{
			LogInfo.logs("CitationTracker: user %s is citing rule: %s", uid, deriv.rule.toString());
			citeRule(deriv.rule);
		}

		if (deriv.children == null)
			return;
		for (final Derivation d : deriv.children)
			citeAll(d);
	}

	static String getAuthor(final Rule rule)
	{
		try
		{
			final String author = rule.source.uid;
			if (Strings.isNullOrEmpty(author))
				return "__noname__";
			else
				return author;
		}
		catch (final Exception e)
		{
			e.printStackTrace();
			return "__noname__";
		}
	}

	static String getHead(final Rule rule)
	{
		return rule.source.head;
	}

	static String getBody(final Rule rule)
	{
		return String.join(". ", rule.source.body);
	}

	public static String encode(final String utt)
	{
		try
		{
			return URLEncoder.encode(utt, "UTF-8");
		}
		catch (final UnsupportedEncodingException e)
		{
			e.printStackTrace();
		}
		return Base64.getUrlEncoder().encodeToString(utt.getBytes());
		// return Base64.getUrlEncoder().encodeToString(utt.getBytes());
	}

	public static String decode(final String code)
	{
		try
		{
			return URLDecoder.decode(code, "UTF-8");
		}
		catch (final UnsupportedEncodingException e)
		{
			e.printStackTrace();
		}
		return Base64.getUrlDecoder().decode(code).toString();
	}

}
