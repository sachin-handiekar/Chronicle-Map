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

import net.openhft.chronicle.bytes.Byteable;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.hash.ChronicleHashBuilder;
import net.openhft.chronicle.hash.ChronicleHashBuilderPrivateAPI;
import net.openhft.chronicle.hash.ChronicleHashInstanceBuilder;
import net.openhft.chronicle.hash.impl.stage.entry.ChecksumStrategy;
import net.openhft.chronicle.hash.impl.util.math.PoissonDistribution;
import net.openhft.chronicle.hash.replication.*;
import net.openhft.chronicle.hash.serialization.*;
import net.openhft.chronicle.hash.serialization.impl.SerializationBuilder;
import net.openhft.chronicle.map.replication.MapRemoteOperations;
import net.openhft.chronicle.set.ChronicleSetBuilder;
import net.openhft.chronicle.threads.NamedThreadFactory;
import net.openhft.chronicle.values.ValueModel;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Double.isNaN;
import static java.lang.Math.round;
import static net.openhft.chronicle.bytes.NativeBytesStore.lazyNativeBytesStoreWithFixedCapacity;
import static net.openhft.chronicle.core.Maths.*;
import static net.openhft.chronicle.hash.impl.CompactOffHeapLinearHashTable.*;
import static net.openhft.chronicle.hash.impl.util.Objects.builderEquals;
import static net.openhft.chronicle.map.DefaultSpi.mapEntryOperations;
import static net.openhft.chronicle.map.DefaultSpi.mapRemoteOperations;
import static net.openhft.chronicle.map.VanillaChronicleMap.alignAddr;

/**
 * {@code ChronicleMapBuilder} manages {@link ChronicleMap} configurations; could be used as a
 * classic builder and/or factory. This means that in addition to the standard builder usage
 * pattern: <pre>{@code
 * ChronicleMap<Key, Value> map = ChronicleMapOnHeapUpdatableBuilder
 *     .of(Key.class, Value.class)
 *     .entries(100500)
 *     // ... other configurations
 *     .create();}</pre>
 * it could be prepared and used to create many similar maps: <pre>{@code
 * ChronicleMapBuilder<Key, Value> builder = ChronicleMapBuilder
 *     .of(Key.class, Value.class)
 *     .entries(100500);
 *
 * ChronicleMap<Key, Value> map1 = builder.create();
 * ChronicleMap<Key, Value> map2 = builder.create();}</pre>
 * i. e. created {@code ChronicleMap} instances don't depend on the builder.
 *
 * <p>{@code ChronicleMapBuilder} is mutable, see a note in {@link ChronicleHashBuilder} interface
 * documentation.
 *
 * <p>Later in this documentation, "ChronicleMap" means "ChronicleMaps, created by {@code
 * ChronicleMapBuilder}", unless specified different, because theoretically someone might provide
 * {@code ChronicleMap} implementations with completely different properties.
 *
 * <p>{@code ChronicleMap} ("ChronicleMaps, created by {@code ChronicleMapBuilder}") currently
 * doesn't support resizing. That is why you <i>must</i> configure {@linkplain #entries(long) number
 * of entries} you are going to insert into the created map <i>at most</i>. See {@link
 * #entries(long)} method documentation for more information on this.
 *
 * <p>If you key or value type is not constantly sized and known to {@code ChronicleHashBuilder}, i.
 * e. it is not a boxed primitive, value interface, or {@link Byteable}, you <i>must</i> provide the
 * {@code ChronicleHashBuilder} with some information about you keys or values: if they are
 * constantly-sized, call {@link #constantKeySizeBySample(Object)}, otherwise {@link
 * ChronicleHashBuilder#averageKeySize(double)} method, accordingly for values.
 *
 * @param <K> key type of the maps, produced by this builder
 * @param <V> value type of the maps, produced by this builder
 * @see ChronicleMap
 * @see ChronicleSetBuilder
 */
