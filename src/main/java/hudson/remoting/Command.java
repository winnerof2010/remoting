/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.ExecutionException;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * One-way command to be sent over to the remote system and executed there.
 * This is the smallest unit of message in remoting from one side to another,
 * such as "please execute this method" or "here's the return value from an earlier method call".
 * This is layer 0, the lower most layer.
 *
 * <p>
 * At this level, remoting of class files are not provided, so both {@link Channel}s
 * need to have the definition of {@link Command}-implementation.
 * 
 * @author Kohsuke Kawaguchi
 * @see hudson.remoting.Channel.Listener#onRead
 * @see hudson.remoting.Channel.Listener#onWrite
 * @since 3.17
 */
public abstract class Command implements Serializable {
    /**
     * This exception captures the stack trace of where the Command object is created.
     * This is useful for diagnosing the error when command fails to execute on the remote peer.
     * {@code null} if the cause is not recorded.
     */
    @CheckForNull
    final Exception createdAt;

    // Prohibited to subclass outside of this package.
    Command() {
        this(true);
    }

    Command(Channel channel, @CheckForNull Throwable cause) {
        // Command object needs to be deserializable on the other end without requiring custom classloading,
        // so we wrap this in ProxyException
        this.createdAt = new Source(cause != null ? new ProxyException(cause) : null);
    }

    /**
     * @param recordCreatedAt
     *      If false, skip the recording of where the command is created. This makes the trouble-shooting
     *      and cause/effect correlation hard in case of a failure, but it will reduce the amount of the data
     *      transferred.
     */
    Command(boolean recordCreatedAt) {
        if(recordCreatedAt)
            this.createdAt = new Source();
        else
            this.createdAt = null;
    }

    /**
     * Called on a remote system to perform this command.
     *
     * @param channel
     *      The {@link Channel} of the remote system.
     * @throws ExecutionException Execution error
     */
    abstract void execute(Channel channel) throws ExecutionException;


    /**
     * Chains the {@link #createdAt} cause.
     * It will happen if and only if cause recording is enabled.
     * @param initCause Original Cause. {@code null} causes will be ignored
     */
    final void chainCause(@CheckForNull Throwable initCause) {
        if (createdAt != null && initCause != null) {
            createdAt.initCause(initCause);
        }
    }
      
    /** Consider calling {@link Channel#notifyWrite} afterwards. */
    void writeTo(Channel channel, ObjectOutputStream oos) throws IOException {
        Channel old = Channel.setCurrent(channel);
        try {
            oos.writeObject(this);
        } finally {
            Channel.setCurrent(old);
        }
    }

    /**
     * Reads command from the specified payload.
     * @param channel Channel
     * @param payload Payload
     * @return Read command
     * @throws IOException Read exception
     * @throws ClassNotFoundException Deserialization error: class not found
     */
    public static Command readFrom(@Nonnull Channel channel, @Nonnull byte[] payload)
            throws IOException, ClassNotFoundException {
        Command cmd = Command.readFrom(channel, new ObjectInputStreamEx(
                new ByteArrayInputStream(payload),
                channel.baseClassLoader,channel.classFilter));
        channel.notifyRead(cmd, payload.length);
        return cmd;
    }

    /** Consider calling {@link Channel#notifyRead} afterwards. */
    static Command readFrom(Channel channel, ObjectInputStream ois) throws IOException, ClassNotFoundException {
        Channel old = Channel.setCurrent(channel);
        try {
            return (Command)ois.readObject();
        } finally {
            Channel.setCurrent(old);
        }
    }

    /**
     * Obtains a diagnostic stack trace recording the point at which the command was created.
     * This is not necessarily the point at which the command was delivered or run.
     * Part of the stack trace might have been produced on a remote machine,
     * in which case {@link ProxyException} may be used in place of the original.
     * @return an information stack trace, or null if not recorded
     */
    public @CheckForNull Throwable getCreationStackTrace() {
        return createdAt;
    }

    /**
     * Should provide concise information useful for {@link hudson.remoting.Channel.Listener}.
     */
    @Override
    public abstract String toString();

    private static final long serialVersionUID = 1L;

    private final class Source extends Exception {
        public Source() {
        }

        private Source(@CheckForNull Throwable cause) {
            super(cause);
        }

        public String toString() {
            return "Command "+Command.this.toString()+" created at";
        }

        private static final long serialVersionUID = 1L;
    }
}
