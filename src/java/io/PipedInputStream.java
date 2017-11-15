/*
 * Copyright (c) 1995, 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.io;

/**
 * A piped input stream should be connected
 * to a piped output stream; the piped  input
 * stream then provides whatever data bytes
 * are written to the piped output  stream.
 * Typically, data is read from a <code>PipedInputStream</code>
 * object by one thread  and data is written
 * to the corresponding <code>PipedOutputStream</code>
 * by some  other thread. Attempting to use
 * both objects from a single thread is not
 * recommended, as it may deadlock the thread.
 * The piped input stream contains a buffer,
 * decoupling read operations from write operations,
 * within limits.
 * A pipe is said to be <a name="BROKEN"> <i>broken</i> </a> if a
 * thread that was providing data bytes to the connected
 * piped output stream is no longer alive.
 *
 * @author  James Gosling
 * @see     PipedOutputStream
 * @since   JDK1.0
 */
//管道输入流。一对管道的输出输入需要在多个线程间进行，单个线程会引发死锁
public class PipedInputStream extends InputStream {
    boolean closedByWriter = false;
    volatile boolean closedByReader = false;
    boolean connected = false;

        /* REMIND: identification of the read and write sides needs to be
           more sophisticated.  Either using thread groups (but what about
           pipes within a thread?) or using finalization (but it may be a
           long time until the next GC). */
    //读取线程
    Thread readSide;
    //写线程
    Thread writeSide;

    private static final int DEFAULT_PIPE_SIZE = 1024;

    /**
     * The default size of the pipe's circular input buffer.
     * @since   JDK1.1
     */
    // This used to be a constant before the pipe size was allowed
    // to change. This field will continue to be maintained
    // for backward compatibility.
    protected static final int PIPE_SIZE = DEFAULT_PIPE_SIZE;

    /**
     * The circular buffer into which incoming data is placed.
     * @since   JDK1.1
     */
    protected byte buffer[];

    /**
     * The index of the position in the circular buffer at which the
     * next byte of data will be stored when received from the connected
     * piped output stream. <code>in&lt;0</code> implies the buffer is empty,
     * <code>in==out</code> implies the buffer is full
     * @since   JDK1.1
     */
    protected int in = -1;

    /**
     * The index of the position in the circular buffer at which the next
     * byte of data will be read by this piped input stream.
     * @since   JDK1.1
     */
    protected int out = 0;

    /**
     * Creates a <code>PipedInputStream</code> so
     * that it is connected to the piped output
     * stream <code>src</code>. Data bytes written
     * to <code>src</code> will then be  available
     * as input from this stream.
     *
     * @param      src   the stream to connect to.
     * @exception  IOException  if an I/O error occurs.
     */
    //指定管道输出流，创建该对象
    public PipedInputStream(PipedOutputStream src) throws IOException {
        this(src, DEFAULT_PIPE_SIZE);
    }

    /**
     * Creates a <code>PipedInputStream</code> so that it is
     * connected to the piped output stream
     * <code>src</code> and uses the specified pipe size for
     * the pipe's buffer.
     * Data bytes written to <code>src</code> will then
     * be available as input from this stream.
     *
     * @param      src   the stream to connect to.
     * @param      pipeSize the size of the pipe's buffer.
     * @exception  IOException  if an I/O error occurs.
     * @exception  IllegalArgumentException if {@code pipeSize <= 0}.
     * @since      1.6
     */
    public PipedInputStream(PipedOutputStream src, int pipeSize)
            throws IOException {
        //初始化管道
         initPipe(pipeSize);
         //链接到输出流
         connect(src);
    }

    /**
     * Creates a <code>PipedInputStream</code> so
     * that it is not yet {@linkplain #connect(PipedOutputStream)
     * connected}.
     * It must be {@linkplain PipedOutputStream#connect(
     * PipedInputStream) connected} to a
     * <code>PipedOutputStream</code> before being used.
     */
    public PipedInputStream() {
        initPipe(DEFAULT_PIPE_SIZE);
    }

