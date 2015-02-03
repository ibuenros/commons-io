/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.io.input;

import static org.apache.commons.io.IOUtils.EOF;

import java.io.*;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * Simple implementation of the unix "tail -f" functionality.
 * 
 * <h2>1. Create a TailerListener implementation</h2>
 * <p>
 * First you need to create a {@link TailerListener} implementation
 * ({@link TailerListenerAdapter} is provided for convenience so that you don't have to
 * implement every method).
 * </p>
 *
 * <p>For example:</p>
 * <pre>
 *  public class MyTailerListener extends TailerListenerAdapter {
 *      public void handle(String line) {
 *          System.out.println(line);
 *      }
 *  }</pre>
 *
 * <h2>2. Using a Tailer</h2>
 *
 * <p>
 * You can create and use a Tailer in one of three ways:
 * </p>
 * <ul>
 *   <li>Using one of the static helper methods:
 *     <ul>
 *       <li>{@link Tailer#create(File, TailerListener)}</li>
 *       <li>{@link Tailer#create(File, TailerListener, long)}</li>
 *       <li>{@link Tailer#create(File, TailerListener, long, boolean)}</li>
 *     </ul>
 *   </li>
 *   <li>Using an {@link java.util.concurrent.Executor}</li>
 *   <li>Using an {@link Thread}</li>
 * </ul>
 *
 * <p>
 * An example of each of these is shown below.
 * </p>
 *
 * <h3>2.1 Using the static helper method</h3>
 *
 * <pre>
 *      TailerListener listener = new MyTailerListener();
 *      Tailer tailer = Tailer.create(file, listener, delay);</pre>
 *
 * <h3>2.2 Using an Executor</h3>
 *
 * <pre>
 *      TailerListener listener = new MyTailerListener();
 *      Tailer tailer = new Tailer(file, listener, delay);
 *
 *      // stupid executor impl. for demo purposes
 *      Executor executor = new Executor() {
 *          public void execute(Runnable command) {
 *              command.run();
 *           }
 *      };
 *
 *      executor.execute(tailer);
 * </pre>
 *
 *
 * <h3>2.3 Using a Thread</h3>
 * <pre>
 *      TailerListener listener = new MyTailerListener();
 *      Tailer tailer = new Tailer(file, listener, delay);
 *      Thread thread = new Thread(tailer);
 *      thread.setDaemon(true); // optional
 *      thread.start();</pre>
 *
 * <h2>3. Stopping a Tailer</h2>
 * <p>Remember to stop the tailer when you have done with it:</p>
 * <pre>
 *      tailer.stop();
 * </pre>
 *
 * <h2>4. Interrupting a Tailer</h2>
 * <p>You can interrupt the thread a tailer is running on by calling {@link Thread#interrupt()}.</p>
 * <pre>
 *      thread.interrupt();
 * </pre>
 * <p>If you interrupt a tailer, the tailer listener is called with the {@link InterruptedException}.</p>
 *
 * <p>The file is read using the default charset; this can be overriden if necessary</p>
 * @see TailerListener
 * @see TailerListenerAdapter
 * @version $Id$
 * @since 2.0
 * @since 2.5 Updated behavior and documentation for {@link Thread#interrupt()}
 */
public class Tailer implements Runnable {

    private static final int DEFAULT_DELAY_MILLIS = 1000;

    private static final String RAF_MODE = "r";

    private static final int DEFAULT_BUFSIZE = 4096;

    // The default charset used for reading files
    private static final Charset DEFAULT_CHARSET = Charset.defaultCharset();

    /**
     * Buffer on top of RandomAccessFile.
     */
    private final byte inbuf[];

    /**
     * The file which will be tailed.
     */
    private final File file;

    /**
     * The character set that will be used to read the file.
     */
    private final Charset cset;

    /**
     * The amount of time to wait for the file to be updated.
     */
    private final long delayMillis;

    /**
     * Whether to tail from the end or start of file
     */
    private final boolean end;

    /**
     * The listener to notify of events when tailing.
     */
    private final TailerListener listener;

    /**
     * Whether to close and reopen the file whilst waiting for more input.
     */
    private final boolean reOpen;

    /**
     * Whether to keep reading from old file after it has been rotated.
     */
    private final boolean followReader;

    /**
     * Maximum number of iterations before closing reader after file has been rotated.
     */
    private final long maxIterationsBeforeClosing;

    /**
     * The tailer will run as long as this value is true.
     */
    private volatile boolean run = true;

    /**
     * Whether the file has been rotated.
     */
    private volatile boolean rotated = false;

    /**
     * Counts the number of iterations without modification after the file has been rotated.
     */
    private volatile int noModifyCounter = 0;

    /**
     * Creates a Tailer for the given file, starting from the beginning, with the default delay of 1.0s.
     * @param file The file to follow.
     * @param listener the TailerListener to use.
     */
    public Tailer(final File file, final TailerListener listener) {
        this(file, listener, DEFAULT_DELAY_MILLIS);
    }

    /**
     * Creates a Tailer for the given file, starting from the beginning.
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis) {
        this(file, listener, delayMillis, false);
    }

    /**
     * Creates a Tailer for the given file, with a delay other than the default 1.0s.
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis, final boolean end) {
        this(file, listener, delayMillis, end, DEFAULT_BUFSIZE);
    }

    /**
     * Creates a Tailer for the given file, with a delay other than the default 1.0s.
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param reOpen if true, close and reopen the file between reading chunks
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis, final boolean end, final boolean reOpen) {
        this(file, listener, delayMillis, end, reOpen, DEFAULT_BUFSIZE);
    }

    /**
     * Creates a Tailer for the given file, with a delay other than the default 1.0s.
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param readerPersistence the behavior of the file reader {@link org.apache.commons.io.input.TailerReaderPersistenceOptions}
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis, final boolean end, final TailerReaderPersistenceOptions readerPersistence ) {
        this(file, listener, delayMillis, end, readerPersistence, DEFAULT_BUFSIZE);
    }

    /**
     * Creates a Tailer for the given file, with a specified buffer size.
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param bufSize Buffer size
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis, final boolean end, final int bufSize) {
        this(file, listener, delayMillis, end, false, bufSize);
    }

    /**
     * Creates a Tailer for the given file, with a specified buffer size.
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param reOpen if true, close and reopen the file between reading chunks
     * @param bufSize Buffer size
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis, final boolean end, final boolean reOpen, final int bufSize) {
        this(file, DEFAULT_CHARSET, listener, delayMillis, end, reOpen ? TailerReaderPersistenceOptions.NO_FOLLOW_OPEN_ONCE : TailerReaderPersistenceOptions.NO_FOLLOW_REOPEN, bufSize, 0);
    }

    /**
     * Creates a Tailer for the given file, with a specified buffer size.
     * @param file The file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param readerPersistence the behavior of the file reader {@link org.apache.commons.io.input.TailerReaderPersistenceOptions}
     * @param bufSize Buffer size
     */
    public Tailer(final File file, final TailerListener listener, final long delayMillis, final boolean end, final TailerReaderPersistenceOptions readerPersistence, final int bufSize) {
        this(file, DEFAULT_CHARSET, listener, delayMillis, end, readerPersistence, bufSize, 10 * delayMillis);
    }

    /**
     * Creates a Tailer for the given file, with a specified buffer size.
     * @param file The file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param reOpen if true, close and reopen the file between reading chunks
     * @param bufSize Buffer size
     */
    public Tailer(final File file, final Charset cset, final TailerListener listener, final long delayMillis,
                  final boolean end, final boolean reOpen, final int bufSize) {
        this(file, cset, listener, delayMillis, end, reOpen ? TailerReaderPersistenceOptions.NO_FOLLOW_OPEN_ONCE : TailerReaderPersistenceOptions.NO_FOLLOW_REOPEN, bufSize, 0);
    }

    /**
     * Creates a Tailer for the given file, with a specified buffer size.
     * @param file the file to follow.
     * @param cset the Charset to be used for reading the file
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param readerPersistence the behavior of the file reader {@link org.apache.commons.io.input.TailerReaderPersistenceOptions}
     * @param bufSize Buffer size
     * @param timeoutAfterRotation if readerPersistence is set to FOLLOW_OPEN_ONCE, tailer will automatically close after
     *                             this many milliseconds without change. Set to negative number to never close automatically.
     */
    public Tailer(final File file, final Charset cset, final TailerListener listener, final long delayMillis,
                  final boolean end, final TailerReaderPersistenceOptions readerPersistence, final int bufSize, final long timeoutAfterRotation) {
        this.file = file;
        this.delayMillis = delayMillis;
        this.end = end;

        this.inbuf = new byte[bufSize];

        // Save and prepare the listener
        this.listener = listener;
        listener.init(this);

        this.maxIterationsBeforeClosing = timeoutAfterRotation / delayMillis;
        switch (readerPersistence) {
            case NO_FOLLOW_OPEN_ONCE:
                this.reOpen = false;
                this.followReader = false;
                break;
            case NO_FOLLOW_REOPEN:
                this.followReader = false;
                this.reOpen = true;
                break;
            case FOLLOW_OPEN_ONCE:
                this.reOpen = false;
                this.followReader = true;
                break;
            default:
                this.reOpen = false;
                this.followReader = false;
        }
        if (this.reOpen && this.followReader) {
            listener.handle(new Exception("Cannot followReader and reOpen in the same Tailer."));
        }
        this.cset = cset; 
    }

    /**
     * Creates and starts a Tailer for the given file.
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param bufSize buffer size.
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener, final long delayMillis, final boolean end, final int bufSize) {
        return create(file, listener, delayMillis, end, false, bufSize);
    }

    /**
     * Creates and starts a Tailer for the given file.
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param reOpen whether to close/reopen the file between chunks
     * @param bufSize buffer size.
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener, final long delayMillis, final boolean end, final boolean reOpen,
            final int bufSize) {
        return create(file, DEFAULT_CHARSET, listener, delayMillis, end, reOpen, bufSize);
    }

    /**
     * Creates and starts a Tailer for the given file.
     *
     * @param file the file to follow.
     * @param charset the character set to use for reading the file
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param reOpen whether to close/reopen the file between chunks
     * @param bufSize buffer size.
     * @return The new tailer
     */
    public static Tailer create(final File file, final Charset charset, final TailerListener listener, final long delayMillis, final boolean end, final boolean reOpen
            ,final int bufSize) {
        return create(file, charset, listener, delayMillis, end,
                reOpen ? TailerReaderPersistenceOptions.NO_FOLLOW_OPEN_ONCE : TailerReaderPersistenceOptions.NO_FOLLOW_REOPEN, bufSize, 0);
    }

    /**
     * Creates and starts a Tailer for the given file.
     *
     * @param file the file to follow.
     * @param charset the character set to use for reading the file
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param readerPersistence the behavior of the file reader {@link org.apache.commons.io.input.TailerReaderPersistenceOptions}
     * @param bufSize buffer size.
     * @return The new tailer
     */
    public static Tailer create(final File file, final Charset charset, final TailerListener listener, final long delayMillis, final boolean end, final TailerReaderPersistenceOptions readerPersistence
            ,final int bufSize, long millisToFollowAfterRotation) {
        final Tailer tailer = new Tailer(file, charset, listener, delayMillis, end, readerPersistence, bufSize, millisToFollowAfterRotation);
        final Thread thread = new Thread(tailer);
        thread.setDaemon(true);
        thread.start();
        return tailer;
    }

    /**
     * Creates and starts a Tailer for the given file with default buffer size.
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener, final long delayMillis, final boolean end) {
        return create(file, listener, delayMillis, end, DEFAULT_BUFSIZE);
    }

    /**
     * Creates and starts a Tailer for the given file with default buffer size.
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param reOpen whether to close/reopen the file between chunks
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener, final long delayMillis, final boolean end, final boolean reOpen) {
        return create(file, listener, delayMillis, end, reOpen, DEFAULT_BUFSIZE);
    }

    /**
     * Creates and starts a Tailer for the given file with default buffer size.
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @param end Set to true to tail from the end of the file, false to tail from the beginning of the file.
     * @param readerPersistence the behavior of the file reader {@link org.apache.commons.io.input.TailerReaderPersistenceOptions}
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener, final long delayMillis, final boolean end, final TailerReaderPersistenceOptions readerPersistence) {
        return create(file, DEFAULT_CHARSET, listener, delayMillis, end, readerPersistence, DEFAULT_BUFSIZE, 10 * delayMillis);
    }

    /**
     * Creates and starts a Tailer for the given file, starting at the beginning of the file
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @param delayMillis the delay between checks of the file for new content in milliseconds.
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener, final long delayMillis) {
        return create(file, listener, delayMillis, false);
    }

    /**
     * Creates and starts a Tailer for the given file, starting at the beginning of the file
     * with the default delay of 1.0s
     *
     * @param file the file to follow.
     * @param listener the TailerListener to use.
     * @return The new tailer
     */
    public static Tailer create(final File file, final TailerListener listener) {
        return create(file, listener, DEFAULT_DELAY_MILLIS, false);
    }

    /**
     * Return the file.
     *
     * @return the file
     */
    public File getFile() {
        return file;
    }

    /**
     * Gets whether to keep on running.
     *
     * @return whether to keep on running.
     * @since 2.5
     */
    protected boolean getRun() {
        return run;
    }

    /**
     * Return the delay in milliseconds.
     *
     * @return the delay in milliseconds.
     */
    public long getDelay() {
        return delayMillis;
    }

    /**
     * Follows changes in the file, calling the TailerListener's handle method for each new line.
     */
    public void run() {
        RandomAccessFile reader = null;
        try {
            long last = 0; // The last time the file was checked for changes
            long position = 0; // position within the file
            // Open the file
            while (getRun() && reader == null) {
                try {
                    reader = new RandomAccessFile(file, RAF_MODE);
                } catch (final FileNotFoundException e) {
                    listener.fileNotFound();
                }
                if (reader == null) {
                    Thread.sleep(delayMillis);
                } else {
                    // The current position in the file
                    position = end ? file.length() : 0;
                    last = file.lastModified();
                    reader.seek(position);
                }
            }
            while (getRun()) {
                final boolean newer = FileUtils.isFileNewer(file, last); // IO-279, must be done first
                // Check the file length to see if it was rotated
                final long length = file.length();
                if (rotated && followReader) {
                    // File has been rotated, but Tailer should follow the file
                    // Keep scanning until the file has not been modified for the threshold number of iterations
                    long oldPosition = position;
                    position = readLines(reader);
                    // Check if file was modified, if it wasn't update noModifyCounter
                    if (oldPosition < position) {
                        noModifyCounter = 0;
                    } else {
                        noModifyCounter += 1;
                    }
                    if(noModifyCounter >= maxIterationsBeforeClosing && maxIterationsBeforeClosing > 0) {
                        // If the file has not been modified for a few iterations, stop
                        stop();
                        IOUtils.closeQuietly(reader);
                    }
                } else if (length < position) {
                    // File was rotated
                    listener.fileRotated();
                    rotated = true;
                    if (followReader) {
                        // If we should follow the file
                        continue;
                    }
                    // Reopen the reader after rotation
                    try {
                        // Ensure that the old file is closed iff we re-open it successfully
                        final RandomAccessFile save = reader;
                        reader = new RandomAccessFile(file, RAF_MODE);
                        // At this point, we're sure that the old file is rotated
                        // Finish scanning the old file and then we'll start with the new one
                        try {
                            readLines(save);
                        }  catch (IOException ioe) {
                            listener.handle(ioe);
                        }
                        position = 0;
                        // close old file explicitly rather than relying on GC picking up previous RAF
                        IOUtils.closeQuietly(save);
                    } catch (final FileNotFoundException e) {
                        // in this case we continue to use the previous reader and position values
                        listener.fileNotFound();
                    }
                    continue;
                } else {
                    // File was not rotated
                    // See if the file needs to be read again
                    if (length > position) {
                        // The file has more content than it did last time
                        position = readLines(reader);
                        last = file.lastModified();
                    } else if (newer) {
                        /*
                         * This can happen if the file is truncated or overwritten with the exact same length of
                         * information. In cases like this, the file position needs to be reset
                         */
                        position = 0;
                        reader.seek(position); // cannot be null here

                        // Now we can read new lines
                        position = readLines(reader);
                        last = file.lastModified();
                    }
                }
                if (reOpen) {
                    IOUtils.closeQuietly(reader);
                }
                Thread.sleep(delayMillis);
                if (getRun() && reOpen) {
                    reader = new RandomAccessFile(file, RAF_MODE);
                    reader.seek(position);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(e);
        } catch (final Exception e) {
            stop(e);
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    private void stop(final Exception e) {
        listener.handle(e);
        stop();
    }

    /**
     * Allows the tailer to complete its current loop and return.
     */
    public void stop() {
        this.run = false;
    }

    /**
     * Read new lines.
     *
     * @param reader The file to read
     * @return The new position after the lines have been read
     * @throws java.io.IOException if an I/O error occurs.
     */
    private long readLines(final RandomAccessFile reader) throws IOException {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(64);
        long pos = reader.getFilePointer();
        long rePos = pos; // position to re-read
        int num;
        boolean seenCR = false;
        while (getRun() && ((num = reader.read(inbuf)) != EOF)) {
            for (int i = 0; i < num; i++) {
                final byte ch = inbuf[i];
                switch (ch) {
                case '\n':
                    seenCR = false; // swallow CR before LF
                    listener.handle(new String(lineBuf.toByteArray(), cset));
                    lineBuf.reset();
                    rePos = pos + i + 1;
                    break;
                case '\r':
                    if (seenCR) {
                        lineBuf.write('\r');
                    }
                    seenCR = true;
                    break;
                default:
                    if (seenCR) {
                        seenCR = false; // swallow final CR
                        listener.handle(new String(lineBuf.toByteArray(), cset));
                        lineBuf.reset();
                        rePos = pos + i + 1;
                    }
                    lineBuf.write(ch);
                }
            }
            pos = reader.getFilePointer();
        }
        IOUtils.closeQuietly(lineBuf); // not strictly necessary
        reader.seek(rePos); // Ensure we can re-read if necessary
        return rePos;
    }

}