public final class ChronicleMapBuilder<K, V> implements
        ChronicleHashBuilder<K, ChronicleMap<K, V>, ChronicleMapBuilder<K, V>> {

    static final byte UDP_REPLICATION_MODIFICATION_ITERATOR_ID = (byte) 127;
    private static final long DEFAULT_ENTRIES = 1 << 20;

    private static final int UNDEFINED_ALIGNMENT_CONFIG = -1;
    private static final int NO_ALIGNMENT = 1;

    /**
     * If want to increase this number, note {@link OldDeletedEntriesCleanup} uses array to store
     * all segment indexes -- so it could be current JVM max array size, not Integer.MAX_VALUE
     * (which is an obvious limitation, as many APIs and internals use int type for representing
     * segment index).
     *
     * Anyway, unlikely anyone ever need more than 1 billion segments.
     */
    private static final int MAX_SEGMENTS = (1 << 30);
    private static final Logger LOG =
            LoggerFactory.getLogger(ChronicleMapBuilder.class.getName());

    private static final double UNDEFINED_DOUBLE_CONFIG = Double.NaN;

    private static final int XML_SERIALIZATION = 1;
    private static final int BINARY_SERIALIZATION = 2;

    private static boolean isDefined(double config) {
        return !isNaN(config);
    }

    // not final because of cloning
    private ChronicleMapBuilderPrivateAPI<K> privateAPI = new ChronicleMapBuilderPrivateAPI<>(this);

    //////////////////////////////
    // Configuration fields

    SerializationBuilder<K> keyBuilder;
    SerializationBuilder<V> valueBuilder;

    // used when configuring the number of segments.
    private int minSegments = -1;
    private int actualSegments = -1;
    // used when reading the number of entries per
    private long entriesPerSegment = -1L;
    private long actualChunksPerSegment = -1L;
    private double averageKeySize = UNDEFINED_DOUBLE_CONFIG;
    private K averageKey;
    private K sampleKey;
    private double averageValueSize = UNDEFINED_DOUBLE_CONFIG;
    private V averageValue;
    private V sampleValue;
    private int actualChunkSize = 0;
    private int worstAlignment = -1;
    private int maxChunksPerEntry = -1;
    private int alignment = UNDEFINED_ALIGNMENT_CONFIG;
    private long entries = -1L;
    private double maxBloatFactor = 1.0;
    private boolean allowSegmentTiering = true;
    private double nonTieredSegmentsPercentile = 0.99999;
    private boolean aligned64BitMemoryOperationsAtomic = OS.is64Bit();

    enum ChecksumEntries {YES, NO, IF_PERSISTED}
    private ChecksumEntries checksumEntries = ChecksumEntries.IF_PERSISTED;

    private boolean putReturnsNull = false;
    private boolean removeReturnsNull = false;

    // replication
    private TimeProvider timeProvider = MicrosecondPrecisionSystemTimeProvider.instance();
    /**
     * Default timeout is 1 minute. Even loopback tests converge often in the course of seconds,
     * let alone WAN replication over many nodes might take tens of seconds.
     *
     * TODO review
     */
    long cleanupTimeout = 1;
    TimeUnit cleanupTimeoutUnit = TimeUnit.MINUTES;
    private boolean cleanupRemovedEntries = true;

    DefaultValueProvider<K, V> defaultValueProvider = DefaultSpi.defaultValueProvider();

    private SingleChronicleHashReplication singleHashReplication = null;

    MapMethods<K, V, ?> methods = DefaultSpi.mapMethods();
    MapEntryOperations<K, V, ?> entryOperations = mapEntryOperations();
    MapRemoteOperations<K, V, ?> remoteOperations = mapRemoteOperations();

    //////////////////////////////
    // Instance fields

    private boolean replicated;
    private boolean persisted;

    ChronicleMapBuilder(Class<K> keyClass, Class<V> valueClass) {
        keyBuilder = new SerializationBuilder<>(keyClass);
        valueBuilder = new SerializationBuilder<>(valueClass);
    }

    /**
     * Returns a new {@code ChronicleMapBuilder} instance which is able to {@linkplain #create()
     * create} maps with the specified key and value classes.
     *
     * @param keyClass   class object used to infer key type and discover it's properties via
     *                   reflection
     * @param valueClass class object used to infer value type and discover it's properties via
     *                   reflection
     * @param <K>        key type of the maps, created by the returned builder
     * @param <V>        value type of the maps, created by the returned builder
     * @return a new builder for the given key and value classes
     */
    public static <K, V> ChronicleMapBuilder<K, V> of(
            @NotNull Class<K> keyClass, @NotNull Class<V> valueClass) {
        return new ChronicleMapBuilder<>(keyClass, valueClass);
    }

    private static void checkSegments(long segments) {
        if (segments <= 0) {
            throw new IllegalArgumentException("segments should be positive, " +
                    segments + " given");
        }
        if (segments > MAX_SEGMENTS) {
            throw new IllegalArgumentException("Max segments is " + MAX_SEGMENTS + ", " +
                    segments + " given");
        }
    }

    private static String pretty(int value) {
        return value > 0 ? value + "" : "not configured";
    }

    private static String pretty(Object obj) {
        return obj != null ? obj + "" : "not configured";
    }

    @Override
    public ChronicleMapBuilder<K, V> clone() {
        try {
            @SuppressWarnings("unchecked")
            ChronicleMapBuilder<K, V> result =
                    (ChronicleMapBuilder<K, V>) super.clone();
            result.keyBuilder = keyBuilder.clone();
            result.valueBuilder = valueBuilder.clone();
            result.privateAPI = new ChronicleMapBuilderPrivateAPI<>(result);
            return result;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * @deprecated don't use private API in the client code
     */
    @Override
    public ChronicleHashBuilderPrivateAPI<K> privateAPI() {
        return privateAPI;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Example: if keys in your map(s) are English words in {@link String} form, average English
     * word length is 5.1, configure average key size of 6: <pre>{@code
     * ChronicleMap<String, LongValue> wordFrequencies = ChronicleMapBuilder
     *     .of(String.class, LongValue.class)
     *     .entries(50000)
     *     .averageKeySize(6)
     *     .create();}</pre>
     * (Note that 6 is chosen as average key size in bytes despite strings in Java are UTF-16
     * encoded (and each character takes 2 bytes on-heap), because default off-heap {@link String}
     * encoding is UTF-8 in {@code ChronicleMap}.)
     *
     * @param averageKeySize  the average size of the key
     * @throws IllegalStateException    {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @see #averageKey(Object)
     * @see #constantKeySizeBySample(Object)
     * @see #averageValueSize(double)
     * @see #actualChunkSize(int)
     */
    @Override
    public ChronicleMapBuilder<K, V> averageKeySize(double averageKeySize) {
        checkSizeIsNotStaticallyKnown(keyBuilder);
        checkAverageSize(averageKeySize, "key");
        this.averageKeySize = averageKeySize;
        averageKey = null;
        sampleKey = null;
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * @param averageKey the average (by footprint in serialized form) key, is going to be put
     *                   into the hash containers, created by this builder
     * @throws NullPointerException {@inheritDoc}
     * @see #averageKeySize(double)
     * @see #constantKeySizeBySample(Object)
     * @see #averageValue(Object)
     * @see #actualChunkSize(int)
     */
    @Override
    public ChronicleMapBuilder<K, V> averageKey(K averageKey) {
        checkSizeIsNotStaticallyKnown(keyBuilder);
        this.averageKey = averageKey;
        sampleKey = null;
        averageKeySize = UNDEFINED_DOUBLE_CONFIG;
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For example, if your keys are Git commit hashes:<pre>{@code
     * Map<byte[], String> gitCommitMessagesByHash =
     *     ChronicleMapBuilder.of(byte[].class, String.class)
     *     .constantKeySizeBySample(new byte[20])
     *     .create();}</pre>
     *
     * @see #averageKeySize(double)
     * @see #averageKey(Object)
     * @see #constantValueSizeBySample(Object)
     */
    @Override
    public ChronicleMapBuilder<K, V> constantKeySizeBySample(K sampleKey) {
        this.sampleKey = sampleKey;
        averageKey = null;
        averageKeySize = UNDEFINED_DOUBLE_CONFIG;
        return this;
    }

    private double averageKeySize() {
        if (!isDefined(averageKeySize))
            throw new AssertionError();
        return averageKeySize;
    }

    /**
     * Configures the average number of bytes, taken by serialized form of values, put into maps,
     * created by this builder. However, in many cases {@link #averageValue(Object)} might be easier
     * to use and more reliable. If value size is always the same, call {@link
     * #constantValueSizeBySample(Object)} method instead of this one.
     *
     * <p>{@code ChronicleHashBuilder} implementation heuristically chooses {@linkplain
     * #actualChunkSize(int) the actual chunk size} based on this configuration and the key size,
     * that, however, might result to quite high internal fragmentation, i. e. losses because only
     * integral number of chunks could be allocated for the entry. If you want to avoid this, you
     * should manually configure the actual chunk size in addition to this average value size
     * configuration, which is anyway needed.
     *
     * <p>If values are of boxed primitive type or {@link Byteable} subclass, i. e. if value size is
     * known statically, it is automatically accounted and shouldn't be specified by user.
     *
     * <p>Calling this method clears any previous {@link #constantValueSizeBySample(Object)} and
     * {@link #averageValue(Object)} configurations.
     *
     * @param averageValueSize number of bytes, taken by serialized form of values
     * @return this builder back
     * @throws IllegalStateException    if value size is known statically and shouldn't be
     *                                  configured by user
     * @throws IllegalArgumentException if the given {@code averageValueSize} is non-positive
     * @see #averageValue(Object)
     * @see #constantValueSizeBySample(Object)
     * @see #averageKeySize(double)
     * @see #actualChunkSize(int)
     */
    public ChronicleMapBuilder<K, V> averageValueSize(double averageValueSize) {
        checkSizeIsNotStaticallyKnown(valueBuilder);
        checkAverageSize(averageValueSize, "value");
        this.averageValueSize = averageValueSize;
        averageValue = null;
        sampleValue = null;
        return this;
    }

    /**
     * Configures the average number of bytes, taken by serialized form of values, put into maps,
     * created by this builder, by serializing the given {@code averageValue} using the configured
     * {@link #valueMarshallers(SizedReader, SizedWriter) value marshallers}. In some cases, {@link
     * #averageValueSize(double)} might be easier to use, than constructing the "average value".
     * If value size is always the same, call {@link #constantValueSizeBySample(Object)} method
     * instead of this one.
     *
     * <p>Example: If you
     *
     * <p>{@code ChronicleHashBuilder} implementation heuristically chooses {@linkplain
     * #actualChunkSize(int) the actual chunk size} based on this configuration and the key size,
     * that, however, might result to quite high internal fragmentation, i. e. losses because only
     * integral number of chunks could be allocated for the entry. If you want to avoid this, you
     * should manually configure the actual chunk size in addition to this average value size
     * configuration, which is anyway needed.
     *
     * <p>If values are of boxed primitive type or {@link Byteable} subclass, i. e. if value size is
     * known statically, it is automatically accounted and shouldn't be specified by user.
     *
     * <p>Calling this method clears any previous {@link #constantValueSizeBySample(Object)}
     * and {@link #averageValueSize(double)} configurations.
     *
     * @param averageValue the average (by footprint in serialized form) value, is going to be put
     *                     into the maps, created by this builder
     * @return this builder back
     * @throws NullPointerException if the given {@code averageValue} is {@code null}
     * @see #averageValueSize(double)
     * @see #constantValueSizeBySample(Object)
     * @see #averageKey(Object)
     * @see #actualChunkSize(int)
     */
    public ChronicleMapBuilder<K, V> averageValue(V averageValue) {
        Objects.requireNonNull(averageValue);
        checkSizeIsNotStaticallyKnown(valueBuilder);
        this.averageValue = averageValue;
        sampleValue = null;
        averageValueSize = UNDEFINED_DOUBLE_CONFIG;
        return this;
    }

    private static void checkAverageSize(double averageSize, String role) {
        if (averageSize <= 0 || isNaN(averageSize) ||
                Double.isInfinite(averageSize)) {
            throw new IllegalArgumentException("Average " + role + " size must be a positive, " +
                    "finite number");
        }
    }

    private static void checkSizeIsNotStaticallyKnown(SerializationBuilder builder) {
        if (builder.sizeIsStaticallyKnown)
            throw new IllegalStateException("Size of type " + builder.tClass +
                    " is statically known and shouldn't be specified manually");
    }

    /**
     * Configures the constant number of bytes, taken by serialized form of values, put into maps,
     * created by this builder. This is done by providing the {@code sampleValue}, all values should
     * take the same number of bytes in serialized form, as this sample object.
     *
     * <p>If values are of boxed primitive type or {@link Byteable} subclass, i. e. if value size is
     * known statically, it is automatically accounted and this method shouldn't be called.
     *
     * <p>If value size varies, method {@link #averageValue(Object)} or {@link
     * #averageValueSize(double)} should be called instead of this one.
     *
     * <p>Calling this method clears any previous {@link #averageValue(Object)} and
     * {@link #averageValueSize(double)} configurations.
     *
     * @param sampleValue the sample value
     * @return this builder back
     * @see #averageValueSize(double)
     * @see #averageValue(Object)
     * @see #constantKeySizeBySample(Object)
     */
    public ChronicleMapBuilder<K, V> constantValueSizeBySample(V sampleValue) {
        this.sampleValue = sampleValue;
        averageValue = null;
        averageValueSize = UNDEFINED_DOUBLE_CONFIG;
        return this;
    }

    double averageValueSize() {
        if (!isDefined(averageValueSize))
            throw new AssertionError();
        return averageValueSize;
    }

    private <E> double averageKeyOrValueSize(
            double configuredSize, SerializationBuilder<E> builder, E average) {
        if (isDefined(configuredSize))
            return configuredSize;
        if (builder.constantSizeMarshaller())
            return builder.constantSize();
        if (average != null) {
            return builder.serializationSize(average);
        }
        return Double.NaN;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException is sizes of both keys and values of maps created by this
     * builder are constant, hence chunk size shouldn't be configured by user
     * @see #entryAndValueOffsetAlignment(int)
     * @see #entries(long)
     * @see #maxChunksPerEntry(int)
     */
    @Override
    public ChronicleMapBuilder<K, V> actualChunkSize(int actualChunkSize) {
        if (constantlySizedEntries()) {
            throw new IllegalStateException("Sizes of key type: " + keyBuilder.tClass + " and " +
                    "value type: " + valueBuilder.tClass + " are both constant, " +
                    "so chunk size shouldn't be specified manually");
        }
        if (actualChunkSize <= 0)
            throw new IllegalArgumentException("Chunk size must be positive");
        this.actualChunkSize = actualChunkSize;
        return this;
    }

    SerializationBuilder<K> keyBuilder() {
        return keyBuilder;
    }

    static class EntrySizeInfo {
        final double averageEntrySize;
        final int worstAlignment;

        public EntrySizeInfo(double averageEntrySize, int worstAlignment) {
            this.averageEntrySize = averageEntrySize;
            this.worstAlignment = worstAlignment;
        }
    }

    private EntrySizeInfo entrySizeInfo() {
        double size = 0;
        double keySize = averageKeySize();
        size += averageSizeStoringLength(keyBuilder, keySize);
        size += keySize;
        if (replicated)
            size += ReplicatedChronicleMap.ADDITIONAL_ENTRY_BYTES;
        if (checksumEntries())
            size += ChecksumStrategy.CHECKSUM_STORED_BYTES;
        double valueSize = averageValueSize();
        size += averageSizeStoringLength(valueBuilder, valueSize);
        int alignment = valueAlignment();
        int worstAlignment;
        if (worstAlignmentComputationRequiresValueSize(alignment)) {
            long constantSizeBeforeAlignment = round(size);
            if (constantlySizedValues()) {
                // see segmentEntrySpaceInnerOffset()
                long totalDataSize = constantSizeBeforeAlignment + constantValueSize();
                worstAlignment = (int) (alignAddr(totalDataSize, alignment) - totalDataSize);
            } else {
                determineAlignment:
                if (actualChunkSize > 0) {
                    worstAlignment = worstAlignmentAssumingChunkSize(constantSizeBeforeAlignment,
                            actualChunkSize);
                } else {
                    int chunkSize = 8;
                    worstAlignment = worstAlignmentAssumingChunkSize(
                            constantSizeBeforeAlignment, chunkSize);
                    if (size + worstAlignment + valueSize >=
                            maxDefaultChunksPerAverageEntry(replicated) * chunkSize) {
                        break determineAlignment;
                    }
                    chunkSize = 4;
                    worstAlignment = worstAlignmentAssumingChunkSize(
                            constantSizeBeforeAlignment, chunkSize);
                }
            }
        } else {
            // assume worst case, we always lose most possible bytes for alignment
            worstAlignment = worstAlignmentWithoutValueSize(alignment);
        }
        size += worstAlignment;
        size += valueSize;
        return new EntrySizeInfo(size, worstAlignment);
    }

    private boolean worstAlignmentComputationRequiresValueSize(int alignment) {
        return alignment != NO_ALIGNMENT &&
                constantlySizedKeys() && valueBuilder.constantStoringLengthSizeMarshaller();
    }

    private int worstAlignmentWithoutValueSize(int alignment) {
        return alignment - 1;
    }

    int segmentEntrySpaceInnerOffset() {
        // This is needed, if chunkSize = constant entry size is not aligned, for entry alignment
        // to be always the same, we should _misalign_ the first chunk.
        if (!constantlySizedEntries())
            return 0;
        return (int) (constantValueSize() % valueAlignment());
    }

    private long constantValueSize() {
        return valueBuilder.constantSize();
    }

    boolean constantlySizedKeys() {
        return keyBuilder.constantSizeMarshaller() || sampleKey != null;
    }

    private static double averageSizeStoringLength(
            SerializationBuilder builder, double averageSize) {
        SizeMarshaller sizeMarshaller = builder.sizeMarshaller();
        if (averageSize == round(averageSize))
            return sizeMarshaller.storingLength(round(averageSize));
        long lower = (long) averageSize;
        long upper = lower + 1;
        int lowerStoringLength = sizeMarshaller.storingLength(lower);
        int upperStoringLength = sizeMarshaller.storingLength(upper);
        if (lowerStoringLength == upperStoringLength)
            return lowerStoringLength;
        return lower * (upper - averageSize) + upper * (averageSize - lower);
    }

    private int worstAlignmentAssumingChunkSize(
            long constantSizeBeforeAlignment, int chunkSize) {
        int alignment = valueAlignment();
        long firstAlignment = alignAddr(constantSizeBeforeAlignment, alignment) -
                constantSizeBeforeAlignment;
        int gcdOfAlignmentAndChunkSize = greatestCommonDivisor(alignment, chunkSize);
        if (gcdOfAlignmentAndChunkSize == alignment)
            return (int) firstAlignment;
        // assume worst by now because we cannot predict alignment in VanillaCM.entrySize() method
        // before allocation
        long worstAlignment = firstAlignment;
        while (worstAlignment + gcdOfAlignmentAndChunkSize < alignment)
            worstAlignment += gcdOfAlignmentAndChunkSize;
        return (int) worstAlignment;
    }

    int worstAlignment() {
        if (worstAlignment >= 0)
            return worstAlignment;
        int alignment = valueAlignment();
        if (!worstAlignmentComputationRequiresValueSize(alignment))
            return worstAlignment = worstAlignmentWithoutValueSize(alignment);
        return worstAlignment = entrySizeInfo().worstAlignment;
    }

    void worstAlignment(int worstAlignment) {
        assert worstAlignment >= 0;
        this.worstAlignment = worstAlignment;
    }

    static int greatestCommonDivisor(int a, int b) {
        if (b == 0) return a;
        return greatestCommonDivisor(b, a % b);
    }

    long chunkSize() {
        if (actualChunkSize > 0)
            return actualChunkSize;
        double averageEntrySize = entrySizeInfo().averageEntrySize;
        if (constantlySizedEntries())
            return round(averageEntrySize);
        int maxChunkSize = 1 << 30;
        for (long chunkSize = 4; chunkSize <= maxChunkSize; chunkSize *= 2L) {
            if (maxDefaultChunksPerAverageEntry(replicated) * chunkSize > averageEntrySize)
                return chunkSize;
        }
        return maxChunkSize;
    }

    boolean constantlySizedEntries() {
        return constantlySizedKeys() && constantlySizedValues();
    }

    double averageChunksPerEntry() {
        if (constantlySizedEntries())
            return 1.0;
        long chunkSize = chunkSize();
        // assuming we always has worst internal fragmentation. This affects total segment
        // entry space which is allocated lazily on Linux (main target platform)
        // so we can afford this
        return (entrySizeInfo().averageEntrySize + chunkSize - 1) / chunkSize;
    }

    private static int maxDefaultChunksPerAverageEntry(boolean replicated) {
        // When replicated, having 8 chunks (=> 8 bits in bitsets) per entry seems more wasteful
        // because when replicated we have bit sets per each remote node, not only allocation
        // bit set as when non-replicated
        return replicated ? 4 : 8;
    }

    @Override
    public ChronicleMapBuilder<K, V> maxChunksPerEntry(int maxChunksPerEntry) {
        if (maxChunksPerEntry < 1)
            throw new IllegalArgumentException("maxChunksPerEntry should be >= 1, " +
                    maxChunksPerEntry + " given");
        this.maxChunksPerEntry = maxChunksPerEntry;
        return this;
    }

    int maxChunksPerEntry() {
        if (constantlySizedEntries())
            return 1;
        long actualChunksPerSegment = actualChunksPerSegment();
        int result = (int) Math.min(actualChunksPerSegment, (long) Integer.MAX_VALUE);
        if (this.maxChunksPerEntry > 0)
            result = Math.min(this.maxChunksPerEntry, result);
        return result;
    }

    boolean constantlySizedValues() {
        return valueBuilder.constantSizeMarshaller() || sampleValue != null;
    }

    /**
     * Configures alignment of address in memory of entries and independently of address in memory
     * of values within entries ((i. e. final addresses in native memory are multiples of the given
     * alignment) for ChronicleMaps, created by this builder.
     *
     * <p>Useful when values of the map are updated intensively, particularly fields with volatile
     * access, because it doesn't work well if the value crosses cache lines. Also, on some
     * (nowadays rare) architectures any misaligned memory access is more expensive than aligned.
     *
     * <p>If values couldn't reference off-heap memory (i. e. it is not {@link Byteable} or a value
     * interface), alignment configuration makes no sense.
     *
     * <p>Default is {@link ValueModel#recommendedOffsetAlignment()} if the value type is a value
     * interface, otherwise 1 (that is effectively no alignment) or chosen heuristically (configure
     * explicitly for being sure and to compare performance in your case).
     *
     * @param alignment the new alignment of the maps constructed by this builder
     * @return this {@code ChronicleMapOnHeapUpdatableBuilder} back
     * @throws IllegalStateException if values of maps, created by this builder, couldn't reference
     *                               off-heap memory
     */
    public ChronicleMapBuilder<K, V> entryAndValueOffsetAlignment(int alignment) {
        if (alignment <= 0) {
            throw new IllegalArgumentException("Alignment should be positive integer, " +
                    alignment + " given");
        }
        if (!isPowerOf2(alignment)) {
            throw new IllegalArgumentException("Alignment should be a power of 2, " + alignment +
                    " given");
        }
        this.alignment = alignment;
        return this;
    }

    int valueAlignment() {
        if (alignment != UNDEFINED_ALIGNMENT_CONFIG)
            return alignment;
        try {
            return ValueModel.acquire(valueBuilder.tClass).recommendedOffsetAlignment();
        } catch (Exception e) {
            return NO_ALIGNMENT;
        }
    }

    @Override
    public ChronicleMapBuilder<K, V> entries(long entries) {
        if (entries <= 0L)
            throw new IllegalArgumentException("Entries should be positive, " + entries + " given");
        this.entries = entries;
        return this;
    }

    long entries() {
        if (entries < 0)
            return DEFAULT_ENTRIES;
        return entries;
    }

    @Override
    public ChronicleMapBuilder<K, V> entriesPerSegment(long entriesPerSegment) {
        if (entriesPerSegment <= 0L)
            throw new IllegalArgumentException("Entries per segment should be positive, " +
                    entriesPerSegment + " given");
        this.entriesPerSegment = entriesPerSegment;
        return this;
    }

    long entriesPerSegment() {
        long entriesPerSegment;
        if (this.entriesPerSegment > 0L) {
            entriesPerSegment = this.entriesPerSegment;
        } else {
            int actualSegments = actualSegments();
            double averageEntriesPerSegment = entries() * 1.0 / actualSegments;
            entriesPerSegment = PoissonDistribution.inverseCumulativeProbability(
                    averageEntriesPerSegment, nonTieredSegmentsPercentile);
        }
        boolean actualChunksDefined = actualChunksPerSegment > 0;
        if (!actualChunksDefined) {
            double averageChunksPerEntry = averageChunksPerEntry();
            if (entriesPerSegment * averageChunksPerEntry >
                    MAX_SEGMENT_CHUNKS)
                throw new IllegalStateException("Max chunks per segment is " +
                        MAX_SEGMENT_CHUNKS +
                        " configured entries() and " +
                        "actualSegments() so that there should be " + entriesPerSegment +
                        " entries per segment, while average chunks per entry is " +
                        averageChunksPerEntry);
        }
        if (entriesPerSegment > MAX_SEGMENT_ENTRIES)
            throw new IllegalStateException("shouldn't be more than " +
                    MAX_SEGMENT_ENTRIES + " entries per segment");
        return entriesPerSegment;
    }

    @Override
    public ChronicleMapBuilder<K, V> actualChunksPerSegment(long actualChunksPerSegment) {
        if (actualChunksPerSegment <= 0)
            throw new IllegalArgumentException("Actual chunks per segment should be positive, " +
                    actualChunksPerSegment + " given");
        this.actualChunksPerSegment = actualChunksPerSegment;
        return this;
    }

    private void checkActualChunksPerSegmentIsConfiguredOnlyIfOtherLowLevelConfigsAreManual() {
        if (actualChunksPerSegment > 0) {
            if (entriesPerSegment <= 0 || (actualChunkSize <= 0 && !constantlySizedEntries()) ||
                    actualSegments <= 0)
                throw new IllegalStateException("Actual chunks per segment could be configured " +
                        "only if other three low level configs are manual: " +
                        "entriesPerSegment(), actualSegments() and actualChunkSize(), unless " +
                        "both keys and value sizes are constant");
        }
    }

    private void checkActualChunksPerSegmentGreaterOrEqualToEntries() {
        if (actualChunksPerSegment > 0 && entriesPerSegment > 0 &&
                entriesPerSegment > actualChunksPerSegment) {
            throw new IllegalStateException("Entries per segment couldn't be greater than " +
                    "actual chunks per segment. Entries: " + entriesPerSegment + ", " +
                    "chunks: " + actualChunksPerSegment + " is configured");
        }
    }

    long actualChunksPerSegment() {
        if (actualChunksPerSegment > 0)
            return actualChunksPerSegment;
        return chunksPerSegment(entriesPerSegment());
    }

    private long chunksPerSegment(long entriesPerSegment) {
        return round(entriesPerSegment * averageChunksPerEntry());
    }

    @Override
    public ChronicleMapBuilder<K, V> minSegments(int minSegments) {
        checkSegments(minSegments);
        this.minSegments = minSegments;
        return this;
    }

    int minSegments() {
        return Math.max(estimateSegments(), minSegments);
    }

    private int estimateSegments() {
        return (int) Math.min(nextPower2(entries() / 32, 1), estimateSegmentsBasedOnSize());
    }

    //TODO review because this heuristic doesn't seem to perform well
    private int estimateSegmentsBasedOnSize() {
        // the idea is that if values are huge, operations on them (and simply ser/deser)
        // could take long time, so we want more segment to minimize probablity that
        // two or more concurrent write ops will go to the same segment, and then all but one of
        // these threads will wait for long time.
        int segmentsForEntries = estimateSegmentsForEntries(entries());
        double averageValueSize = averageValueSize();
        return averageValueSize >= 1000000
                ? segmentsForEntries * 16
                : averageValueSize >= 100000
                ? segmentsForEntries * 8
                : averageValueSize >= 10000
                ? segmentsForEntries * 4
                : averageValueSize >= 1000
                ? segmentsForEntries * 2
                : segmentsForEntries;
    }

    private static int estimateSegmentsForEntries(long size) {
        if (size > 200 << 20)
            return 256;
        if (size >= 1 << 20)
            return 128;
        if (size >= 128 << 10)
            return 64;
        if (size >= 16 << 10)
            return 32;
        if (size >= 4 << 10)
            return 16;
        if (size >= 1 << 10)
            return 8;
        return 1;
    }

    @Override
    public ChronicleMapBuilder<K, V> actualSegments(int actualSegments) {
        checkSegments(actualSegments);
        this.actualSegments = actualSegments;
        return this;
    }

    int actualSegments() {
        if (actualSegments > 0)
            return actualSegments;
        if (entriesPerSegment > 0) {
            return (int) segmentsGivenEntriesPerSegmentFixed(entriesPerSegment);
        }
        // Try to fit 4 bytes per hash lookup slot, then 8. Trying to apply small slot
        // size (=> segment size, because slot size depends on segment size) not only because
        // they take less memory per entry (if entries are of KBs or MBs, it doesn't matter), but
        // also because if segment size is small, slot and free list are likely to lie on a single
        // memory page, reducing number of memory pages to update, if Chronicle Map is persisted.
        // Actually small segments are all ways better: many segments => better parallelism, lesser
        // pauses for per-key operations, if parallel/background operation blocks the segment for
        // the whole time while it operates on it (like iteration, probably replication background
        // thread will require some level of full segment lock, however currently if doesn't, in
        // future durability background thread could update slot states), because smaller segments
        // contain less entries/slots and are processed faster.
        //
        // The only problem with small segments is that due to probability theory, if there are
        // a lot of segments each of little number of entries, difference between most filled
        // and least filled segment in the Chronicle Map grows. (Number of entries in a segment is
        // Poisson-distributed with mean = average number of entries per segment.) It is meaningful,
        // because segment tiering is exceptional mechanism, only very few segments should be
        // tiered, if any, normally. So, we are required to allocate unnecessarily many entries per
        // each segment. To compensate this at least on linux, don't accept segment sizes that with
        // the given entry sizes, lead to too small total segment sizes in native memory pages,
        // see comment in tryHashLookupSlotSize()
        long segments = tryHashLookupSlotSize(4);
        if (segments > 0)
            return (int) segments;
        int maxHashLookupEntrySize = aligned64BitMemoryOperationsAtomic() ? 8 : 4;
        long maxEntriesPerSegment =
                findMaxEntriesPerSegmentToFitHashLookupSlotSize(maxHashLookupEntrySize);
        long maxSegments = trySegments(maxEntriesPerSegment, MAX_SEGMENTS);
        if (maxSegments > 0L)
            return (int) maxSegments;
        throw new IllegalStateException("Max segments is " + MAX_SEGMENTS + ", configured so much" +
                " entries (" + entries() + ") or average chunks per entry is too high (" +
                averageChunksPerEntry() + ") that builder automatically decided to use " +
                (-maxSegments) + " segments");
    }

    private long tryHashLookupSlotSize(int hashLookupSlotSize) {
        long entriesPerSegment = findMaxEntriesPerSegmentToFitHashLookupSlotSize(
                hashLookupSlotSize);
        long entrySpaceSize = round(entriesPerSegment * entrySizeInfo().averageEntrySize);
        // Not to lose too much on linux because of "poor distribution" entry over-allocation.
        // This condition should likely filter cases when we target very small hash lookup
        // size + entry size is small.
        // * 5 => segment will lose not more than 20% of memory, 10% on average
        if (entrySpaceSize < OS.pageSize() * 5L)
            return -1;
        return trySegments(entriesPerSegment, MAX_SEGMENTS);
    }

    private long findMaxEntriesPerSegmentToFitHashLookupSlotSize(
            int targetHashLookupSlotSize) {
        long entriesPerSegment = 1L << 62;
        long step = entriesPerSegment / 2L;
        while (step > 0L) {
            if (hashLookupSlotBytes(entriesPerSegment) > targetHashLookupSlotSize)
                entriesPerSegment -= step;
            step /= 2L;
        }
        return entriesPerSegment - 1L;
    }

    private int hashLookupSlotBytes(long entriesPerSegment) {
        int valueBits = valueBits(chunksPerSegment(entriesPerSegment));
        int keyBits = keyBits(entriesPerSegment, valueBits);
        return entrySize(keyBits, valueBits);
    }

    private long trySegments(long entriesPerSegment, int maxSegments) {
        long segments = segmentsGivenEntriesPerSegmentFixed(entriesPerSegment);
        segments = nextPower2(Math.max(segments, minSegments()), 1L);
        return segments <= maxSegments ? segments : -segments;
    }

    private long segmentsGivenEntriesPerSegmentFixed(long entriesPerSegment) {
        double precision = 1.0 / averageChunksPerEntry();
        double entriesPerSegmentShouldBe =
                PoissonDistribution.meanByCumulativeProbabilityAndValue(
                        nonTieredSegmentsPercentile, entriesPerSegment, precision);
        long segments = ((long) (entries() / entriesPerSegmentShouldBe)) + 1;
        checkSegments(segments);
        if (minSegments > 0)
            segments = Math.max(minSegments, segments);
        return segments;
    }

    int segmentHeaderSize() {
        int segments = actualSegments();

        long pageSize = 4096;
        if (segments * (64 * 3) < (2 * pageSize)) // i. e. <= 42 segments
            return 64 * 3; // cache line per header, plus one CL to the left, plus one to the right

        if (segments * (64 * 2) < (3 * pageSize)) // i. e. <= 96 segments
            return 64 * 2;

        // reduce false sharing unless we have a lot of segments.
        return segments <= 16 * 1024 ? 64 : 32;
    }

    /**
     * Configures if the maps created by this {@code ChronicleMapBuilder} should return {@code null}
     * instead of previous mapped values on {@link ChronicleMap#put(Object, Object)
     * ChornicleMap.put(key, value)} calls.
     *
     * <p>{@link Map#put(Object, Object) Map.put()} returns the previous value, functionality
     * which is rarely used but fairly cheap for simple in-process, on-heap implementations like
     * {@link HashMap}. But an off-heap collection has to create a new object and deserialize
     * the data from off-heap memory. A collection hiding remote queries over the network should
     * send the value back in addition to that. It's expensive for something you probably don't use.
     *
     * <p>By default, of cause, {@code ChronicleMap} conforms the general {@code Map} contract and
     * returns the previous mapped value on {@code put()} calls.
     *
     * @param putReturnsNull {@code true} if you want {@link ChronicleMap#put(Object, Object)
     *                       ChronicleMap.put()} to not return the value that was replaced but
     *                       instead return {@code null}
     * @return this builder back
     * @see #removeReturnsNull(boolean)
     */
    public ChronicleMapBuilder<K, V> putReturnsNull(boolean putReturnsNull) {
        this.putReturnsNull = putReturnsNull;
        return this;
    }

    boolean putReturnsNull() {
        return putReturnsNull;
    }

    /**
     * Configures if the maps created by this {@code ChronicleMapBuilder} should return {@code null}
     * instead of the last mapped value on {@link ChronicleMap#remove(Object)
     * ChronicleMap.remove(key)} calls.
     *
     * <p>{@link Map#remove(Object) Map.remove()} returns the previous value, functionality which is
     * rarely used but fairly cheap for simple in-process, on-heap implementations like {@link
     * HashMap}. But an off-heap collection has to create a new object and deserialize the data
     * from off-heap memory. A collection hiding remote queries over the network should send
     * the value back in addition to that. It's expensive for something you probably don't use.
     *
     * <p>By default, of cause, {@code ChronicleMap} conforms the general {@code Map} contract and
     * returns the mapped value on {@code remove()} calls.
     *
     * @param removeReturnsNull {@code true} if you want {@link ChronicleMap#remove(Object)
     *                          ChronicleMap.remove()} to not return the value of the removed entry
     *                          but instead return {@code null}
     * @return this builder back
     * @see #putReturnsNull(boolean)
     */
    public ChronicleMapBuilder<K, V> removeReturnsNull(boolean removeReturnsNull) {
        this.removeReturnsNull = removeReturnsNull;
        return this;
    }

    boolean removeReturnsNull() {
        return removeReturnsNull;
    }

    @Override
    public ChronicleMapBuilder<K, V> maxBloatFactor(double maxBloatFactor) {
        if (isNaN(maxBloatFactor) || maxBloatFactor < 1.0 || maxBloatFactor > 1_000.0) {
            throw new IllegalArgumentException("maxBloatFactor should be in [1.0, 1_000.0] " +
                    "bounds, " + maxBloatFactor + " given");
        }
        this.maxBloatFactor = maxBloatFactor;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> allowSegmentTiering(boolean allowSegmentTiering) {
        this.allowSegmentTiering = allowSegmentTiering;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> nonTieredSegmentsPercentile(
            double nonTieredSegmentsPercentile) {
        if (isNaN(nonTieredSegmentsPercentile) ||
                0.5 <= nonTieredSegmentsPercentile || nonTieredSegmentsPercentile >= 1.0) {
            throw new IllegalArgumentException("nonTieredSegmentsPercentile should be in (0.5, " +
                    "1.0) range, " + nonTieredSegmentsPercentile + " is given");
        }
        this.nonTieredSegmentsPercentile = nonTieredSegmentsPercentile;
        return this;
    }

    long maxExtraTiers() {
        if (!allowSegmentTiering)
            return 0;
        int actualSegments = actualSegments();
        // maxBloatFactor is scale, so we do (- 1.0) to compute _extra_ tiers
        return ((long) (maxBloatFactor - 1.0) * actualSegments)
                // but to mitigate slight misconfiguration, and uneven distribution of entries
                // between segments, add 1.0 x actualSegments
                + actualSegments;
    }

    @Override
    public String toString() {
        return "ChronicleMapBuilder{" +
                ", actualSegments=" + pretty(actualSegments) +
                ", minSegments=" + pretty(minSegments) +
                ", entriesPerSegment=" + pretty(entriesPerSegment) +
                ", actualChunksPerSegment=" + pretty(actualChunksPerSegment) +
                ", averageKeySize=" + pretty(averageKeySize) +
                ", sampleKeyForConstantSizeComputation=" + pretty(sampleKey) +
                ", averageValueSize=" + pretty(averageValueSize) +
                ", sampleValueForConstantSizeComputation=" + pretty(sampleValue) +
                ", actualChunkSize=" + pretty(actualChunkSize) +
                ", valueAlignment=" + valueAlignment() +
                ", entries=" + entries() +
                ", putReturnsNull=" + putReturnsNull() +
                ", removeReturnsNull=" + removeReturnsNull() +
                ", timeProvider=" + timeProvider() +
                ", keyBuilder=" + keyBuilder +
                ", valueBuilder=" + valueBuilder +
                '}';
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(Object o) {
        return builderEquals(this, o);
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public ChronicleMapBuilder<K, V> timeProvider(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> removedEntryCleanupTimeout(
            long removedEntryCleanupTimeout, TimeUnit unit) {
        if (unit.toMillis(removedEntryCleanupTimeout) < 1) {
            throw new IllegalArgumentException("timeout should be >= 1 millisecond, " +
                    removedEntryCleanupTimeout + " " + unit + " is given");
        }
        cleanupTimeout = removedEntryCleanupTimeout;
        cleanupTimeoutUnit = unit;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> cleanupRemovedEntries(boolean cleanupRemovedEntries) {
        this.cleanupRemovedEntries = cleanupRemovedEntries;
        return this;
    }

    TimeProvider timeProvider() {
        return timeProvider;
    }

    @Override
    public ChronicleMapBuilder<K, V> keyReaderAndDataAccess(
            SizedReader<K> keyReader, @NotNull DataAccess<K> keyDataAccess) {
        keyBuilder.reader(keyReader);
        keyBuilder.dataAccess(keyDataAccess);
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> keyMarshallers(
            @NotNull SizedReader<K> keyReader, @NotNull SizedWriter<? super K> keyWriter) {
        keyBuilder.reader(keyReader);
        keyBuilder.writer(keyWriter);
        return this;
    }

    @Override
    public <M extends SizedReader<K> & SizedWriter<? super K>>
    ChronicleMapBuilder<K, V> keyMarshaller(@NotNull  M sizedMarshaller) {
        return keyMarshallers(sizedMarshaller, sizedMarshaller);
    }

    @Override
    public ChronicleMapBuilder<K, V> keyMarshallers(
            @NotNull BytesReader<K> keyReader, @NotNull BytesWriter<? super K> keyWriter) {
        keyBuilder.reader(keyReader);
        keyBuilder.writer(keyWriter);
        return this;
    }

    @Override
    public <M extends BytesReader<K> & BytesWriter<? super K>>
    ChronicleMapBuilder<K, V> keyMarshaller(@NotNull  M marshaller) {
        return keyMarshallers(marshaller, marshaller);
    }

    @Override
    public ChronicleMapBuilder<K, V> keySizeMarshaller(@NotNull SizeMarshaller keySizeMarshaller) {
        keyBuilder.sizeMarshaller(keySizeMarshaller);
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> aligned64BitMemoryOperationsAtomic(
            boolean aligned64BitMemoryOperationsAtomic) {
        this.aligned64BitMemoryOperationsAtomic = aligned64BitMemoryOperationsAtomic;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> checksumEntries(boolean checksumEntries) {
        this.checksumEntries = checksumEntries ? ChecksumEntries.YES : ChecksumEntries.NO;
        return this;
    }

    boolean checksumEntries() {
        switch (checksumEntries) {
            case NO: return false;
            case YES: return true;
            case IF_PERSISTED: return persisted;
            default: throw new AssertionError();
        }
    }

    boolean aligned64BitMemoryOperationsAtomic() {
        return aligned64BitMemoryOperationsAtomic;
    }

    /**
     * Configures the {@code DataAccess} and {@code SizedReader} used to serialize and deserialize
     * values to and from off-heap memory in maps, created by this builder.
     *
     * @param valueReader the new bytes &rarr; value object reader strategy
     * @param valueDataAccess the new strategy of accessing the values' bytes for writing
     * @return this builder back
     * @see #valueMarshallers(SizedReader, SizedWriter)
     * @see ChronicleHashBuilder#keyReaderAndDataAccess(SizedReader, DataAccess)
     */
    public ChronicleMapBuilder<K, V> valueReaderAndDataAccess(
            SizedReader<V> valueReader, @NotNull DataAccess<V> valueDataAccess) {
        valueBuilder.reader(valueReader);
        valueBuilder.dataAccess(valueDataAccess);
        return this;
    }

    /**
     * Configures the marshallers, used to serialize/deserialize values to/from off-heap memory in
     * maps, created by this builder.
     *
     * @param valueReader the new bytes &rarr; value object reader strategy
     * @param valueWriter the new value object &rarr; bytes writer strategy
     * @return this builder back
     * @see #valueReaderAndDataAccess(SizedReader, DataAccess)
     * @see #valueSizeMarshaller(SizeMarshaller)
     * @see ChronicleHashBuilder#keyMarshallers(SizedReader, SizedWriter)
     */
    public ChronicleMapBuilder<K, V> valueMarshallers(
            @NotNull SizedReader<V> valueReader, @NotNull SizedWriter<? super V> valueWriter) {
        valueBuilder.reader(valueReader);
        valueBuilder.writer(valueWriter);
        return this;
    }

    /**
     * Shortcut for {@link #valueMarshallers(SizedReader, SizedWriter)
     * valueMarshallers(sizedMarshaller, sizedMarshaller)}.
     */
    public <M extends SizedReader<V> & SizedWriter<? super V>>
    ChronicleMapBuilder<K, V> valueMarshaller(@NotNull M sizedMarshaller) {
        return valueMarshallers(sizedMarshaller, sizedMarshaller);
    }

    /**
     * Configures the marshallers, used to serialize/deserialize values to/from off-heap memory in
     * maps, created by this builder.
     *
     * @param valueReader the new bytes &rarr; value object reader strategy
     * @param valueWriter the new value object &rarr; bytes writer strategy
     * @return this builder back
     * @see #valueReaderAndDataAccess(SizedReader, DataAccess)
     * @see #valueSizeMarshaller(SizeMarshaller)
     * @see ChronicleHashBuilder#keyMarshallers(BytesReader, BytesWriter)
     */
    public ChronicleMapBuilder<K, V> valueMarshallers(
            @NotNull BytesReader<V> valueReader, @NotNull BytesWriter<? super V> valueWriter) {
        valueBuilder.reader(valueReader);
        valueBuilder.writer(valueWriter);
        return this;
    }

    /**
     * Shortcut for {@link #valueMarshallers(BytesReader, BytesWriter)
     * valueMarshallers(marshaller, marshaller)}.
     */
    public <M extends BytesReader<V> & BytesWriter<? super V>>
    ChronicleMapBuilder<K, V> valueMarshaller(@NotNull M marshaller) {
        return valueMarshallers(marshaller, marshaller);
    }

    /**
     * Configures the marshaller used to serialize actual value sizes to off-heap memory in maps,
     * created by this builder.
     *
     * <p>Default value size marshaller is so-called "stop bit encoding" marshalling, unless {@link
     * #constantValueSizeBySample(Object)} or the builder statically knows the value size is
     * constant -- special constant size marshalling is used by default in these cases.
     *
     * @param valueSizeMarshaller the new marshaller, used to serialize actual value sizes to
     *                            off-heap memory
     * @return this builder back
     * @see #keySizeMarshaller(SizeMarshaller)
     */
    public ChronicleMapBuilder<K, V> valueSizeMarshaller(
            @NotNull SizeMarshaller valueSizeMarshaller) {
        valueBuilder.sizeMarshaller(valueSizeMarshaller);
        return this;
    }

    /**
     * Specifies the function to obtain a value for the key during {@link ChronicleMap#acquireUsing
     * acquireUsing()} calls, if the key is absent in the map, created by this builder.
     *
     * @param defaultValueProvider the strategy to obtain a default value by the absent key
     * @return this builder object back
     */
    public ChronicleMapBuilder<K, V> defaultValueProvider(
            @NotNull DefaultValueProvider<K, V> defaultValueProvider) {
        Objects.requireNonNull(defaultValueProvider);
        this.defaultValueProvider = defaultValueProvider;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> replication(SingleChronicleHashReplication replication) {
        this.singleHashReplication = replication;
        return this;
    }

    @Override
    public ChronicleMapBuilder<K, V> replication(byte identifier) {
        return replication(SingleChronicleHashReplication.builder().createWithId(identifier));
    }

    @Override
    public ChronicleMapBuilder<K, V> replication(
            byte identifier, TcpTransportAndNetworkConfig tcpTransportAndNetwork) {
        return replication(SingleChronicleHashReplication.builder()
                .tcpTransportAndNetwork(tcpTransportAndNetwork).createWithId(identifier));
    }

    @Override
    public ChronicleHashInstanceBuilder<ChronicleMap<K, V>> instance() {
        return new MapInstanceBuilder<>(this.clone(), singleHashReplication, null, null, null,
                new AtomicBoolean(false));
    }

    @Override
    public ChronicleMap<K, V> createPersistedTo(File file) throws IOException {
        // clone() to make this builder instance thread-safe, because createWithFile() method
        // computes some state based on configurations, but doesn't synchronize on configuration
        // changes.
        return clone().createWithFile(file, singleHashReplication, null);
    }

    @Override
    public ChronicleMap<K, V> create() {
        // clone() to make this builder instance thread-safe, because createWithoutFile() method
        // computes some state based on configurations, but doesn't synchronize on configuration
        // changes.
        return clone().createWithoutFile(singleHashReplication, null);
    }

    ChronicleMap<K, V> create(MapInstanceBuilder<K, V> ib) throws IOException {
        if (ib.file != null) {
            return createWithFile(ib.file, ib.singleHashReplication, ib.channel);
        } else {
            return createWithoutFile(ib.singleHashReplication, ib.channel);
        }
    }

    ChronicleMap<K, V> createWithFile(
            File file, SingleChronicleHashReplication singleHashReplication,
            ReplicationChannel channel) throws IOException {
        replicated = singleHashReplication != null || channel != null;
        persisted = true;

        for (int i = 0; i < 10; i++) {
            long fileLength = file.length();
            if (fileLength > 0) {
                try (FileInputStream fis = new FileInputStream(file);
                     ObjectInputStream ois = new ObjectInputStream(fis)) {
                    Object m;
                    byte serialization = ois.readByte();
                    if (serialization == XML_SERIALIZATION) {
                        m = deserializeHeaderViaXStream(ois);
                    } else if (serialization == BINARY_SERIALIZATION) {
                        try {
                            m = ois.readObject();
                        } catch (ClassNotFoundException e) {
                            throw new AssertionError(e);
                        }
                    } else {
                        throw new IOException("Unknown map header serialization type: " +
                                serialization);
                    }
                    @SuppressWarnings("unchecked")
                    VanillaChronicleMap<K, V, ?> map = (VanillaChronicleMap<K, V, ?>) m;
                    map.initTransientsFromBuilder(this);
                    initTransientsFromReplication(map, singleHashReplication, channel);
                    map.initBeforeMapping(fis.getChannel());
                    long expectedFileLength = map.expectedFileSize();
                    if (expectedFileLength != fileLength) {
                        throw new IOException("The file " + file + " the map is serialized from " +
                                "has unexpected length " + fileLength + ", probably corrupted. " +
                                "Expected length is " + expectedFileLength);
                    }
                    map.createMappedStoreAndSegments(file);
                    // This is needed to property initialize key and value serialization builders,
                    // which are later used in replication
                    preMapConstruction();
                    establishReplication(map, singleHashReplication, channel);
                    fis.getChannel().force(true);
                    // TODO according to Self Boostrapping Data spec, should write "init complete"
                    // byte *after* the above force instruction, see HCOLL-396
                    return map;
                }
            }
            if (file.createNewFile() || fileLength == 0) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        // new file
        if (!file.exists())
            throw new FileNotFoundException("Unable to create " + file);

        VanillaChronicleMap<K, V, ?> map = newMap(singleHashReplication, channel);

        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            if (!trySerializeHeaderViaXStream(map, oos)) {
                oos.writeByte(BINARY_SERIALIZATION);
                oos.writeObject(map);
            }
            oos.flush();
            map.initBeforeMapping(fos.getChannel());
            map.createMappedStoreAndSegments(file);
        }

        return establishReplication(map, singleHashReplication, channel);
    }

    private static void initTransientsFromReplication(
            VanillaChronicleMap<?, ?, ?> map,
            SingleChronicleHashReplication singleHashReplication, ReplicationChannel channel) {
        if (map instanceof ReplicatedChronicleMap) {
            AbstractReplication replication;
            if (singleHashReplication != null) {
                replication = singleHashReplication;
            } else if (channel != null) {
                replication = channel.hub();
            } else {
                replication = null;
            }
            if (replication != null)
                ((ReplicatedChronicleMap) map).initTransientsFromReplication(replication);
        }
    }

    private static <K, V> boolean trySerializeHeaderViaXStream(
            VanillaChronicleMap<K, V, ?> map, ObjectOutputStream oos)
            throws IOException {
        Class<?> xStreamClass;
        try {
            xStreamClass =
                    Class.forName("net.openhft.xstream.MapHeaderSerializationXStream");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            xStreamClass = null;
        }
        if (xStreamClass == null) {
            LOG.info("xStream not found, use binary ChronicleMap header serialization");
            return false;
        }
        try {
            oos.writeByte(XML_SERIALIZATION);
            Method toXML = xStreamClass.getMethod("toXML", Object.class, OutputStream.class);
            toXML.invoke(xStreamClass.newInstance(), map, oos);
            return true;
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException |
                InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object deserializeHeaderViaXStream(ObjectInputStream ois) {
        try {
            Class<?> xStreamClass =
                    Class.forName("net.openhft.xstream.MapHeaderSerializationXStream");
            Method fromXML = xStreamClass.getMethod("fromXML", InputStream.class);
            return fromXML.invoke(xStreamClass.newInstance(), ois);
        } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException |
                IllegalAccessException | InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    ChronicleMap<K, V> createWithoutFile(
            SingleChronicleHashReplication singleHashReplication, ReplicationChannel channel) {
        replicated = singleHashReplication != null || channel != null;
        persisted = false;

        try {
            VanillaChronicleMap<K, V, ?> map = newMap(singleHashReplication, channel);
            // TODO this method had been moved
//            if(OS.warnOnWindows(map.sizeInBytesWithoutTiers())){
//                throw new IllegalStateException("Windows cannot support this configuration");
//            }

            BytesStore bytesStore =
                    lazyNativeBytesStoreWithFixedCapacity(map.sizeInBytesWithoutTiers());
            map.createMappedStoreAndSegments(bytesStore);
            return establishReplication(map, singleHashReplication, channel);
        } catch (IOException e) {
            // file-less version should never trigger an IOException.
            throw new AssertionError(e);
        }
    }

    private VanillaChronicleMap<K, V, ?> newMap(
            SingleChronicleHashReplication singleHashReplication, ReplicationChannel channel)
            throws IOException {
        preMapConstruction();
        if (replicated) {
            AbstractReplication replication;
            if (singleHashReplication != null) {
                replication = singleHashReplication;
            } else {
                replication = channel.hub();
            }
            return new ReplicatedChronicleMap<>(this, replication);
        } else {
            return new VanillaChronicleMap<>(this);
        }
    }

    void preMapConstruction() {
        averageKeySize = preMapConstruction(
                keyBuilder, averageKeySize, averageKey, sampleKey, "Key");
        averageValueSize = preMapConstruction(
                valueBuilder, averageValueSize, averageValue, sampleValue, "Value");
        stateChecks();
    }

    private <E> double preMapConstruction(
            SerializationBuilder<E> builder, double configuredAverageSize, E average, E sample,
            String dim) {
        if (sample != null) {
            return builder.constantSizeBySample(sample);
        } else {
            double result = averageKeyOrValueSize(configuredAverageSize, builder, average);
            if (!isNaN(result) || allLowLevelConfigurationsAreManual()) {
                return result;
            } else {
                throw new IllegalStateException(dim + " size in serialized form must " +
                        "be configured in ChronicleMap, at least approximately.\nUse builder" +
                        ".average" + dim + "()/.constant" + dim + "SizeBySample()/" +
                        ".average" + dim + "Size() methods to configure the size");
            }
        }
    }

    private void stateChecks() {
        checkActualChunksPerSegmentIsConfiguredOnlyIfOtherLowLevelConfigsAreManual();
        checkActualChunksPerSegmentGreaterOrEqualToEntries();
    }

    private boolean allLowLevelConfigurationsAreManual() {
        return actualSegments > 0 && entriesPerSegment > 0 && actualChunksPerSegment > 0
                && actualChunkSize > 0;
    }

    private ChronicleMap<K, V> establishReplication(
            VanillaChronicleMap<K, V, ?> map,
            SingleChronicleHashReplication singleHashReplication,
            ReplicationChannel channel) throws IOException {
        if (map instanceof ReplicatedChronicleMap) {
            if (singleHashReplication != null && channel != null)
                throw new AssertionError("Only one non-null replication should be passed");

            ReplicatedChronicleMap result = (ReplicatedChronicleMap) map;
            if (cleanupRemovedEntries)
                establishCleanupThread(result);

            List<Replicator> replicators = new ArrayList<>(2);
            if (singleHashReplication != null) {
                if (singleHashReplication.tcpTransportAndNetwork() != null)
                    replicators.add(Replicator.tcp(singleHashReplication));
                if (singleHashReplication.udpTransport() != null)
                    replicators.add(Replicator.udp(singleHashReplication.udpTransport()));
            } else if (channel != null) {
                ReplicationHub hub = channel.hub();

                ChannelProvider provider = ChannelProvider.getProvider(hub);
                ChannelProvider.ChronicleChannel ch = provider.createChannel(channel.channelId());
                replicators.add(ch);
            } else {
                assert persisted && !((ReplicatedChronicleMap) map).createdOrInMemory :
                        "No Replicators for replicated ChronicleMap could be only on " +
                                "deserialization/access of existing replicated ChronicleMap, " +
                                "when replication in not needed in this JVM/run";
            }
            for (Replicator replicator : replicators) {
                Closeable token = replicator.applyTo(this, result, result,
                        (ReplicatedChronicleMap)map);
                if (replicators.size() == 1 && token.getClass() == UdpReplicator.class) {
                    LOG.warn(Replicator.ONLY_UDP_WARN_MESSAGE);
                }
                result.addCloseable(token);
            }
        }
        return map;
    }

    private void establishCleanupThread(ReplicatedChronicleMap map) {
        OldDeletedEntriesCleanup cleanup = new OldDeletedEntriesCleanup(map);
        NamedThreadFactory threadFactory =
                new NamedThreadFactory("cleanup thread for map persisted at " + map.file());
        ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
        executor.submit(cleanup);

        map.addCloseable(cleanup);
        // WARNING this relies on the fact that ReplicatedChronicleMap closes closeables in the same
        // order as they are added, i. e. OldDeletedEntriesCleanup instance close()d before the
        // following closeable
        map.addCloseable(() -> {
            executor.shutdown();
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.error("", e);
            }
        });
    }

    /**
     * Inject your SPI code around basic {@code ChronicleMap}'s operations with entries:
     * removing entries, replacing the entries' value and inserting the new entry.
     * 
     * <p>This affects behaviour of ordinary map.put(), map.remove(), etc. calls, as well as removes
     * and replacing values <i>during iterations</i>, <i>remote map calls</i> and
     * <i>internal replication operations</i>. 
     */
    public ChronicleMapBuilder<K, V> entryOperations(MapEntryOperations<K, V, ?> entryOperations) {
        Objects.requireNonNull(entryOperations);
        this.entryOperations = entryOperations;
        return this;
    }

    /**
     * Inject your SPI around logic of all {@code ChronicleMap}'s operations with individual keys:
     * from {@link ChronicleMap#containsKey} to {@link ChronicleMap#acquireUsing} and
     * {@link ChronicleMap#merge}.
     * 
     * <p>This affects behaviour of ordinary map calls, as well as <i>remote calls</i>.
     */
    public ChronicleMapBuilder<K, V> mapMethods(MapMethods<K, V, ?> mapMethods) {
        Objects.requireNonNull(mapMethods);
        this.methods = mapMethods;
        return this;
    }

    public ChronicleMapBuilder<K, V> remoteOperations(
            MapRemoteOperations<K, V, ?> remoteOperations) {
        Objects.requireNonNull(remoteOperations);
        this.remoteOperations = remoteOperations;
        return this;
    }
}