    /**
     * Creates a <code>PipedInputStream</code> so that it is not yet
     * {@linkplain #connect(PipedOutputStream) connected} and
     * uses the specified pipe size for the pipe's buffer.
     * It must be {@linkplain PipedOutputStream#connect(
     * PipedInputStream)
     * connected} to a <code>PipedOutputStream</code> before being used.
     *
     * @param      pipeSize the size of the pipe's buffer.
     * @exception  IllegalArgumentException if {@code pipeSize <= 0}.
     * @since      1.6
     */
    public PipedInputStream(int pipeSize) {
        initPipe(pipeSize);
    }

    private void initPipe(int pipeSize) {
         if (pipeSize <= 0) {
            throw new IllegalArgumentException("Pipe Size <= 0");
         }
         buffer = new byte[pipeSize];
    }

    /**
     * Causes this piped input stream to be connected
     * to the piped  output stream <code>src</code>.
     * If this object is already connected to some
     * other piped output  stream, an <code>IOException</code>
     * is thrown.
     * <p>
     * If <code>src</code> is an
     * unconnected piped output stream and <code>snk</code>
     * is an unconnected piped input stream, they
     * may be connected by either the call:
     *
     * <pre><code>snk.connect(src)</code> </pre>
     * <p>
     * or the call:
     *
     * <pre><code>src.connect(snk)</code> </pre>
     * <p>
     * The two calls have the same effect.
     *
     * @param      src   The piped output stream to connect to.
     * @exception  IOException  if an I/O error occurs.
     */
    public void connect(PipedOutputStream src) throws IOException {
        //调用管道输出流的connect()方法连接到这个输入流。连接到指定管道输出流，如果该输入流已经连接了另一个管道，则抛出异常
        src.connect(this);
    }

    /**
     * Receives a byte of data.  This method will block if no input is
     * available.
     * @param b the byte being received
     * @exception IOException If the pipe is <a href="#BROKEN"> <code>broken</code></a>,
     *          {@link #connect(PipedOutputStream) unconnected},
     *          closed, or if an I/O error occurs.
     * @since     JDK1.1
     */
    protected synchronized void receive(int b) throws IOException {
        //检查接收状态，如果不通过，该方法中会直接抛出异常
        checkStateForReceive();
        //将调用该方法的线程设置为写入线程
        writeSide = Thread.currentThread();
        //如果缓冲区满了，则使用awaitSpace()方法，等待(阻塞自己)
        if (in == out)
            awaitSpace();
        if (in < 0) {          //in<0,表示之前没有开始接收数据,将in和out都设置为0，相当于开始接收输入和输出
            in = 0;
            out = 0;
        }
        //将b转为byte类型，存入缓冲区，并将in + 1
        buffer[in++] = (byte)(b & 0xFF);
        //最后判断下，这个表示已经将环形缓冲区一圈读完了，就从头开始再读(这也就形成了逻辑上的环形缓冲区)
        if (in >= buffer.length) {
            in = 0;
        }
    }

