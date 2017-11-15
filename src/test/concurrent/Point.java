package test.concurrent;

import java.util.concurrent.locks.StampedLock;

/**
 * Created by houlong on 2017/9/19.
 */
public class Point {
    private static double x, y;
    private final static StampedLock lock = new StampedLock();

    static void move(double deltaX, double deltaY) {
        long stamp = lock.writeLock();
        try {
            x += deltaX;
            y += deltaY;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public static void main(String[] args) {
        System.out.println("before: x = " + x + ", y = " + y);
        for (int x = 0; x <= 1000; x++) {
            new Thread(() -> move(2, 4)).start();
        }

        System.out.println("after: x = " + x + ", y = " + y);
    }
}
