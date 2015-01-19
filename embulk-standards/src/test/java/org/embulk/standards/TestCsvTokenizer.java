package org.embulk.standards;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import org.embulk.EmbulkTestRuntime;
import org.embulk.spi.Buffer;
import org.embulk.spi.FileInput;
import org.embulk.spi.ListFileInput;
import org.embulk.config.ConfigSource;
import org.embulk.type.Column;
import org.embulk.type.Schema;
import org.embulk.spi.Exec;
import org.embulk.spi.LineDecoder;

public class TestCsvTokenizer
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    protected ConfigSource config;
    protected CsvParserPlugin.PluginTask task;

    @Before
    public void setup() {
        config = Exec.newConfigSource()
            .set("newline", "LF")
            .set("columns", ImmutableList.of(
                        ImmutableMap.of(
                            "name", "date_code",
                            "type", "string"),
                        ImmutableMap.of(
                            "name", "foo",
                            "type", "string"))
                );
        reloadPluginTask();
    }

    private void reloadPluginTask()
    {
        task = config.loadConfig(CsvParserPlugin.PluginTask.class);
    }

    private static FileInput newFileInputFromLines(CsvParserPlugin.PluginTask task, String... lines)
    {
        List<Buffer> buffers = new ArrayList<>();
        for (String line : lines) {
            byte[] buffer = (line + task.getNewline().getString()).getBytes(task.getCharset());
            buffers.add(Buffer.wrap(buffer));
        }
        return new ListFileInput(ImmutableList.of(buffers));
    }

    private static FileInput newFileInputFromText(CsvParserPlugin.PluginTask task, String text)
    {
        return new ListFileInput(
                ImmutableList.of(ImmutableList.of(
                        Buffer.wrap(text.getBytes(task.getCharset())))));
    }

    private static List<List<String>> parse(CsvParserPlugin.PluginTask task, String... lines)
    {
        return parse(task, newFileInputFromLines(task, lines));
    }

    private static List<List<String>> parse(CsvParserPlugin.PluginTask task, FileInput input)
    {
        LineDecoder decoder = new LineDecoder(input, task);
        CsvTokenizer tokenizer = new CsvTokenizer(decoder, task);
        Schema schema = task.getSchemaConfig().toSchema();

        tokenizer.nextFile();

        List<List<String>> records = new ArrayList<>();
        while (tokenizer.nextRecord()) {
            List<String> record = new ArrayList<>();
            for (Column c : schema.getColumns()) {
                String v = tokenizer.nextColumn();
                if (!v.isEmpty()) {
                    record.add(v);
                } else {
                    record.add(tokenizer.wasQuotedColumn() ? "" : null);
                }
            }
            records.add(record);
        }
        return records;
    }

    private List<List<String>> expectedRecords(int columnCount, String... values)
    {
        List<List<String>> records = new ArrayList<>();
        List<String> columns = null;
        for (int i=0; i < values.length; i++) {
            if (i % columnCount == 0) {
                columns = new ArrayList<String>();
                records.add(columns);
            }
            columns.add(values[i]);
        }
        return records;
    }

    @Test
    public void testSimple() throws Exception
    {
        assertEquals(expectedRecords(2,
                    "aaa", "bbb",
                    "ccc", "ddd"),
                parse(task,
                    "aaa,bbb",
                    "ccc,ddd"));
    }

    @Test
    public void testSkipEmptyLine() throws Exception
    {
        assertEquals(expectedRecords(2,
                    "aaa", "bbb",
                    "ccc", "ddd"),
                parse(task,
                    "", "aaa,bbb", "", "",
                    "ccc,ddd", "", ""));
    }

    @Test
    public void parseEmptyColumnsToNull() throws Exception
    {
        assertEquals(expectedRecords(2,
                    null, null,
                    "", "",
                    "  ", "  "), // not trimmed
                parse(task,
                    ",",
                    "\"\",\"\"",
                    "  ,  "));
    }

    @Test
    public void parseEmptyColumnsToNullTrimmed() throws Exception
    {
        config.set("trim_if_not_quoted", true);
        reloadPluginTask();
        assertEquals(
                expectedRecords(2,
                    null, null,
                    "", "",
                    null, null),  // trimmed
                parse(task,
                    ",",
                    "\"\",\"\"",
                    "  ,  "));
    }

    @Test
    public void testMultilineQuotedValueWithEmptyLine() throws Exception
    {
        assertEquals(expectedRecords(2,
                    "a", "\nb\n\n",
                    "c", "d"),
                parse(task,
                    "",
                    "a,\"", "b", "", "\"",
                    "c,d"));
    }

    @Test
    public void testEndOfFileWithoutNewline() throws Exception
    {
        // In RFC 4180, the last record in the file may or may not have
        // an ending line break.
        assertEquals(expectedRecords(2,
                        "aaa", "bbb",
                        "ccc", "ddd"),
            parse(task, newFileInputFromText(task,
                    "aaa,bbb\nccc,ddd")));
    }

    @Test
    public void testChangeDelimiter() throws Exception
    {
        config.set("delimiter", JsonNodeFactory.instance.textNode("\t")); // TSV format
        reloadPluginTask();
        assertEquals(expectedRecords(2,
                        "aaa", "bbb",
                        "ccc", "ddd"),
            parse(task,
                    "aaa\tbbb",
                    "ccc\tddd"));
    }

    @Test
    public void testQuotedValues() throws Exception
    {
        assertEquals(expectedRecords(2,
                        "a\na\na", "b,bb",
                        "cc\"c", "\"ddd",
                        null, ""),
            parse(task, newFileInputFromText(task,
                "\n\"a\na\na\",\"b,bb\"\n\n\"cc\"\"c\",\"\"\"ddd\"\n,\"\"\n")));
    }

    @Test
    public void parseEscapedValues() throws Exception
    {
        assertEquals(expectedRecords(2,
                        "a\"aa", "b,bb\"",
                        "cc\"c", "\"ddd",
                        null, ""),
                parse(task, newFileInputFromText(task,
                    "\n\"a\\\"aa\",\"b,bb\\\"\"\n\n\"cc\"\"c\",\"\"\"ddd\"\n,\"\"\n")));
    }

    @Test
    public void trimNonQuotedValues() throws Exception
    {
        assertEquals(expectedRecords(2,
                    "  aaa  ", "  b cd ",
                    "  ccc","dd d \n "), // quoted values are not changed
                parse(task, newFileInputFromText(task,
                        "  aaa  ,  b cd \n\"  ccc\",\"dd d \n \"")));

        // trim_if_not_quoted is true
        config.set("trim_if_not_quoted", true);
        reloadPluginTask();
        assertEquals(expectedRecords(2,
                    "aaa", "b cd",
                    "  ccc","dd d \n "), // quoted values are not changed
                parse(task, newFileInputFromText(task,
                        "  aaa  ,  b cd \n\"  ccc\",\"dd d \n \"")));
    }

    @Test
    public void parseQuotedValueWithSpacesAndTrimmingOption() throws Exception
    {
        config.set("trim_if_not_quoted", true);
        reloadPluginTask();
        assertEquals(expectedRecords(2,
                        "heading1", "heading2",
                        "trailing1","trailing2",
                        "trailing\n3","trailing\n4"),
                parse(task,
                    "  \"heading1\",  \"heading2\"",
                    "\"trailing1\"  ,\"trailing2\"  ",
                    "\"trailing\n3\"  ,\"trailing\n4\"  "));
    }

    /*
    @Test(expected = CsvTokenizer.CsvValueValidateException.class)
    public void parseTooLargeSizedValues() throws Exception
    {
        config.set("max_quoted_column_size", 8L);
        reloadPluginTask();
        List<List<String>> parsed = doParse(task, bufferList("utf-8",
                "aaa,bbb", "\n", "\"cccccccc\",ddd", "\n"));

        assertEquals(Arrays.asList(
                        Arrays.asList("aaa", "bbb"),
                        Arrays.asList("ccc", "ddd")),
                parsed);
    }
    */

    /*
    @Test
    public void parseEscapedQuotedValues() throws Exception
    {
        List<List<String>> parsed = doParse(task, bufferList("utf-8",
                "\"aa,a\",\",aaa\",\"aaa,\"", "\n",
                "\"bb\"\"b\",\"\"\"bbb\",\"bbb\"\"\"", "\n",
                "\"cc\\\"c\",\"\\\"ccc\",\"ccc\\\"\"", "\n",
                "\"dd\nd\",\"\nddd\",\"ddd\n\"", "\n"));
        assertEquals(Arrays.asList(
                        Arrays.asList("aa,a", ",aaa", "aaa,"),
                        Arrays.asList("bb\"b", "\"bbb", "bbb\""),
                        Arrays.asList("cc\"c", "\"ccc", "ccc\""),
                        Arrays.asList("dd\nd", "\nddd", "ddd\n")),
                parsed);
    }
    */
}