    /**
     * Receives data into an array of bytes.  This method will
     * block until some input is available.
     * @param b the buffer into which the data is received
     * @param off the start offset of the data
     * @param len the maximum number of bytes received
     * @exception IOException If the pipe is <a href="#BROKEN"> broken</a>,
     *           {@link #connect(PipedOutputStream) unconnected},
     *           closed,or if an I/O error occurs.
     */
    synchronized void receive(byte b[], int off, int len)  throws IOException {
        checkStateForReceive();
        writeSide = Thread.currentThread();
        int bytesToTransfer = len;
        while (bytesToTransfer > 0) {  //只要带传送长度还大于0，一直循环
            if (in == out)
                awaitSpace();
            //下一个转移总数：本次循环要从b[]数组拷贝到缓冲区的数据大小
            int nextTransferAmount = 0;
            if (out < in) { //有数据可以输出
                //下个转让总数 = 缓冲区还可以接收的数据数，也就是直接拷贝满
                nextTransferAmount = buffer.length - in;

                //如果in<out,可能是in=-1，也就是没有数据可以输出了；
                //也可能是in已经超了out一圈，此时out可以一直输出到缓冲数组的最后，然后再从缓冲数组的0位置一直输出到in这个位置-1
            } else if (in < out) {
                if (in == -1) { //缓冲区为空
                    in = out = 0;
                    nextTransferAmount = buffer.length - in;  //然后本次循环要拷贝的数据大小就是 缓冲区的长度
                } else {
                    //此时的情况就是in超过了out一圈，但是in最多也就只能超过out一圈，不然缓冲区的数据就会被覆盖，
                    // 所以能填充的数据也就是 out -in ，也就是说，此时in只能和out平齐,然后out可以把整个缓冲区的数据都输出了才能追上in
                    nextTransferAmount = out - in;
                }
            }
            if (nextTransferAmount > bytesToTransfer)
                nextTransferAmount = bytesToTransfer;
            assert(nextTransferAmount > 0);
            System.arraycopy(b, off, buffer, in, nextTransferAmount);
            bytesToTransfer -= nextTransferAmount;
            off += nextTransferAmount;
            in += nextTransferAmount;
            if (in >= buffer.length) {
                in = 0;
            }
        }
    }

    //检查该流的接收状态，判断此时能否接收新的数据
    private void checkStateForReceive() throws IOException {
        if (!connected) {   //如果没有连接通道输出流，抛出异常
            throw new IOException("Pipe not connected");
        } else if (closedByWriter || closedByReader) {  //如果写入关闭或读取关闭，抛出异常
            throw new IOException("Pipe closed");
        } else if (readSide != null && !readSide.isAlive()) {    //如果  读取线程为空 或 读取线程未激活，抛出异常
            throw new IOException("Read end dead");
        }
    }

    private void awaitSpace() throws IOException {
        while (in == out) { //只要还满着，就一直循环
            checkStateForReceive();

            /* full: kick any waiting readers */  //唤醒所有线程
            notifyAll();
            try {
                wait(1000);  //然后自己等待1s
            } catch (InterruptedException ex) {
                throw new InterruptedIOException();
            }
        }
    }

    /**
     * Notifies all waiting threads that the last byte of data has been
     * received.
     */
    synchronized void receivedLast() {
        closedByWriter = true;
        notifyAll();
    }

    /**
     * Reads the next byte of data from this piped input stream. The
     * value byte is returned as an <code>int</code> in the range
     * <code>0</code> to <code>255</code>.
     * This method blocks until input data is available, the end of the
     * stream is detected, or an exception is thrown.
     *
     * @return     the next byte of data, or <code>-1</code> if the end of the
     *             stream is reached.
     * @exception  IOException  if the pipe is
     *           {@link #connect(PipedOutputStream) unconnected},
     *           <a href="#BROKEN"> <code>broken</code></a>, closed,
     *           or if an I/O error occurs.
     */
    public synchronized int read()  throws IOException {
        if (!connected) {
            throw new IOException("Pipe not connected");
        } else if (closedByReader) {
            throw new IOException("Pipe closed");
        } else if (writeSide != null && !writeSide.isAlive()
                   && !closedByWriter && (in < 0)) {
            throw new IOException("Write end dead");
        }

        readSide = Thread.currentThread();
        int trials = 2;
        while (in < 0) {
            if (closedByWriter) {
                /* closed by writer, return EOF */
                return -1;
            }
            if ((writeSide != null) && (!writeSide.isAlive()) && (--trials < 0)) {
                throw new IOException("Pipe broken");
            }
            /* might be a writer waiting */
            //唤醒所有线程，因为in<0,可能是等待写入，所以唤醒所有线程，让写线程进行写操作
            notifyAll();
            try {
                wait(1000);
            } catch (InterruptedException ex) {
                throw new InterruptedIOException();
            }
        }
        int ret = buffer[out++] & 0xFF;
        if (out >= buffer.length) {   //输出完一圈后，重置到0位置
            out = 0;
        }
        if (in == out) {
            /* now empty */
            in = -1;
        }

        return ret;
    }

