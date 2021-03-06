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

package net.openhft.chronicle.map;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Created by Borislav Ivanov on 5/29/15.
 */
public class ChronicleMapSanityCheckTest {

    enum DummyValue {
        DUMMY_VALUE
    }

    @Test
    public void testSanity1() throws IOException, InterruptedException
    {

        String tmp = System.getProperty("java.io.tmpdir");

        String pathname = tmp + "/testSanity1-" + UUID.randomUUID().toString() + ".dat";

        File file = new File(pathname);

        System.out.println("Starting sanity test 1. Chronicle file :" +
                file.getAbsolutePath().toString());

        ScheduledExecutorService producerExecutor =
                Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() - 1);

        ScheduledExecutorService consumerExecutor =
                Executors.newSingleThreadScheduledExecutor();

        int N = 1000;

        int producerPeriod = 100;
        TimeUnit producerTimeUnit = TimeUnit.MILLISECONDS;

        int consumerPeriod = 100;
        TimeUnit consumerTimeUnit = TimeUnit.MILLISECONDS;

        int totalTestTimeMS = (consumerPeriod + producerPeriod) * 20;

        try (ChronicleMap<String, DummyValue> map =
                     ChronicleMapBuilder.of(String.class, DummyValue.class)
                             .averageKey("" + N).averageValue(DummyValue.DUMMY_VALUE)
                             .entries(N)
                             .createPersistedTo(file)) {

            map.clear();

            producerExecutor.scheduleAtFixedRate(() -> {

                Thread.currentThread().setName("Producer " + Thread.currentThread().getId());
                Random r = new Random();

                System.out.println("Before PRODUCING size is " + map.size());
                for (int i = 0; i < N; i++) {
                    LockSupport.parkNanos(r.nextInt(5));
                    map.put(String.valueOf(i), DummyValue.DUMMY_VALUE);
                }
                System.out.println("After PRODUCING size is " + map.size());

            }, 0, producerPeriod, producerTimeUnit);

            consumerExecutor.scheduleAtFixedRate(() -> {

                Thread.currentThread().setName("Consumer");
                Set<String> keys = map.keySet();

                Random r = new Random();

                System.out.println("Before CONSUMING size is " + map.size());
                System.out.println();
                for (String key : keys) {
                    if (r.nextBoolean()) {
                        map.remove(key);
                    }
                }

                System.out.println("After CONSUMING size is " + map.size());

            }, 0, consumerPeriod, consumerTimeUnit);

            Thread.sleep(totalTestTimeMS);

            consumerExecutor.shutdown();
            try {
                consumerExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            producerExecutor.shutdown();
            try {
                producerExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }
}
