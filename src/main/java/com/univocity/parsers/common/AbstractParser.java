/*******************************************************************************
 * Copyright 2014 uniVocity Software Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.univocity.parsers.common;

import com.univocity.parsers.common.input.*;
import com.univocity.parsers.common.input.EOFException;
import com.univocity.parsers.common.processor.*;
import com.univocity.parsers.common.record.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * The AbstractParser class provides a common ground for all parsers in uniVocity-parsers.
 * <p> It handles all settings defined by {@link CommonParserSettings}, and delegates the parsing algorithm implementation to its subclasses through the abstract method {@link AbstractParser#parseRecord()}
 * <p> The following (absolutely required) attributes are exposed to subclasses:
 * <ul>
 * <li><b>input (<i>{@link CharInputReader}</i>):</b> the character input provider that reads characters from a given input into an internal buffer</li>
 * <li><b>output (<i>{@link ParserOutput}</i>):</b> the output handler for every record parsed from the input. Implementors must use this object to handle the input (such as appending characters and notifying of values parsed)</li>
 * <li><b>ch (<i>char</i>):</b> the current character read from the input</li>
 * </ul>
 *
 * @param <T> The specific parser settings configuration class, which can potentially provide additional configuration options supported by the parser implementation.
 *
 * @author uniVocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 * @see com.univocity.parsers.csv.CsvParser
 * @see com.univocity.parsers.csv.CsvParserSettings
 * @see com.univocity.parsers.fixed.FixedWidthParser
 * @see com.univocity.parsers.fixed.FixedWidthParserSettings
 * @see com.univocity.parsers.common.input.CharInputReader
 * @see com.univocity.parsers.common.ParserOutput
 */
public abstract class AbstractParser<T extends CommonParserSettings<?>> {

	protected final T settings;
	protected final ParserOutput output;
	private final long recordsToRead;
	private final char comment;
	private final LineReader lineReader = new LineReader();
	protected ParsingContext context;
	protected RowProcessor processor;
	protected CharInputReader input;
	protected char ch;
	private final RowProcessorErrorHandler errorHandler;
	protected RecordFactory recordFactory;
	private final long rowsToSkip;
	protected final Map<Long, String> comments;
	protected String lastComment;
	private final boolean collectComments;
	private Record firstRecord;
	private final int errorContentLength;

	/**
	 * All parsers must support, at the very least, the settings provided by {@link CommonParserSettings}. The AbstractParser requires its configuration to be properly initialized.
	 *
	 * @param settings the parser configuration
	 */
	public AbstractParser(T settings) {
		settings.autoConfigure();
		this.settings = settings;
		this.errorContentLength = settings.getErrorContentLength();
		this.output = new ParserOutput(settings);
		this.processor = settings.getRowProcessor();
		this.recordsToRead = settings.getNumberOfRecordsToRead();
		this.comment = settings.getFormat().getComment();
		this.errorHandler = settings.getRowProcessorErrorHandler();
		this.rowsToSkip = settings.getNumberOfRowsToSkip();
		this.collectComments = settings.isCommentCollectionEnabled();
		this.comments = collectComments ? new TreeMap<Long, String>() : Collections.<Long, String>emptyMap();
	}

	protected void processComment() {
		if (collectComments) {
			long line = input.lineCount();
			String comment = input.readComment();
			if(comment != null){
				lastComment = comment;
				comments.put(line, lastComment);
			}
		} else {
			input.skipLines(1);
		}
	}

