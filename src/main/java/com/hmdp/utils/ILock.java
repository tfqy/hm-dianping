package com.hmdp.utils;

/**
 * 2023/1/27 14:14
 *
 * @author tfqy
 */

public interface ILock {

    /**
     * 获取锁
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
