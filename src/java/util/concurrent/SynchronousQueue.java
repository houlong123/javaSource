/*
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

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * A {@linkplain BlockingQueue blocking queue} in which each insert
 * operation must wait for a corresponding remove operation by another
 * thread, and vice versa.  A synchronous queue does not have any
 * internal capacity, not even a capacity of one.  You cannot
 * {@code peek} at a synchronous queue because an element is only
 * present when you try to remove it; you cannot insert an element
 * (using any method) unless another thread is trying to remove it;
 * you cannot iterate as there is nothing to iterate.  The
 * <em>head</em> of the queue is the element that the first queued
 * inserting thread is trying to add to the queue; if there is no such
 * queued thread then no element is available for removal and
 * {@code poll()} will return {@code null}.  For purposes of other
 * {@code Collection} methods (for example {@code contains}), a
 * {@code SynchronousQueue} acts as an empty collection.  This queue
 * does not permit {@code null} elements.
 *
 * <p>Synchronous queues are similar to rendezvous channels used in
 * CSP and Ada. They are well suited for handoff designs, in which an
 * object running in one thread must sync up with an object running
 * in another thread in order to hand it some information, event, or
 * task.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <E> the type of elements held in this collection
 */
public class SynchronousQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * This class implements extensions of the dual stack and dual
     * queue algorithms described in "Nonblocking Concurrent Objects
     * with Condition Synchronization", by W. N. Scherer III and
     * M. L. Scott.  18th Annual Conf. on Distributed Computing,
     * Oct. 2004 (see also
     * http://www.cs.rochester.edu/u/scott/synchronization/pseudocode/duals.html).
     * The (Lifo) stack is used for non-fair mode, and the (Fifo)
     * queue for fair mode. The performance of the two is generally
     * similar. Fifo usually supports higher throughput under
     * contention but Lifo maintains higher thread locality in common
     * applications.
     *
     * A dual queue (and similarly stack) is one that at any given
     * time either holds "data" -- items provided by put operations,
     * or "requests" -- slots representing take operations, or is
     * empty. A call to "fulfill" (i.e., a call requesting an item
     * from a queue holding data or vice versa) dequeues a
     * complementary node.  The most interesting feature of these
     * queues is that any operation can figure out which mode the
     * queue is in, and act accordingly without needing locks.
     *
     * Both the queue and stack extend abstract class Transferer
     * defining the single method transfer that does a put or a
     * take. These are unified into a single method because in dual
     * data structures, the put and take operations are symmetrical,
     * so nearly all code can be combined. The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
     *
     * The queue and stack data structures share many conceptual
     * similarities but very few concrete details. For simplicity,
     * they are kept distinct so that they can later evolve
     * separately.
     *
     * The algorithms here differ from the versions in the above paper
     * in extending them for use in synchronous queues, as well as
     * dealing with cancellation. The main differences include:
     *
     *  1. The original algorithms used bit-marked pointers, but
     *     the ones here use mode bits in nodes, leading to a number
     *     of further adaptations.
     *  2. SynchronousQueues must block threads waiting to become
     *     fulfilled.
     *  3. Support for cancellation via timeout and interrupts,
     *     including cleaning out cancelled nodes/threads
     *     from lists to avoid garbage retention and memory depletion.
     *
     * Blocking is mainly accomplished using LockSupport park/unpark,
     * except that nodes that appear to be the next ones to become
     * fulfilled first spin a bit (on multiprocessors only). On very
     * busy synchronous queues, spinning can dramatically improve
     * throughput. And on less busy ones, the amount of spinning is
     * small enough not to be noticeable.
     *
     * Cleaning is done in different ways in queues vs stacks.  For
     * queues, we can almost always remove a node immediately in O(1)
     * time (modulo retries for consistency checks) when it is
     * cancelled. But if it may be pinned as the current tail, it must
     * wait until some subsequent cancellation. For stacks, we need a
     * potentially O(n) traversal to be sure that we can remove the
     * node, but this can run concurrently with other threads
     * accessing the stack.
     *
     * While garbage collection takes care of most node reclamation
     * issues that otherwise complicate nonblocking algorithms, care
     * is taken to "forget" references to data, other nodes, and
     * threads that might be held on to long-term by blocked
     * threads. In cases where setting to null would otherwise
     * conflict with main algorithms, this is done by changing a
     * node's link to now point to the node itself. This doesn't arise
     * much for Stack nodes (because blocked threads do not hang on to
     * old head pointers), but references in Queue nodes must be
     * aggressively forgotten to avoid reachability of everything any
     * node has ever referred to since arrival.
     */

    /**
     * Shared internal API for dual stacks and queues.
     */
    abstract static class Transferer<E> {
        /**
         * Performs a put or take.
         *
         * @param e if non-null, the item to be handed to a consumer;
         *          if null, requests that transfer return an item
         *          offered by producer.
         * @param timed if this operation should timeout
         * @param nanos the timeout, in nanoseconds
         * @return if non-null, the item provided or received; if null,
         *         the operation failed due to timeout or interrupt --
         *         the caller can distinguish which of these occurred
         *         by checking Thread.interrupted.
         */
        // 转移数据，put或者take操作
        abstract E transfer(E e, boolean timed, long nanos);
    }

    /** The number of CPUs, for spin control */
    // 可用的处理器
    static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    // 最大空旋时间
    static final int maxTimedSpins = (NCPUS < 2) ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    // 无限时的等待的最大空旋时间
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    // 超时空旋等待阈值
    static final long spinForTimeoutThreshold = 1000L;

    /** Dual stack */
    static final class TransferStack<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual stack algorithm, differing,
         * among other ways, by using "covering" nodes rather than
         * bit-marked pointers: Fulfilling operations push on marker
         * nodes (with FULFILLING bit set in mode) to reserve a spot
         * to match a waiting node.
         */

        /* Modes for SNodes, ORed together in node fields */
        /** Node represents an unfulfilled consumer */
        // 表示消费数据的消费者
        static final int REQUEST    = 0;
        /** Node represents an unfulfilled producer */
        // 表示生产数据的生产者
        static final int DATA       = 1;
        /** Node is fulfilling another unfulfilled DATA or REQUEST */
        //表示该操作节点处于真正匹配状态
        static final int FULFILLING = 2;

        /** Returns true if m has fulfilling bit set. */
        //是否处于匹配状态
        static boolean isFulfilling(int m) { return (m & FULFILLING) != 0; }

        /** Node class for TransferStacks. */
        // SNode类表示栈中的结点，使用了反射机制和CAS来保证原子性的改变相应的域值。
        static final class SNode {
            // 下一个结点
            volatile SNode next;        // next node in stack
            // 相匹配的结点
            volatile SNode match;       // the node matched to this
            // 等待的线程
            volatile Thread waiter;     // to control park/unpark
            // 元素项
            Object item;                // data; or null for REQUESTs

            /**
             * 模式。有四种可能。
             *  1) REQUEST 0000
             *  2) DATA    0001
             *  3) REQUEST（0000）| FULFILLING（0010） =  0010 表示消费者匹配到了生成者。REQUEST为此次操作，即出队
             *  4）DATA（0001）| FULFILLING（0010） =  0011 表示生成者匹配到了消费者。DATA为此次操作，即入队
             *  后两种是在成功匹配后，将节点入队时设置的节点模式。通过：FULFILLING|mode 计算节点模式
             */
            int mode;
            // Note: item and mode fields don't need to be volatile
            // since they are always written before, and read after,
            // other volatile/atomic operations.

            SNode(Object item) {
                this.item = item;
            }

            boolean casNext(SNode cmp, SNode val) {
                return cmp == next &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * Tries to match node s to this node, if so, waking up thread.
             * Fulfillers call tryMatch to identify their waiters.
             * Waiters block until they have been matched.
             *
             * @param s the node to match
             * @return true if successfully matched to s
             */
            boolean tryMatch(SNode s) {
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {  // 本结点的match域为null并且比较并替换match域成功
                    // 获取本节点的等待线程
                    Thread w = waiter;
                    // 存在等待的线程
                    if (w != null) {    // waiters need at most one unpark
                        waiter = null;  // 将本结点的等待线程重新置为null
                        LockSupport.unpark(w);  // unpark等待线程
                    }
                    return true;
                }
                return match == s;  // 如果match不为null或者CAS设置失败，则比较match域是否等于s结点，若相等，则表示已经完成匹配，匹配成功
            }

            /**
             * Tries to cancel a wait by matching node to itself.
             */
            void tryCancel() {
                UNSAFE.compareAndSwapObject(this, matchOffset, null, this);
            }

            boolean isCancelled() {
                return match == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long matchOffset;  // match域的内存偏移地址
            private static final long nextOffset;   // next域的偏移地址

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = SNode.class;
                    matchOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("match"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** The head (top) of the stack */
        // 栈头结点
        volatile SNode head;

        boolean casHead(SNode h, SNode nh) {
            return h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh);
        }

        /**
         * Creates or resets fields of a node. Called only from transfer
         * where the node to push on stack is lazily created and
         * reused when possible to help reduce intervals between reads
         * and CASes of head and to avoid surges of garbage when CASes
         * to push nodes fail due to contention.
         */
        //生成栈节点
        static SNode snode(SNode s, Object e, SNode next, int mode) {
            if (s == null) s = new SNode(e);
            s.mode = mode;
            s.next = next;
            return s;
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /*
             * Basic algorithm is to loop trying one of three actions:
             *
             * 1. If apparently empty or already containing nodes of same
             *    mode, try to push node on stack and wait for a match,
             *    returning it, or null if cancelled.
             *
             * 2. If apparently containing node of complementary mode,
             *    try to push a fulfilling node on to stack, match
             *    with corresponding waiting node, pop both from
             *    stack, and return matched item. The matching or
             *    unlinking might not actually be necessary because of
             *    other threads performing action 3:
             *
             * 3. If top of stack already holds another fulfilling node,
             *    help it out by doing its match and/or pop
             *    operations, and then continue. The code for helping
             *    is essentially the same as for fulfilling, except
             *    that it doesn't return the item.
             */

            SNode s = null; // constructed/reused as needed
            // 根据e确定此次转移的模式（是put or take）
            int mode = (e == null) ? REQUEST : DATA;

            for (;;) {  //无限循环
                SNode h = head; //保存头结点

                //相同的操作模式
                if (h == null || h.mode == mode) {  // 头结点为null或者头结点的模式与此次转移的模式相同 empty or same-mode

                    if (timed && nanos <= 0) {      // 设置了timed并且等待时间小于等于0，表示不能等待，需要立即操作 can't wait
                        if (h != null && h.isCancelled())   // 头结点不为null并且头结点被取消
                            casHead(h, h.next);     // 重新设置头结点（弹出之前的头结点） pop cancelled node
                        else    // 头结点为null或者头结点没有被取消
                            return null;
                    } else if (casHead(h, s = snode(s, e, h, mode))) {  // 生成一个SNode结点；将原来的head头结点设置为该结点的next结点；将head头结点设置为该结点。入队
                        //等待匹配操作
                        SNode m = awaitFulfill(s, timed, nanos);    // 空旋或者阻塞直到s结点被FulFill操作所匹配
                        if (m == s) {               // 匹配的结点为s结点（s结点被取消） wait was cancelled
                            clean(s);
                            return null;
                        }

                        //s 还没有离开栈，帮助其离开
                        if ((h = head) != null && h.next == s)  // h重新赋值为head头结点，并且不为null；头结点的next域为s结点，表示有结点插入到s结点之前，完成了匹配
                            casHead(h, s.next);     // 比较并替换head域（移除插入在s之前的结点和s结点） // help s's fulfiller
                        return (E) ((mode == REQUEST) ? m.item : s.item);    // 根据此次转移的类型返回元素
                    }
                } else if (!isFulfilling(h.mode)) { // 不同的模式，并且没有处于正在匹配状态，则进行匹配 // try to fulfill
                    //节点取消，更新head
                    if (h.isCancelled())            // already cancelled
                        // 比较并替换head域（弹出头结点）
                        casHead(h, h.next);         // pop and retry

                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {   //入队一个新节点，并且处于匹配状态（表示h正在匹配）
                        for (;;) { // loop until matched or waiters disappear
                            // s.next 是真正的操作节点
                            SNode m = s.next;       // m is s's match

                            // next域为null
                            if (m == null) {        // all waiters are gone
                                // 比较并替换head域
                                casHead(s, null);   // pop fulfill node
                                s = null;           // use new node next time
                                break;              // restart main loop
                            }

                            // m结点的next域
                            SNode mn = m.next;
                            if (m.tryMatch(s)) {    // 尝试匹配，并且成功
                                // 比较并替换head域（弹出s结点和m结点）
                                casHead(s, mn);     // pop both s and m
                                return (E) ((mode == REQUEST) ? m.item : s.item);   // 根据此次转移的类型返回元素
                            } else                  // lost match
                                // 没有匹配成功，说明有其他线程已经匹配了，把m移出
                                s.casNext(m, mn);   // help unlink
                        }
                    }
                } else {         // 头结点是真正匹配的状态，那么就帮助它匹配                  // help a fulfiller
                    // 保存头结点的next域
                    SNode m = h.next;               // m is h's match
                    if (m == null)                  // waiter is gone
                        // 比较并替换head域（m被其他结点匹配了，需要弹出h）
                        casHead(h, null);           // pop fulfilling node
                    else {
                        // 获取m结点的next域
                        SNode mn = m.next;
                        if (m.tryMatch(h))  // 帮助匹配 / help match
                            // 比较并替换head域（弹出h和m结点）
                            casHead(h, mn);         // pop both h and m
                        else    // 匹配不成功                     // lost match
                            // 比较并替换next域（移除m结点）
                            h.casNext(m, mn);       // help unlink
                    }
                }
            }
        }

        /**
         * Spins/blocks until node s is matched by a fulfill operation.
         *
         * @param s the waiting node
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched node, or s if cancelled
         */
        SNode awaitFulfill(SNode s, boolean timed, long nanos) {
            /*
             * When a node/thread is about to block, it sets its waiter
             * field and then rechecks state at least one more time
             * before actually parking, thus covering race vs
             * fulfiller noticing that waiter is non-null so should be
             * woken.
             *
             * When invoked by nodes that appear at the point of call
             * to be at the head of the stack, calls to park are
             * preceded by spins to avoid blocking when producers and
             * consumers are arriving very close in time.  This can
             * happen enough to bother only on multiprocessors.
             *
             * The order of checks for returning out of main loop
             * reflects fact that interrupts have precedence over
             * normal returns, which have precedence over
             * timeouts. (So, on timeout, one last check for match is
             * done before giving up.) Except that calls from untimed
             * SynchronousQueue.{poll/offer} don't check interrupts
             * and don't wait at all, so are trapped in transfer
             * method rather than calling awaitFulfill.
             */
            // 根据timed标识计算截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            // 获取当前线程
            Thread w = Thread.currentThread();
            // 根据s确定空旋等待的时间
            int spins = (shouldSpin(s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            for (;;) {  // 无限循环，确保操作成功
                if (w.isInterrupted())  // 当前线程被中断
                    // 取消s结点
                    s.tryCancel();
                // 获取s结点的match域
                SNode m = s.match;
                if (m != null)   // m不为null，存在匹配结点
                    return m;
                if (timed) {
                    // 确定继续等待的时间
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {  // 继续等待的时间小于等于0，等待超时
                        s.tryCancel();   // 取消s结点
                        continue;
                    }
                }
                if (spins > 0)  // 空旋等待的时间大于0
                    spins = shouldSpin(s) ? (spins-1) : 0;   // 确定是否还需要继续空旋等待
                else if (s.waiter == null)   // 等待线程为null
                    // 设置waiter线程为当前线程
                    s.waiter = w; // establish waiter so can park next iter
                else if (!timed)
                    // 禁用当前线程并设置了阻塞者
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)   // 继续等待的时间大于阈值
                    LockSupport.parkNanos(this, nanos); // 禁用当前线程，最多等待指定的等待时间，除非许可可用
            }
        }

        /**
         * Returns true if node s is at head or there is an active
         * fulfiller.
         */
        boolean shouldSpin(SNode s) {
            SNode h = head; // 获取头结点
            // s为头结点或者头结点为null或者h包含FULFILLING标记,返回true
            return (h == s || h == null || isFulfilling(h.mode));
        }

        /**
         * Unlinks s from the stack.
         */
        // 移除从栈顶头结点开始到该结点（不包括）之间的所有已取消结点。
        void clean(SNode s) {
            // s结点的item设置为null
            s.item = null;   // forget item
            // s结点的waiter设置为null
            s.waiter = null; // forget thread

            /*
             * At worst we may need to traverse entire stack to unlink
             * s. If there are multiple concurrent calls to clean, we
             * might not see s if another thread has already removed
             * it. But we can stop when we see any node known to
             * follow s. We use s.next unless it too is cancelled, in
             * which case we try the node one past. We don't check any
             * further because we don't want to doubly traverse just to
             * find sentinel.
             */
            //被删除节点的后继
            SNode past = s.next;

            //后继节点操作被取消，直接移除该节点
            if (past != null && past.isCancelled()) // next域不为null并且next域被取消
                past = past.next;   // 重新设置past

            // Absorb cancelled nodes at head
            SNode p;
            //如果栈顶是取消了的操作节点，则移除
            while ((p = head) != null && p != past && p.isCancelled())   // 从栈顶头结点开始到past结点（不包括），将连续的取消结点移除
                casHead(p, p.next);

            // Unsplice embedded nodes
            //因为是单向链表，因此需要从head 开始，遍历到被删除节点的后继
            while (p != null && p != past) {     // 移除上一步骤没有移除的非连续的取消结点
                SNode n = p.next;   // 获取p的next域
                if (n != null && n.isCancelled())   // n不为null并且n被取消
                    p.casNext(n, n.next);    // 比较并替换next域
                else
                    p = n;
            }
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferStack.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Dual Queue */
    /**
     *  这是一个典型的 queue , 它有如下的特点
     *  1. 整个队列有 head, tail 两个节点
     *  2. 队列初始化时会有个 dummy 节点
     *  3. 这个队列的头节点是个 dummy 节点/ 或 哨兵节点, 所以操作的总是队列中的第二个节点(AQS的设计中也是这也)
     */
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         */

        /** Node class for TransferQueue. */
        static final class QNode {
            volatile QNode next;          // next node in queue
            volatile Object item;         // CAS'ed to or from null
            volatile Thread waiter;       // to control park/unpark
            final boolean isData;

            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }

            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
            }

            /**
             * Tries to cancel by CAS'ing ref to this as item.
             */
            // 取消本结点，将item域设置为自身
            void tryCancel(Object cmp) {
                UNSAFE.compareAndSwapObject(this, itemOffset, cmp, this);
            }

            // 是否被取消
            boolean isCancelled() {
                return item == this;
            }

            /**
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             */
            // 是否不在队列中
            boolean isOffList() {
                return next == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long itemOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = QNode.class;
                    itemOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("item"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** Head of queue */
        //队列头
        transient volatile QNode head;
        /** Tail of queue */
        //队列尾
        transient volatile QNode tail;
        /**
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         */
        //当要删除的节点为队列中最后一个元素时，会引用到这个节点
        transient volatile QNode cleanMe;

        TransferQueue() {
            /**
             * 构造一个 dummy node, 而整个 queue 中永远会存在这样一个 dummy node
             * dummy node 的存在使得 代码中不存在复杂的 if 条件判断
             */
            QNode h = new QNode(null, false); // initialize to dummy node.
            head = h;
            tail = h;
        }

        /**
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         */
        // 通过CAS重置nh为头节点
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh))
                h.next = h; // forget old next
        }

        /**
         * Tries to cas nt as new tail.
         */
        //CAS更新nt为尾节点
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                UNSAFE.compareAndSwapObject(this, tailOffset, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         */
        /** CAS 设置 cleamMe 节点 */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                UNSAFE.compareAndSwapObject(this, cleanMeOffset, cmp, val);
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             *
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             */

            QNode s = null; // constructed/reused as needed
            // 确定此次转移的类型（put or take）
            boolean isData = (e != null);

            for (;;) {
                QNode t = tail; // 获取尾结点
                QNode h = head; //获取头结点

                //初始化的队头和队尾都是指向的一个"空" 节点，不会是null
                if (t == null || h == null)       // 看到未初始化的头尾结点  // saw uninitialized value
                    continue;                       // spin

                if (h == t || t.isData == isData) { //入队操作。 队列为空 或者 尾结点的模式与当前结点模式相同,即上次和本次是同样的操作：入队或者出队。每一个入队和出队是互相匹配的  // empty or same-mode
                    // 获取尾结点的next域
                    QNode tn = t.next;

                    // 存在并发，队列被修改了，从头开始
                    if (t != tail)                  // t不为尾结点，不一致，有并发，重试  // inconsistent read
                        continue;

                    //队列被修改了，tail不是队尾，则辅助推进tail
                    if (tn != null) {           //tn不为null，有其他线程添加了tail的next结点，帮助推进 tail    // lagging tail
                        // 设置新的尾结点为tn
                        advanceTail(t, tn);
                        continue;
                    }

                    //如果进行了超时等待操作，发生超时则返回NULL
                    if (timed && nanos <= 0)    // 设置了timed并且等待时间小于等于0，表示不能等待，需要立即操作    // can't wait
                        return null;

                    if (s == null)
                        s = new QNode(e, isData);   // 新生一个结点并赋值给s

                    // 将新节点 cas 设置成tail的后继 ,失败则重来
                    if (!t.casNext(null, s))   // 将 新建的节点加入到 队列中  // failed to link in
                        continue;

                    // 添加了一个节点，推进队尾指针，将队尾指向新加入的节点
                    advanceTail(t, s);              // swing tail and wait

                    // 空旋或者阻塞直到有匹配操作，即s结点被匹配
                    Object x = awaitFulfill(s, e, timed, nanos);    // 调用awaitFulfill, 若节点是 head.next, 则进行一些自旋, 若不是的话, 直接 block, 知道有其他线程 与之匹配, 或它自己进行线程的中断

                    //如果操作被取消
                    if (x == s) {          // x与s相等，表示已经取消         // wait was cancelled
                        clean(t, s);    // 清除
                        return null;
                    }

                    //匹配的操作到来，s操作完成，离开队列，如果没离开，使其离开
                    if (!s.isOffList()) {           // not already unlinked
                        //推进head ,cas将head 由t(是t不是h)，设置为s
                        advanceHead(t, s);          // unlink if head
                        // x不为null
                        if (x != null)              // and forget fields
                            // 设置s结点的item
                            s.item = s;
                        // 设置s结点的waiter域为null
                        s.waiter = null;
                    }
                    return (x != null) ? (E)x : e;

                } else {  // 模式互补。不同的模式那么是匹配的（put 匹配take ，take匹配put），出队操作             // complementary-mode

                    // 获取头结点的next域（匹配的结点）。 这里是从head.next开始，因为TransferQueue总是会存在一个dummy node节点
                    QNode m = h.next;               // node to fulfill

                    //队列发生变化，重来
                    if (t != tail || m == null || h != head)    // t不为尾结点或者m为null或者h不为头结点（不一致）
                        continue;                   // inconsistent read

                    Object x = m.item;  // 获取m结点的元素域

                    // 如果isData == (x != null) 为true 那么表示是相同的操作。
                    //x==m 则是被取消了的操作
                    //m.casItem(x, e) 将m的item设置为本次操作的数据域
                    if (isData == (x != null) ||    // m结点被匹配 // m already fulfilled
                        x == m ||                   // m结点被取消 // m cancelled
                        !m.casItem(x, e)) {         // CAS操作失败 // lost CAS
                        advanceHead(h, m);          // 队列头结点出队列，并重试 // dequeue and retry
                        continue;
                    }

                    // 匹配成功，设置新的头结点
                    advanceHead(h, m);              // successfully fulfilled

                    // 唤醒匹配操作的线程
                    LockSupport.unpark(m.waiter);
                    return (x != null) ? (E)x : e;
                }
            }
        }

        /**
         * Spins/blocks until node s is fulfilled.
         *
         * @param s the waiting node
         * @param e the comparison value for checking match
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched item, or s if cancelled
         */
        //主逻辑: 若节点是 head.next 则进行 spins 一会, 若不是, 则调用 LockSupport.park / parkNanos(), 直到其他的线程对其进行唤醒
        Object awaitFulfill(QNode s, E e, boolean timed, long nanos) {          // s 是刚刚入队的操作节点，e是操作（也可说是数据）
            /* Same idea as TransferStack.awaitFulfill */
            // 根据timed标识计算截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            // 计算空旋时间
            int spins = ((head.next == s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);    //若当前节点为head.next时才进行自旋

            for (;;) {

                //如果当前线程发生中断，那么尝试取消该操作
                if (w.isInterrupted())
                    s.tryCancel(e); // 若线程中断, 直接将 item 设置成了 this, 在 transfer 中会对返回值进行判断

                Object x = s.item;  // 获取s的元素域

                // s.item ！=e则返回，生成s节点的时候，s.item是等于e的，当取消操作或者匹配了操作的时候会进行更改。匹配对应transfer方法中的 m.casItem(x, e) 代码
                if (x != e) // 在进行线程阻塞->唤醒, 线程中断, 等待超时, 这时 x != e,直接return 回去
                    return x;

                //如果设置了超时等待
                if (timed) {    // 设置了timed
                    nanos = deadline - System.nanoTime();   // 计算继续等待的时间

                    //发生了超时，尝试取消该操作
                    if (nanos <= 0L) {
                        s.tryCancel(e);
                        continue;
                    }
                }

                //自旋控制
                if (spins > 0)  // 空旋时间大于0，空旋
                    --spins;

                //设置等待线程 waiter
                else if (s.waiter == null)
                    // 设置等待线程
                    s.waiter = w;

                else if (!timed)
                    // 禁用当前线程并设置了阻塞者
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)   //自旋次数过了, 直接 + timeout 方式 park
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Gets rid of cancelled node s with original predecessor pred.
         */
        //用于移除已经被取消的结点。pred 是s的前驱
        void clean(QNode pred, QNode s) {
            s.waiter = null; // forget thread
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             */
            while (pred.next == s) { // pred的next域为s // Return early if already unlinked
                QNode h = head; // 获取头结点
                // 获取头结点的next域
                QNode hn = h.next;   // Absorb cancelled first node as head

                //操作取消了，那么推进head
                if (hn != null && hn.isCancelled()) {   // hn不为null并且hn被取消，重新设置头结点
                    advanceHead(h, hn);
                    continue;
                }

                // 获取尾结点，保证对尾结点的读一致性
                QNode t = tail;      // Ensure consistent read for tail
                if (t == h) // 尾结点为头结点，表示队列为空
                    return;

                // 获取尾结点的next域
                QNode tn = t.next;
                if (t != tail)  // t不为尾结点，不一致，重试
                    continue;

                //tn 理应为null ,如果不为空，说明其它线程进行了入队操作，更新tail
                if (tn != null) {   //tn不为null ，说明其他线程修改了尾节点
                    advanceTail(t, tn); //设置新的尾节点
                    continue;
                }

                // s!=t ,则s不是尾节点了，本来最开始是尾节点，其它线程进行了入队操作
                if (s != t) {   // s不为尾结点，移除s      // If not tail, try to unsplice
                    QNode sn = s.next;

                    // 如果s.next ==s ,则已经离开队列
                    //设置pred的后继为s的后继，将s从队列中删除
                    if (sn == s || pred.casNext(s, sn))
                        return;
                }

                // 到这里，那么s就是队尾，那么暂时不能删除
                //cleanMe标识的是需要删除节点的前驱
                QNode dp = cleanMe;

                //有需要删除的节点
                if (dp != null) { // dp不为null，断开前面被取消的结点   // Try unlinking previous cancelled node
                    QNode d = dp.next;
                    QNode dn;
                    /**
                     * cleamMe 失效的情况有：
                     *   (1)cleanMe的后继而空（cleanMe 标记的是需要删除节点的前驱）
                     *   (2)cleanMe的后继等于自身（这个前面有分析过）
                     *   (3)需要删除节点的操作没有被取消
                     *   (4)被删除的节点不是尾节点且其后继节点有效
                     */
                     if (d == null ||               // d is gone or
                        d == dp ||                 // d is off list or
                        !d.isCancelled() ||        // d not cancelled or
                        (d != t &&                 // d not tail and
                         (dn = d.next) != null &&  //   has successor
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // d unspliced

                        //清除cleanMe节点
                        casCleanMe(dp, null);

                    //dp==pred 表示已经被设置过了
                    if (dp == pred)
                        return;      // s is already saved node
                } else if (casCleanMe(null, pred))  // 原来的 cleanMe 是 null, 则将 pred 标记为 cleamMe 为下次 清除 s 节点做标识
                    return;          // Postpone cleaning s
            }
        }

        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        private static final long tailOffset;
        private static final long cleanMeOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferQueue.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
                tailOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("tail"));
                cleanMeOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("cleanMe"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * The transferer. Set only in constructor, but cannot be declared
     * as final without further complicating serialization.  Since
     * this is accessed only at most once per public method, there
     * isn't a noticeable performance penalty for using volatile
     * instead of final here.
     */
    private transient volatile Transferer<E> transferer;

    /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
    public SynchronousQueue() {
        this(false);
    }

    /**
     * Creates a {@code SynchronousQueue} with the specified fairness policy.
     *
     * @param fair if true, waiting threads contend in FIFO order for
     *        access; otherwise the order is unspecified.
     */
    public SynchronousQueue(boolean fair) {
        // 通过 fair 值来决定内部用 使用 queue 还是 stack 存储线程节点。队列是公平的
        transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
    }

    /**
     * Adds the specified element to this queue, waiting if necessary for
     * another thread to receive it.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    // 将指定元素添加到此队列，如有必要则等待另一个线程接收它
    public void put(E e) throws InterruptedException {
        //元素e为空，抛异常
        if (e == null) throw new NullPointerException();

        if (transferer.transfer(e, false, 0) == null) { // 进行转移操作
            Thread.interrupted();   // 中断当前线程
            throw new InterruptedException();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * up to the specified wait time for another thread to receive it.
     *
     * @return {@code true} if successful, or {@code false} if the
     *         specified waiting time elapses before a consumer appears
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    // 将指定元素插入到此队列，如有必要则等待指定的时间，以便另一个线程接收它

    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        //元素e为空，抛异常
        if (e == null) throw new NullPointerException();
        //进行转移操作
        if (transferer.transfer(e, true, unit.toNanos(timeout)) != null)
            return true;
        if (!Thread.interrupted())  // 当前线程没有被中断
            return false;
        throw new InterruptedException();
    }

    /**
     * Inserts the specified element into this queue, if another thread is
     * waiting to receive it.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else
     *         {@code false}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        return transferer.transfer(e, true, 0) != null;
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * for another thread to insert it.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    // 获取并移除此队列的头，如有必要则等待另一个线程插入它
    public E take() throws InterruptedException {
        E e = transferer.transfer(null, false, 0);  // 进行转移操作
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary up to the specified wait time, for another thread
     * to insert it.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is present
     * @throws InterruptedException {@inheritDoc}
     */
    // 获取并移除此队列的头，如有必要则等待指定的时间，以便另一个线程插入它

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = transferer.transfer(null, true, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())  // 元素不为null或者当前线程没有被中断
            return e;
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, if another thread
     * is currently making an element available.
     *
     * @return the head of this queue, or {@code null} if no
     *         element is available
     */
    // 如果另一个线程当前正要使用某个元素，则获取并移除此队列的头
    public E poll() {
        return transferer.transfer(null, true, 0);
    }

    /**
     * Always returns {@code true}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return {@code true}
     */
    public boolean isEmpty() {
        return true;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int size() {
        return 0;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int remainingCapacity() {
        return 0;
    }

    /**
     * Does nothing.
     * A {@code SynchronousQueue} has no internal capacity.
     */
    public void clear() {
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element
     * @return {@code false}
     */
    public boolean contains(Object o) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element to remove
     * @return {@code false}
     */
    public boolean remove(Object o) {
        return false;
    }

    /**
     * Returns {@code false} unless the given collection is empty.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false} unless given collection is empty
     */
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code null}.
     * A {@code SynchronousQueue} does not return elements
     * unless actively waited on.
     *
     * @return {@code null}
     */
    public E peek() {
        return null;
    }

    /**
     * Returns an empty iterator in which {@code hasNext} always returns
     * {@code false}.
     *
     * @return an empty iterator
     */
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    /**
     * Returns an empty spliterator in which calls to
     * {@link Spliterator#trySplit()} always return {@code null}.
     *
     * @return an empty spliterator
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.emptySpliterator();
    }

    /**
     * Returns a zero-length array.
     * @return a zero-length array
     */
    public Object[] toArray() {
        return new Object[0];
    }

    /**
     * Sets the zeroeth element of the specified array to {@code null}
     * (if the array has non-zero length) and returns it.
     *
     * @param a the array
     * @return the specified array
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        if (a.length > 0)
            a[0] = null;
        return a;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /*
     * To cope with serialization strategy in the 1.5 version of
     * SynchronousQueue, we declare some unused classes and fields
     * that exist solely to enable serializability across versions.
     * These fields are never used, so are initialized only if this
     * object is ever serialized or deserialized.
     */

    @SuppressWarnings("serial")
    static class WaitQueue implements java.io.Serializable { }
    static class LifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3633113410248163686L;
    }
    static class FifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3623113410248163686L;
    }
    private ReentrantLock qlock;
    private WaitQueue waitingProducers;
    private WaitQueue waitingConsumers;

    /**
     * Saves this queue to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        boolean fair = transferer instanceof TransferQueue;
        if (fair) {
            qlock = new ReentrantLock(true);
            waitingProducers = new FifoWaitQueue();
            waitingConsumers = new FifoWaitQueue();
        }
        else {
            qlock = new ReentrantLock();
            waitingProducers = new LifoWaitQueue();
            waitingConsumers = new LifoWaitQueue();
        }
        s.defaultWriteObject();
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        if (waitingProducers instanceof FifoWaitQueue)
            transferer = new TransferQueue<E>();
        else
            transferer = new TransferStack<E>();
    }

    // Unsafe mechanics
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }

}