	/**
	 * Parses the entirety of a given input and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param reader The input to be parsed.
	 */
	public final void parse(Reader reader) {
		beginParsing(reader);
		try {
			while (!context.isStopped()) {
				ch = input.nextChar();
				if (inComment()) {
					processComment();
					continue;
				}
				parseRecord();

				String[] row = output.rowParsed();
				if (row != null) {
					if (recordsToRead >= 0 && context.currentRecord() >= recordsToRead) {
						context.stop();
						if (recordsToRead == 0) {
							stopParsing();
							return;
						}
					}
					rowProcessed(row);
				}
			}

			stopParsing();
		} catch (EOFException ex) {
			try {
				handleEOF();
			} finally {
				stopParsing();
			}
		} catch (Throwable ex) {
			try {
				ex = handleException(ex);
			} finally {
				stopParsing(ex);
			}
		}
	}

	/**
	 * Parser-specific implementation for reading a single record from the input.
	 * <p> The AbstractParser handles the initialization and processing of the input until it is ready to be parsed.
	 * <p> It then delegates the input to the parser-specific implementation defined by {@link #parseRecord()}. In general, an implementation of {@link AbstractParser#parseRecord()} will perform the following steps:
	 * <ul>
	 * <li>Test the character stored in <i>ch</i> and take some action on it (e.g. is <i>while (ch != '\n'){doSomething()}</i>)</li>
	 * <li>Request more characters by calling <i>ch = input.nextChar();</i> </li>
	 * <li>Append the desired characters to the output by executing, for example, <i>output.appender.append(ch)</i></li>
	 * <li>Notify a value of the record has been fully read by executing <i>output.valueParsed()</i>. This will clear the output appender ({@link CharAppender}) so the next call to output.appender.append(ch) will be store the character of the next parsed value</li>
	 * <li>Rinse and repeat until all values of the record are parsed</li>
	 * </ul>
	 * <p> Once the {@link #parseRecord()} returns, the AbstractParser takes over and handles the information (generally, reorganizing it and  passing it on to a {@link RowProcessor}).
	 * <p> After the record processing, the AbstractParser reads the next characters from the input, delegating control again to the parseRecord() implementation for processing of the next record.
	 * <p> This cycle repeats until the reading process is stopped by the user, the input is exhausted, or an error happens.
	 * <p> In case of errors, the unchecked exception {@link TextParsingException} will be thrown and all resources in use will be closed automatically. The exception should contain the cause and more information about where in the input the error happened.
	 *
	 * @see com.univocity.parsers.common.input.CharInputReader
	 * @see com.univocity.parsers.common.input.CharAppender
	 * @see com.univocity.parsers.common.ParserOutput
	 * @see com.univocity.parsers.common.TextParsingException
	 * @see com.univocity.parsers.common.processor.RowProcessor
	 */
	protected abstract void parseRecord();

	/**
	 * Allows the parser implementation to handle any value that was being consumed when the end of the input was reached
	 *
	 * @return a flag indicating whether the parser was processing a value when the end of the input was reached.
	 */
	protected boolean consumeValueOnEOF() {
		return false;
	}