    /**
     * Reads up to <code>len</code> bytes of data from this piped input
     * stream into an array of bytes. Less than <code>len</code> bytes
     * will be read if the end of the data stream is reached or if
     * <code>len</code> exceeds the pipe's buffer size.
     * If <code>len </code> is zero, then no bytes are read and 0 is returned;
     * otherwise, the method blocks until at least 1 byte of input is
     * available, end of the stream has been detected, or an exception is
     * thrown.
     *
     * @param      b     the buffer into which the data is read.
     * @param      off   the start offset in the destination array <code>b</code>
     * @param      len   the maximum number of bytes read.
     * @return     the total number of bytes read into the buffer, or
     *             <code>-1</code> if there is no more data because the end of
     *             the stream has been reached.
     * @exception  NullPointerException If <code>b</code> is <code>null</code>.
     * @exception  IndexOutOfBoundsException If <code>off</code> is negative,
     * <code>len</code> is negative, or <code>len</code> is greater than
     * <code>b.length - off</code>
     * @exception  IOException if the pipe is <a href="#BROKEN"> <code>broken</code></a>,
     *           {@link #connect(PipedOutputStream) unconnected},
     *           closed, or if an I/O error occurs.
     */
    public synchronized int read(byte b[], int off, int len)  throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        /* possibly wait on the first character */
        int c = read();
        if (c < 0) {
            return -1;
        }
        b[off] = (byte) c;
        int rlen = 1;
        while ((in >= 0) && (len > 1)) {

            int available;

            if (in > out) {     //in>out，表示有可读数据，但是in没有超过out一圈
                available = Math.min((buffer.length - out), (in - out));
            } else {
                //这个则 in <= out 也就是说in已经超过了out一圈，那就先把out 到 缓冲区终点(终点和起点视同一点)读取完,
                //那也就是读取到缓冲区末尾，此时，缓冲区的0 - （in-1）位置还是可读的
                //此外，之所以要分两次读取的原因显而易见，环形缓冲区的本质还是一个byte[]，每次拷贝最多只能拷到数组末尾
                available = buffer.length - out;
            }

            // A byte is read beforehand outside the loop
            //此处是确保 本次循环要读取的字节长度 不超过 剩余要读取的字节长度
            if (available > (len - 1)) {
                available = len - 1;
            }
            System.arraycopy(buffer, out, b, off + rlen, available);
            out += available;
            rlen += available;
            len -= available;

            if (out >= buffer.length) {
                out = 0;
            }
            if (in == out) {
                /* now empty */
                in = -1;
            }
        }
        return rlen;
    }

    /**
     * Returns the number of bytes that can be read from this input
     * stream without blocking.
     *
     * @return the number of bytes that can be read from this input stream
     *         without blocking, or {@code 0} if this input stream has been
     *         closed by invoking its {@link #close()} method, or if the pipe
     *         is {@link #connect(PipedOutputStream) unconnected}, or
     *          <a href="#BROKEN"> <code>broken</code></a>.
     *
     * @exception  IOException  if an I/O error occurs.
     * @since   JDK1.0.2
     */
    public synchronized int available() throws IOException {
        if(in < 0)  //如果 输入索引 小于0，即-1，则表示没有可读取字节，所有返回0
            return 0;
        else if(in == out)  //如果in==out，表示缓冲区的所有字节都是可读取的，所以直接返回缓冲区大小
            return buffer.length;
        else if (in > out)
            return in - out;
        else
            //这个else表示 in < out,这种情况表示 输入索引已经比 输出索引快了一圈了，也就是说，
            //此时out可以一直输出到缓冲数组的最后，然后再从缓冲数组的0位置一直输出到in这个位置-1
            //所以此时的可读数据量就是下面这样
            return in + buffer.length - out;
    }

    /**
     * Closes this piped input stream and releases any system resources
     * associated with the stream.
     *
     * @exception  IOException  if an I/O error occurs.
     */
    public void close()  throws IOException {
        closedByReader = true;
        synchronized (this) {
            in = -1;
        }
    }
}
