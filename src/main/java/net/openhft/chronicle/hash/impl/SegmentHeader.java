/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.hash.impl;

import java.util.concurrent.TimeUnit;

public interface SegmentHeader {
    long size(long address);
    void size(long address, long size);

    long deleted(long address);
    void deleted(long address, long deleted);

    long nextPosToSearchFrom(long address);
    void nextPosToSearchFrom(long address, long nextPosToSearchFrom);

    void readLock(long address);
    void readLockInterruptibly(long address);
    boolean tryReadLock(long address);
    boolean tryReadLock(long address, long time, TimeUnit unit);

    void updateLock(long address);
    void updateLockInterruptibly(long address);
    boolean tryUpdateLock(long address);
    boolean tryUpdateLock(long address, long time, TimeUnit unit);

    void writeLock(long address);
    void writeLockInterruptibly(long address);
    boolean tryWriteLock(long address);
    boolean tryWriteLock(long address, long time, TimeUnit unit);

    boolean tryUpgradeReadToUpdateLock(long address);
    boolean tryUpgradeReadToWriteLock(long address);

    void upgradeUpdateToWriteLock(long address);
    void upgradeUpdateToWriteLockInterruptibly(long address);
    boolean tryUpgradeUpdateToWriteLock(long address);
    boolean tryUpgradeUpdateToWriteLock(long address, long time, TimeUnit unit);

    void readUnlock(long address);

    void updateUnlock(long address);
    void downgradeUpdateToReadLock(long address);

    void writeUnlock(long address);
    void downgradeWriteToUpdateLock(long address);
    void downgradeWriteToReadLock(long address);
}