	private String[] handleEOF() {
		String[] row = null;
		try {
			boolean consumeValueOnEOF = consumeValueOnEOF();
			if (output.column != 0 || consumeValueOnEOF) {
				if (output.appender.length() > 0 || consumeValueOnEOF) {
					output.valueParsed();
				} else {
					output.emptyParsed();
				}
				row = output.rowParsed();
			} else if (output.appender.length() > 0) {
				output.valueParsed();
				row = output.rowParsed();
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			throw handleException(e);
		}
		if (row != null) {
			rowProcessed(row);
		}
		return row;
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param reader The input to be parsed.
	 */
	public final void beginParsing(Reader reader) {
		output.reset();

		if (reader instanceof LineReader) {
			input = new DefaultCharInputReader(settings.getFormat().getLineSeparator(), settings.getFormat().getNormalizedNewline(), settings.getInputBufferSize());
		} else {
			input = settings.newCharInputReader();
		}
		input.enableNormalizeLineEndings(true);

		context = new DefaultParsingContext(this);
		((DefaultParsingContext) context).stopped = false;

		if (processor instanceof DefaultConversionProcessor) {
			DefaultConversionProcessor conversionProcessor = ((DefaultConversionProcessor) processor);
			conversionProcessor.errorHandler = errorHandler;
			conversionProcessor.context = context;
		}

		if (input instanceof AbstractCharInputReader) {
			((AbstractCharInputReader) input).addInputAnalysisProcess(getInputAnalysisProcess());
		}

		input.start(reader);
		input.skipLines(rowsToSkip);

		recordFactory = new RecordFactory(context);
		processor.processStarted(context);

		initialize();
	}

	protected void initialize() {

	}

	/**
	 * Allows the parser implementation to traverse the input buffer before the parsing process starts, in order to enable automatic configuration and discovery of data formats.
	 *
	 * @return a custom implementation of {@link InputAnalysisProcess}. By default, {@code null} is returned and no special input analysis will be performed.
	 */
	protected InputAnalysisProcess getInputAnalysisProcess() {
		return null;
	}

	private String getParsedContent(CharSequence tmp) {
		return "Parsed content: " + AbstractException.restrictContent(errorContentLength, tmp);
	}

	private TextParsingException handleException(Throwable ex) {
		if (ex instanceof DataProcessingException) {
			DataProcessingException error = (DataProcessingException) ex;
			error.restrictContent(errorContentLength);
			error.setContext(this.context);
			throw error;
		}

		String message = ex.getClass().getName() + " - " + ex.getMessage();
		char[] chars = output.appender.getChars();
		if (chars != null) {
			int length = output.appender.length();
			if (length > chars.length) {
				message = "Length of parsed input (" + length + ") exceeds the maximum number of characters defined in" +
						" your parser settings (" + settings.getMaxCharsPerColumn() + "). ";
				length = chars.length;
			}

			String tmp = new String(chars);
			if (tmp.contains("\n") || tmp.contains("\r")) {
				tmp = displayLineSeparators(tmp, true);
				String lineSeparator = displayLineSeparators(settings.getFormat().getLineSeparatorString(), false);
				message += "\nIdentified line separator characters in the parsed content. This may be the cause of the error. " +
						"The line separator in your parser settings is set to '" + lineSeparator + "'. " + getParsedContent(tmp);
			}

			int nullCharacterCount = 0;
			//ensuring the StringBuilder won't grow over Integer.MAX_VALUE to avoid OutOfMemoryError
			int maxLength = length > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE / 2 - 1 : length;
			StringBuilder s = new StringBuilder(maxLength);
			for (int i = 0; i < maxLength; i++) {
				if (chars[i] == '\0') {
					s.append('\\');
					s.append('0');
					nullCharacterCount++;
				} else {
					s.append(chars[i]);
				}
			}
			tmp = s.toString();

			if (nullCharacterCount > 0) {
				message += "\nIdentified " + nullCharacterCount + " null characters ('\0') on parsed content. This may " +
						"indicate the data is corrupt or its encoding is invalid. Parsed content:\n\t" + getParsedContent(tmp);
			}

		}

		if (ex instanceof ArrayIndexOutOfBoundsException) {
			try {
				int index = Integer.parseInt(ex.getMessage());
				if (index == settings.getMaxCharsPerColumn()) {
					message += "\nHint: Number of characters processed may have exceeded limit of " + index + " characters per column. Use settings.setMaxCharsPerColumn(int) to define the maximum number of characters a column can have";
				}
				if (index == settings.getMaxColumns()) {
					message += "\nHint: Number of columns processed may have exceeded limit of " + index + " columns. Use settings.setMaxColumns(int) to define the maximum number of columns your input can have";
				}
				message += "\nEnsure your configuration is correct, with delimiters, quotes and escape sequences that match the input format you are trying to parse";
			} catch (Throwable t) {
				//ignore;
			}
		}

		try {
			if (!message.isEmpty()) {
				message += "\n";
			}
			message += "Parser Configuration: " + settings.toString();
		} catch (Exception t) {
			//ignore
		}

		if (errorContentLength == 0) {
			output.appender.reset();
		}
		TextParsingException out = new TextParsingException(context, message, ex);
		out.setErrorContentLength(errorContentLength);
		return out;
	}

	private static String displayLineSeparators(String str, boolean addNewLine) {
		if (addNewLine) {
			if (str.contains("\r\n")) {
				str = str.replaceAll("\\r\\n", "[\\\\r\\\\n]\r\n\t");
			} else if (str.contains("\n")) {
				str = str.replaceAll("\\n", "[\\\\n]\n\t");
			} else {
				str = str.replaceAll("\\r", "[\\\\r]\r\t");
			}
		} else {
			str = str.replaceAll("\\n", "\\\\n");
			str = str.replaceAll("\\r", "\\\\r");
		}
		return str;
	}

	/**
	 * In case of errors, stops parsing and closes all open resources. Avoids hiding the original exception in case another error occurs when stopping.
	 */
	private void stopParsing(Throwable error) {
		if (error != null) {
			try {
				stopParsing();
			} catch (Throwable ex) {
				// ignore and throw original error.
			}
			if (error instanceof DataProcessingException) {
				DataProcessingException ex = (DataProcessingException) error;
				ex.setContext(context);
				throw ex;
			} else if (error instanceof RuntimeException) {
				throw (RuntimeException) error;
			} else if (error instanceof Error) {
				throw (Error) error;
			} else {
				throw new IllegalStateException(error.getMessage(), error);
			}
		} else {
			stopParsing();
		}
	}

	/**
	 * Stops parsing and closes all open resources.
	 */
	public final void stopParsing() {
		try {
			context.stop();
		} finally {
			try {
				processor.processEnded(context);
			} finally {
				output.appender.reset();
				input.stop();
			}
		}
	}

	/**
	 * Parses all records from the input and returns them in a list.
	 *
	 * @param reader the input to be parsed
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<String[]> parseAll(Reader reader) {
		List<String[]> out = new ArrayList<String[]>(10000);
		beginParsing(reader);
		String[] row;
		while ((row = parseNext()) != null) {
			out.add(row);
		}
		return out;
	}

	protected boolean inComment(){
		return ch == comment;
	}


	/**
	 * Parses the next record from the input. Note that {@link AbstractParser#beginParsing(Reader)} must have been invoked once before calling this method.
	 * If the end of the input is reached, then this method will return null. Additionally, all resources will be closed automatically at the end of the input or if any error happens while parsing.
	 *
	 * @return The record parsed from the input or null if there's no more characters to read.
	 */
	public final String[] parseNext() {
		if (firstRecord != null) {
			String[] out = firstRecord.getValues();
			firstRecord = null;
			return out;
		}
		try {
			while (!context.isStopped()) {
				ch = input.nextChar();
				if (inComment()) {
					processComment();
					continue;
				}

				parseRecord();
				String[] row = output.rowParsed();
				if (row != null) {
					if (recordsToRead >= 0 && context.currentRecord() >= recordsToRead) {
						context.stop();
						if (recordsToRead == 0L) {
							stopParsing();
							return null;
						}
					}
					rowProcessed(row);
					return row;
				}
			}
			stopParsing();
			return null;
		} catch (EOFException ex) {
			String[] row = handleEOF();
			stopParsing();
			return row;
		} catch (NullPointerException ex) {
			if (context == null) {
				throw new IllegalStateException("Cannot parse without invoking method beginParsing(Reader) first");
			} else {
				if (input != null) {
					stopParsing();
				}
				throw new IllegalStateException("Error parsing next record.", ex);
			}
		} catch (Throwable ex) {
			try {
				ex = handleException(ex);
			} finally {
				stopParsing(ex);
			}
		}
		return null;
	}

	/**
	 * Reloads headers from settings.
	 */
	protected final void reloadHeaders() {
		this.output.initializeHeaders();
		this.recordFactory = new RecordFactory(context);
		if (context instanceof DefaultParsingContext) {
			((DefaultParsingContext) context).reset();
		}
	}

	/**
	 * Parses a single line from a String in the format supported by the parser implementation.
	 *
	 * @param line a line of text to be parsed
	 *
	 * @return the {@link Record} containing the values parsed from the input line
	 */
	public final Record parseRecord(String line) {
		String[] values = parseLine(line);
		if (values == null) {
			return null;
		}
		return recordFactory.newRecord(values);
	}


	/**
	 * Parses a single line from a String in the format supported by the parser implementation.
	 *
	 * @param line a line of text to be parsed
	 *
	 * @return the values parsed from the input line
	 */
	public final String[] parseLine(String line) {
		if (line == null || line.isEmpty()) {
			return null;
		}
		lineReader.setLine(line);
		if (context == null || context.isStopped()) {
			beginParsing(lineReader);
		} else {
			((DefaultCharInputReader) input).reloadBuffer();
		}
		try {
			while (!context.isStopped()) {
				ch = input.nextChar();
				if (inComment()) {
					processComment();
					return null;
				}
				parseRecord();
				String[] row = output.rowParsed();
				if (row != null) {
					rowProcessed(row);
					return row;
				}
			}
			return null;
		} catch (EOFException ex) {
			return handleEOF();
		} catch (NullPointerException ex) {
			if (input != null) {
				stopParsing(null);
			}
			throw new IllegalStateException("Error parsing next record.", ex);
		} catch (Throwable ex) {
			try {
				ex = handleException(ex);
			} finally {
				stopParsing(ex);
			}
		}
		return null;
	}

	private void rowProcessed(String[] row) {
		try {
			processor.rowProcessed(row, context);
		} catch (DataProcessingException ex) {
			ex.setContext(context);
			ex.setErrorContentLength(errorContentLength);
			if (ex.isFatal()) {
				throw ex;
			}
			errorHandler.handleError(ex, row, context);
		} catch (Throwable t) {
			DataProcessingException ex = new DataProcessingException("Unexpected error processing input row "
					+ AbstractException.restrictContent(errorContentLength, Arrays.toString(row))
					+ " using RowProcessor " + processor.getClass().getName() + '.'
					, AbstractException.restrictContent(errorContentLength, row)
					, t);
			ex.restrictContent(errorContentLength);
			throw ex;
		}
	}

	/**
	 * Parses the entirety of a given file and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param file The file to be parsed.
	 */
	public final void parse(File file) {
		parse(ArgumentUtils.newReader(file));
	}

	/**
	 * Parses the entirety of a given file and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param file     The file to be parsed.
	 * @param encoding the encoding of the file
	 */
	public final void parse(File file, String encoding) {
		parse(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Parses the entirety of a given file and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param file     The file to be parsed.
	 * @param encoding the encoding of the file
	 */
	public final void parse(File file, Charset encoding) {
		parse(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Parses the entirety of a given input and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param input The input to be parsed. The input stream will be closed automatically.
	 */
	public final void parse(InputStream input) {
		parse(ArgumentUtils.newReader(input));
	}

	/**
	 * Parses the entirety of a given input and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param input    The input to be parsed. The input stream will be closed automatically.
	 * @param encoding the encoding of the input stream
	 */
	public final void parse(InputStream input, String encoding) {
		parse(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Parses the entirety of a given input and delegates each parsed row to an instance of {@link RowProcessor}, defined by {@link CommonParserSettings#getRowProcessor()}.
	 *
	 * @param input    The input to be parsed. The input stream will be closed automatically.
	 * @param encoding the encoding of the input stream
	 */
	public final void parse(InputStream input, Charset encoding) {
		parse(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param file The file to be parsed.
	 */
	public final void beginParsing(File file) {
		beginParsing(ArgumentUtils.newReader(file));
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param file     The file to be parsed.
	 * @param encoding the encoding of the file
	 */
	public final void beginParsing(File file, String encoding) {
		beginParsing(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param file     The file to be parsed.
	 * @param encoding the encoding of the file
	 */
	public final void beginParsing(File file, Charset encoding) {
		beginParsing(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param input The input to be parsed. The input stream will be closed automatically in case of errors.
	 */
	public final void beginParsing(InputStream input) {
		beginParsing(ArgumentUtils.newReader(input));
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param input    The input to be parsed. The input stream will be closed automatically in case of errors.
	 * @param encoding the encoding of the input stream
	 */
	public final void beginParsing(InputStream input, String encoding) {
		beginParsing(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Starts an iterator-style parsing cycle. If a {@link RowProcessor} is provided in the configuration, it will be used to perform additional processing.
	 * The parsed records must be read one by one with the invocation of {@link AbstractParser#parseNext()}.
	 * The user may invoke @link {@link AbstractParser#stopParsing()} to stop reading from the input.
	 *
	 * @param input    The input to be parsed. The input stream will be closed automatically in case of errors.
	 * @param encoding the encoding of the input stream
	 */
	public final void beginParsing(InputStream input, Charset encoding) {
		beginParsing(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Parses all records from a file and returns them in a list.
	 *
	 * @param file the input file to be parsed
	 *
	 * @return the list of all records parsed from the file.
	 */
	public final List<String[]> parseAll(File file) {
		return parseAll(ArgumentUtils.newReader(file));
	}

	/**
	 * Parses all records from a file and returns them in a list.
	 *
	 * @param file     the input file to be parsed
	 * @param encoding the encoding of the file
	 *
	 * @return the list of all records parsed from the file.
	 */
	public final List<String[]> parseAll(File file, String encoding) {
		return parseAll(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Parses all records from a file and returns them in a list.
	 *
	 * @param file     the input file to be parsed
	 * @param encoding the encoding of the file
	 *
	 * @return the list of all records parsed from the file.
	 */
	public final List<String[]> parseAll(File file, Charset encoding) {
		return parseAll(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Parses all records from an input stream and returns them in a list.
	 *
	 * @param input the input stream to be parsed. The input stream will be closed automatically
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<String[]> parseAll(InputStream input) {
		return parseAll(ArgumentUtils.newReader(input));
	}

	/**
	 * Parses all records from an input stream and returns them in a list.
	 *
	 * @param input    the input stream to be parsed. The input stream will be closed automatically
	 * @param encoding the encoding of the input stream
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<String[]> parseAll(InputStream input, String encoding) {
		return parseAll(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Parses all records from an input stream and returns them in a list.
	 *
	 * @param input    the input stream to be parsed. The input stream will be closed automatically
	 * @param encoding the encoding of the input stream
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<String[]> parseAll(InputStream input, Charset encoding) {
		return parseAll(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Parses all records from a file and returns them in a list.
	 *
	 * @param file the input file to be parsed
	 *
	 * @return the list of all records parsed from the file.
	 */
	public final List<Record> parseAllRecords(File file) {
		return parseAllRecords(ArgumentUtils.newReader(file));
	}

	/**
	 * Parses all records from a file and returns them in a list.
	 *
	 * @param file     the input file to be parsed
	 * @param encoding the encoding of the file
	 *
	 * @return the list of all records parsed from the file.
	 */
	public final List<Record> parseAllRecords(File file, String encoding) {
		return parseAllRecords(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Parses all records from a file and returns them in a list.
	 *
	 * @param file     the input file to be parsed
	 * @param encoding the encoding of the file
	 *
	 * @return the list of all records parsed from the file.
	 */
	public final List<Record> parseAllRecords(File file, Charset encoding) {
		return parseAllRecords(ArgumentUtils.newReader(file, encoding));
	}

	/**
	 * Parses all records from an input stream and returns them in a list.
	 *
	 * @param input the input stream to be parsed. The input stream will be closed automatically
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<Record> parseAllRecords(InputStream input) {
		return parseAllRecords(ArgumentUtils.newReader(input));
	}

	/**
	 * Parses all records from an input stream and returns them in a list.
	 *
	 * @param input    the input stream to be parsed. The input stream will be closed automatically
	 * @param encoding the encoding of the input stream
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<Record> parseAllRecords(InputStream input, String encoding) {
		return parseAllRecords(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Parses all records from an input stream and returns them in a list.
	 *
	 * @param input    the input stream to be parsed. The input stream will be closed automatically
	 * @param encoding the encoding of the input stream
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<Record> parseAllRecords(InputStream input, Charset encoding) {
		return parseAllRecords(ArgumentUtils.newReader(input, encoding));
	}

	/**
	 * Parses all records from the input and returns them in a list.
	 *
	 * @param reader the input to be parsed
	 *
	 * @return the list of all records parsed from the input.
	 */
	public final List<Record> parseAllRecords(Reader reader) {
		List<Record> out = new ArrayList<Record>(10000);
		beginParsing(reader);
		Record record;
		while ((record = parseNextRecord()) != null) {
			out.add(record);
		}
		return out;
	}

	/**
	 * Parses the next record from the input. Note that {@link AbstractParser#beginParsing(Reader)} must have been invoked once before calling this method.
	 * If the end of the input is reached, then this method will return null. Additionally, all resources will be closed automatically at the end of the input or if any error happens while parsing.
	 *
	 * @return The record parsed from the input or null if there's no more characters to read.
	 */
	public final Record parseNextRecord() {
		if (firstRecord != null) {
			Record out = firstRecord;
			firstRecord = null;
			return out;
		}
		String[] row = this.parseNext();
		if (row != null) {
			return recordFactory.newRecord(row);
		}
		return null;
	}

	/**
	 * Returns all comments collected by the parser so far.
	 * An empty map will be returned if {@link CommonParserSettings#isCommentCollectionEnabled()} evaluates to {@code false}.
	 *
	 * @return a map containing the line numbers and comments found in each.
	 */
	final Map<Long, String> getComments() {
		return comments;
	}

	/**
	 * Returns the last comment found in the input.
	 * {@code null} will be returned if {@link CommonParserSettings#isCommentCollectionEnabled()} is evaluated to {@code false}.
	 *
	 * @return the last comment found in the input.
	 */
	final String getLastComment() {
		return lastComment;
	}

	/**
	 * Returns the headers <b>parsed</b> from the input, if and only if {@link CommonParserSettings#headerExtractionEnabled} is {@code true}.
	 * The result of this method won't return the list of headers manually set by the user in {@link CommonParserSettings#getHeaders()}.
	 *
	 * @return the headers parsed from the input, when {@link CommonParserSettings#headerExtractionEnabled} is {@code true}.
	 */
	final String[] getParsedHeaders() {
		return output.parsedHeaders;
	}

	/**
	 * Returns the current parsing context with information about the status of the parser at any given time.
	 *
	 * @return the parsing context
	 */
	public final ParsingContext getContext() {
		return context;
	}

	/**
	 * Returns the metadata associated with {@link Record}s parsed from the input using {@link AbstractParser#parseAllRecords(File)} or {@link AbstractParser#parseNextRecord()}.
	 *
	 * @return the metadata of {@link Record}s generated with the current input.
	 */
	public final RecordMetaData getRecordMetadata() {
		if (recordFactory != null) {
			if (context.currentRecord() == 0L && context.headers() == null && settings.isHeaderExtractionEnabled() && !context.isStopped()) {
				firstRecord = parseNextRecord();
			}
			return recordFactory.getRecordMetaData();
		}
		throw new IllegalStateException("No record metadata available. The parsing process has not been started yet.");
	}
}
