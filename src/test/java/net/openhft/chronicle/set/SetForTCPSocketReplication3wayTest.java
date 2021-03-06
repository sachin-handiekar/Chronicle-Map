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

package net.openhft.chronicle.set;

import net.openhft.chronicle.hash.replication.TcpTransportAndNetworkConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.openhft.chronicle.set.Builder.getPersistenceFile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test Replicated Chronicle Set where the replication is over a TCP Socket
 *
 * @author Rob Austin.
 */
public class SetForTCPSocketReplication3wayTest {

    private ChronicleSet<Integer> set1;
    private ChronicleSet<Integer> set2;
    private ChronicleSet<Integer> set3;

    private static <T extends ChronicleSet<Integer>> T newTcpSocketIntSet(
            final byte identifier,
            final int serverPort,
            final InetSocketAddress... endpoints) throws IOException {
        return (T) ChronicleSetBuilder.of(Integer.class)
                .replication(identifier, TcpTransportAndNetworkConfig.of(serverPort, asList(endpoints))
                        .heartBeatInterval(1L, SECONDS).autoReconnectedUponDroppedConnection(true))
                .createPersistedTo(getPersistenceFile());
    }

    @Before
    public void setup() throws IOException {
        set1 = newTcpSocketIntSet((byte) 1, 18076, new InetSocketAddress("localhost", 18077),
                new InetSocketAddress("localhost", 18079));
        set2 = newTcpSocketIntSet((byte) 2, 18077, new InetSocketAddress("localhost", 18079));
        set3 = newTcpSocketIntSet((byte) 3, 18079);
    }

    @After
    public void tearDown() throws InterruptedException {

        for (final Closeable closeable : new Closeable[]{set1, set2, set3}) {
            try {
                closeable.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.gc();
    }

    @Test
    public void test3() throws IOException, InterruptedException {

        set3.add(5);

        // allow time for the recompilation to resolve
        waitTillEqual(15000);

        assertEquals(set1, set2);
        assertEquals(set2, set2);
        assertTrue(!set1.isEmpty());
    }

    @Test
    public void test() throws IOException, InterruptedException {

        set1.add(1);
        set1.add(2);
        set1.add(2);

        set2.add(5);
        set2.add(6);

        set1.remove(2);
        set2.remove(3);
        set1.remove(3);
        set2.add(5);

        // allow time for the recompilation to resolve
        waitTillEqual(5000);

        assertEquals(set1, set2);
        assertEquals(set2, set3);
        assertTrue(!set1.isEmpty());
    }

    @Test
    public void testClear() throws IOException, InterruptedException {

        set1.add(1);
        set1.add(2);
        set1.add(2);

        set2.add(5);
        set2.add(6);

        set1.clear();

        set2.add(5);

        Thread.sleep(100);

        // allow time for the recompilation to resolve
        waitTillEqual(15000);

        assertEquals(set1, set2);
        assertEquals(set2, set3);
        assertTrue(!set1.isEmpty());
    }

    private void waitTillEqual(final int timeOutMs) throws InterruptedException {
        int t = 0;
        for (; t < timeOutMs; t++) {
            if (set1.equals(set2) &&
                    set1.equals(set3) &&
                    set2.equals(set3))
                break;
            Thread.sleep(1);
        }
    }
}

