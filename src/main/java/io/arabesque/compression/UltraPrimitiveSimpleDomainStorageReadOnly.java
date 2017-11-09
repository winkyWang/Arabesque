package io.arabesque.compression;

import com.koloboke.collect.IntCollection;
import com.koloboke.collect.set.hash.HashIntSet;
import com.koloboke.collect.set.hash.HashIntSets;
import io.arabesque.computation.Computation;
import io.arabesque.conf.Configuration;
import io.arabesque.embedding.EdgeInducedEmbedding;
import io.arabesque.embedding.Embedding;
import io.arabesque.embedding.VertexInducedEmbedding;
import io.arabesque.graph.Edge;
import io.arabesque.graph.LabelledEdge;
import io.arabesque.graph.MainGraph;
import io.arabesque.odag.domain.StorageReader;
import io.arabesque.pattern.LabelledPatternEdge;
import io.arabesque.pattern.Pattern;
import io.arabesque.pattern.PatternEdge;
import io.arabesque.pattern.PatternEdgeArrayList;
import io.arabesque.report.StorageReport;
import io.arabesque.utils.collection.IntArrayList;
import io.arabesque.utils.collection.IntCollectionAddConsumer;
import org.apache.log4j.Logger;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

public class UltraPrimitiveSimpleDomainStorageReadOnly extends UltraPrimitiveSimpleDomainStorage {
    private static final Logger LOG = Logger.getLogger(UltraPrimitiveSimpleDomainStorageReadOnly.class);

    @Override
    public void readFields(DataInput dataInput) throws IOException { 
        this.clear();

        numEmbeddings = dataInput.readLong();
        setNumberOfDomains(dataInput.readInt());

        for (int i = 0; i < numberOfDomains; ++i) {
            int domainSize = dataInput.readInt();

            for (int j = 0; j < domainSize; ++j) {
                domainEntries.get(i).add(dataInput.readInt());
            }
        }
        countsDirty = true;

        isStorageInitialized = false;
        initStorage();
    }

    @Override
    public StorageReader getReader(Pattern pattern,
            Computation<Embedding> computation,
            int numPartitions, int numBlocks, int maxBlockSize) {
        return new Reader(pattern, computation, numPartitions, numBlocks, maxBlockSize);
    }

    @Override
    public StorageReader getReader(Pattern[] patterns,
            Computation<Embedding> computation,
            int numPartitions, int numBlocks, int maxBlockSize) {
        throw new RuntimeException("Multi-pattern with SimpleStorage is not available");
    }

    // Reading efficient storage
    private int[][] storage = null;
    protected boolean isStorageInitialized = false;

    public long getReadingStorageSize() {
        long size = 0;

        for (int i = 0 ; i < storage.length ; ++i) {
            size += storage[i].length;
        }

        return size * 4;
    }

    private void initStorage() {
        if(isStorageInitialized)
            return;

        storage = new int[numberOfDomains][];

        for(int i = 0 ; i < numberOfDomains ; ++ i) {
            storage[i] = new int[domainEntries.get(i).size()];
            int[] keysSet = domainEntries.get(i).toIntArray();

            for(int j = 0 ; j < keysSet.length ; ++j)
                storage[i][j] = keysSet[j];
        }

        isStorageInitialized = true;
    }

    public class Reader implements StorageReader {
        private final MainGraph mainGraph;
        private final Embedding reusableEmbedding;
        private final long numberOfEnumerations;

        private final long blockSize;
        private final int partitionId;

        private final Deque<EnumerationStep> enumerationStack;
        private final HashIntSet singletonExtensionSet;
        private final Pattern pattern;
        private final Computation<Embedding> computation;
        private final int numPartitions;
        private final int superStep;

        private long targetEnumId;

        private EdgesConsumer edgesConsumer;
        private IntArrayList edgeIds;

        private final boolean debugCtor = false;
        // Debugging flags
        private final boolean DEBUG_TestCompleteEmbedding = false;
        private final boolean DEBUG_TryAddWord = false;
        private final boolean DEBUG_GetNextEnumerationID = false;
        private final boolean DEBUG_GetEnumerationWithStack = false;
        private final boolean DEBUG_CTOR = false;
        private final boolean DEBUG_MOVE_NEXT = false;
        private final int TARGET_SUPER_STEP = 3;
        private final boolean isItTargetSuperStep;

        protected StorageReport report = new StorageReport();
        protected long numCompleteEnumerationsVisited = 0;
        // how many invalid embeddings this storage/partition generated
        protected long numSpuriousEmbeddings = 0L;

        public Reader(Pattern pattern, Computation<Embedding> computation, int numPartitions, int numBlocks, int maxBlockSize) {
            this.pattern = pattern;
            this.computation = computation;
            this.numPartitions = numPartitions;

            mainGraph = Configuration.get().getMainGraph();
            reusableEmbedding = Configuration.get().createEmbedding();

            this.numberOfEnumerations = getNumberOfEnumerations();

            this.blockSize = Math.min(Math.max(numberOfEnumerations / numBlocks, 1L), maxBlockSize);

            this.superStep = computation.getStep();

            enumerationStack = new ArrayDeque<>();
            enumerationStack.add(new Domain0EnumerationStep(0, -1, -1));

            singletonExtensionSet = HashIntSets.newMutableSet(1);

            partitionId = computation.getPartitionId();

            targetEnumId = -1;

            edgeIds = new IntArrayList();

            edgesConsumer = new EdgesConsumer(Configuration.get().isGraphEdgeLabelled());
            edgesConsumer.setCollection(edgeIds);

            isItTargetSuperStep = superStep == TARGET_SUPER_STEP;

            if(DEBUG_CTOR && isItTargetSuperStep){
                printDebugInfo("ctor", "");
            }

            report.initReport(numberOfDomains);
        }

        protected int[] getWordIdsOfDomain(int domainId) {
            if(domainId >= numberOfDomains || domainId < 0)
                throw new ArrayIndexOutOfBoundsException("Should not access domain " + domainId + " while numOfDomain=" + numberOfDomains);
            return storage[domainId];
        }

        protected void printDebugInfo(String callerName, String message) {
            String msg = "\nInside " + callerName +
                    "(partitionId=" + partitionId +
                    "in SuperStep " + superStep + " with {\n" +
                    "\nnumberOfEnumerations=" + numberOfEnumerations +
                    "\nblockSize=" + this.blockSize +
                    "\n" + message + "\n}";
            LOG.info(msg);
        }

        // TODO: to be implemeneted
        private boolean isItTargetEmbedding() {
            return false;
        }

        protected void finalizeReport() {
            report.numEnumerations = numberOfEnumerations;
            report.numCompleteEnumerationsVisited = numCompleteEnumerationsVisited;
            report.numSpuriousEmbeddings = numSpuriousEmbeddings;
            report.numActualEmbeddings = numEmbeddings;

            for(int i = 0; i < numberOfDomains; ++i) {
                report.domainSize[i] = domainEntries.get(i).size();
            }
        }

        public StorageReport getStorageReport() {
            finalizeReport();
            return report;
        }

        @Override
        public boolean hasNext() {
            return moveNext();
        }

        @Override
        public Embedding next() {
            return reusableEmbedding;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private boolean tryAddWord(int wordId) {
            if (reusableEmbedding instanceof VertexInducedEmbedding) {
                VertexInducedEmbedding reusableVertexEmbedding = (VertexInducedEmbedding) reusableEmbedding;

                IntArrayList vertices = reusableVertexEmbedding.getVertices();

                // now let's check if the word has already been added to the embedding (Trying to add existing vertex)
                if(vertices.contains(wordId))
                    return false;

                singletonExtensionSet.clear();
                singletonExtensionSet.add(wordId);

                computation.filter(reusableEmbedding, singletonExtensionSet);

                if (singletonExtensionSet.size() == 0)
                    return false;

                // check if it is a canonical embedding with this word
                if (!computation.filter(reusableVertexEmbedding, wordId))
                    return false;

                reusableVertexEmbedding.addWord(wordId);
            } else if (reusableEmbedding instanceof EdgeInducedEmbedding) {
                EdgeInducedEmbedding reusableEdgeEmbedding = (EdgeInducedEmbedding) reusableEmbedding;

                singletonExtensionSet.clear();
                singletonExtensionSet.add(wordId);

                computation.filter(reusableEmbedding, singletonExtensionSet);

                PatternEdgeArrayList edges = pattern.getEdges();

                PatternEdge equivalentPatternEdge = edges.get(reusableEdgeEmbedding.getNumWords());

                int equivalentPatternEdgeSrcIndex = equivalentPatternEdge.getSrcPos();
                int equivalentPatternEdgeDestIndex = equivalentPatternEdge.getDestPos();

                reusableEdgeEmbedding.addWord(wordId);
                IntArrayList embeddingVertices = reusableEdgeEmbedding.getVertices();
                int numEmbeddingVertices = reusableEdgeEmbedding.getNumVertices();
                reusableEdgeEmbedding.removeLastWord();

                // If pattern has more vertices than embedding with this word, quit,
                // expansion not valid
                if (equivalentPatternEdgeSrcIndex >= numEmbeddingVertices ||
                        equivalentPatternEdgeDestIndex >= numEmbeddingVertices) {
                    return false;
                }

                // Otherwise, if same number of vertices, check if the edge connecting the
                // vertices mapped from the pattern is the same that we are trying to add.
                // If not, quit, expansion not valid.
                IntCollection edgeIds = getEdgeIds(
                        embeddingVertices.getUnchecked(equivalentPatternEdgeSrcIndex),
                        embeddingVertices.getUnchecked(equivalentPatternEdgeDestIndex),
                        equivalentPatternEdge);

                // NOTE: IntSet would theoretically allow faster contains but, in practice,
                // we assume not a lot of edges between 2 vertices exist with the same label
                // so array should be quicker.
                if (!edgeIds.contains(wordId))
                    return false;

                if (!computation.filter(reusableEdgeEmbedding, wordId))
                    return false;

                reusableEdgeEmbedding.addWord(wordId);
            } else {
                throw new RuntimeException("Incompatible embedding class: " + reusableEmbedding.getClass());
            }

            return true;
        }

        private IntCollection getEdgeIds(int srcId, int dstId, PatternEdge patternEdge) {
            edgeIds.clear();
            edgesConsumer.setPatternEdge(patternEdge);
            mainGraph.forEachEdgeId(srcId, dstId, edgesConsumer);
            return edgeIds;
        }

        private boolean testCompleteEmbedding() {
            if (reusableEmbedding.getNumVertices() != pattern.getNumberOfVertices())
                return false;

            if (reusableEmbedding instanceof VertexInducedEmbedding) {
                VertexInducedEmbedding reusableVertexEmbedding = (VertexInducedEmbedding) reusableEmbedding;

                // Test if constructed embedding matches the pattern.
                // TODO: Perhaps we can do this incrementally in an efficient manner?
                int numEdgesPattern = pattern.getNumberOfEdges();
                int numEdgesEmbedding = reusableVertexEmbedding.getNumEdges();

                if (numEdgesEmbedding != numEdgesPattern)
                    return false;

                PatternEdgeArrayList edgesPattern = pattern.getEdges();
                IntArrayList edgesEmbedding = reusableVertexEmbedding.getEdges();
                IntArrayList verticesEmbedding = reusableVertexEmbedding.getVertices();

                for (int i = 0; i < numEdgesPattern; ++i) {
                    PatternEdge edgePattern = edgesPattern.get(i);
                    Edge edgeEmbedding = mainGraph.getEdge(edgesEmbedding.getUnchecked(i));

                    if (!edgeEmbedding.hasVertex(verticesEmbedding.getUnchecked(edgePattern.getSrcPos())) ||
                            !edgeEmbedding.hasVertex(verticesEmbedding.getUnchecked(edgePattern.getDestPos()))) {
                        return false;
                    }
                }
            }

            return computation.filter(reusableEmbedding) && computation.shouldExpand(reusableEmbedding);
        }

        public boolean getEnumerationWithStack(int targetSize) {
            long currentId = 0;

            while (!enumerationStack.isEmpty() && targetEnumId >= currentId) {
                EnumerationStep lastEnumerationStep = enumerationStack.pop();
                int domainOfLastEnumerationStep = enumerationStack.size();
                int wordIdOfLastEnumerationStep = lastEnumerationStep.wordId;
                currentId = lastEnumerationStep.currentId;

                if (wordIdOfLastEnumerationStep >= 0) {
                    currentId += domainCounters[domainOfLastEnumerationStep];
                    reusableEmbedding.removeLastWord();
                }

                int domainWithPointers = enumerationStack.size() - 1;

                // we are in the first domain: Domain0 -> Domain0EnumerationStep
                // Need to increment index of first domain
                if (domainWithPointers == -1) {

                    Domain0EnumerationStep domain0EnumerationStep = (Domain0EnumerationStep) lastEnumerationStep;

                    int currentIndex = domain0EnumerationStep.index;

                    while (++currentIndex < domain0OrderedKeys.length) {
                        int wordId = domain0OrderedKeys[currentIndex];
                        long domain0Counter = domainCounters[0];

                        if ((domainOfLastEnumerationStep < targetSize - 1 && currentId + domain0Counter > targetEnumId)
                                || (domainOfLastEnumerationStep == targetSize - 1 && currentId == targetEnumId)) {
                            boolean invalid = false;

                            // If we couldn't add this word this means that the
                            // current partial embedding and all extensions are invalid
                            // so skip everything and return false since enumId was associated
                            // with an invalid embedding.
                            if (!tryAddWord(wordId)) {
                                targetEnumId = currentId + domain0Counter - 1;
                                invalid = true;
                                // Add word anyway. Embedding will be invalid with this word but it will be
                                // popped on the next iteration of the while
                                reusableEmbedding.addWord(wordId);
                            }

                            domain0EnumerationStep.index = currentIndex;
                            domain0EnumerationStep.currentId = currentId;
                            domain0EnumerationStep.wordId = wordId;
                            enumerationStack.push(domain0EnumerationStep);

                            if (invalid) {
                                numSpuriousEmbeddings += 1;
                                report.pruned[domainOfLastEnumerationStep] += 1;
                                return false;
                            }
                            else {
                                report.explored[domainOfLastEnumerationStep] += 1;
                                // add new DomainNot0EnumerationStep with wordId = -1, and all possible ids for next domain
                                if (enumerationStack.size() != targetSize) {
                                    int[] nextDomainPointers = getWordIdsOfDomain(1);

                                    enumerationStack.push( new DomainNot0EnumerationStep(currentId, -1, nextDomainPointers) );
                                }

                                break;
                            }
                        }

                        currentId += domain0Counter;
                    }
                } // we are now in one of the non-0 domains: Domain0 -> DomainNot0EnumerationStep
                else {
                    DomainNot0EnumerationStep domainNot0EnumerationStep = (DomainNot0EnumerationStep) lastEnumerationStep;

                    int[] possibilitiesLastDomain;
                    if(domainOfLastEnumerationStep + 1 ==  numberOfDomains)
                        possibilitiesLastDomain = new int[0];
                    else
                        possibilitiesLastDomain = getWordIdsOfDomain(domainOfLastEnumerationStep + 1);

                    int[] pointers = domainNot0EnumerationStep.domain;

                    for (int i = domainNot0EnumerationStep.pos + 1; i < pointers.length; i++) {
                        int newWordId = pointers[i];

                        long numOfNewPossibilities = domainCounters[domainOfLastEnumerationStep];

                        if ((domainOfLastEnumerationStep < targetSize - 1 && currentId + numOfNewPossibilities > targetEnumId)
                                || (domainOfLastEnumerationStep == targetSize - 1 && currentId == targetEnumId)) {
                            boolean invalid = false;

                            // If we couldn't add this word this means that the
                            // current partial embedding and all extensions are invalid
                            // so skip everything and return false since enumId was associated
                            // with an invalid embedding.
                            if (!tryAddWord(newWordId)) {
                                targetEnumId = currentId + numOfNewPossibilities - 1;
                                invalid = true;
                                // Add word anyway. Embedding will be invalid with this word but it will be
                                // popped on the next iteration of the while
                                reusableEmbedding.addWord(newWordId);
                            }

                            lastEnumerationStep.currentId = currentId;
                            lastEnumerationStep.wordId = newWordId;
                            ((DomainNot0EnumerationStep) lastEnumerationStep).pos = i;
                            enumerationStack.push(lastEnumerationStep);

                            if (invalid) {
                                numSpuriousEmbeddings += 1;
                                report.pruned[domainOfLastEnumerationStep] += 1;

                                return false;
                            } else {
                                report.explored[domainOfLastEnumerationStep] += 1;

                                if (enumerationStack.size() != targetSize)
                                    enumerationStack.push( new DomainNot0EnumerationStep(currentId, -1, possibilitiesLastDomain) );

                                break;
                            }
                        }

                        currentId += numOfNewPossibilities;
                    }
                }

                if (enumerationStack.size() == targetSize) // If enumeration stack is of the desired size
                    if (enumerationStack.peek().wordId >= 0) // And last element actually represents a valid element
                        break; // Get out of the loop
            }

            numCompleteEnumerationsVisited += 1;
            boolean isCompleteEmbeddingValid = testCompleteEmbedding();
            boolean isEmbeddingOfTargetSize = reusableEmbedding.getNumWords() == targetSize;

            if(!(isCompleteEmbeddingValid && isEmbeddingOfTargetSize))
                numSpuriousEmbeddings += 1;

            return isEmbeddingOfTargetSize && isCompleteEmbeddingValid;
        }

        public String toStringResume() {
            StringBuilder sb = new StringBuilder();
            sb.append("EmbeddingsZip Reader:\n");
            sb.append("Enumerations: " + targetEnumId + " " + numberOfEnumerations + "\n");
            return sb.toString();
        }

        public boolean moveNext() {
            while (true) {
                targetEnumId = getNextEnumerationId(targetEnumId);

                if (targetEnumId == -1)
                    return false;

                if (getEnumerationWithStack(domainEntries.size()))
                    return true;
            }
        }

        public long getNextEnumerationId(long enumId) {
            while (enumId < numberOfEnumerations - 1) {
                enumId++;

                long currentBlockId = enumId / blockSize;

                if (isThisMyBlock(currentBlockId))
                    return enumId;
                else // -1 because we'll increment it at the beginning of the next iteration
                    enumId = (currentBlockId + blocksToSkip(currentBlockId)) * blockSize - 1;
            }

            if (enumId >= numberOfEnumerations - 1)
                enumId = -1;

            return enumId;
        }

        public int blocksToSkip(long blockId) {
            int owningPartition = (int) (blockId % numPartitions);
            int myPartition = partitionId;

            if (myPartition < owningPartition) {
                myPartition += numPartitions;
            }

            return myPartition - owningPartition;
        }

        // means this enum is in my block to handle
        public boolean isThisMyBlock(long blockId) {
            return blockId % numPartitions == partitionId;
        }

        @Override
        public void close() {
            // Do nothing by default
        }

        public abstract class EnumerationStep {
            long currentId;
            int wordId;

            public EnumerationStep(long currentId, int wordId) {
                this.currentId = currentId;
                this.wordId = wordId;
            }

            @Override
            public String toString() {
                return "EnumerationStep{" +
                        "currentId=" + currentId +
                        ", wordId=" + wordId +
                        '}';
            }
        }

        public class Domain0EnumerationStep extends EnumerationStep {
            int index;

            public Domain0EnumerationStep(long currentId, int wordId, int index) {
                super(currentId, wordId);
                this.index = index;
            }

            @Override
            public String toString() {
                return "Domain0EnumerationStep{" +
                        "index=" + index +
                        "} " + super.toString();
            }
        }

        public class DomainNot0EnumerationStep extends EnumerationStep {
            int[] domain;//IntCursor cursor;
            int pos = -1;

            public DomainNot0EnumerationStep(long currentId, int wordId, int[] domain) {
                super(currentId, wordId);
                this.domain = domain;
            }

            @Override
            public String toString() {
                return "DomainNot0EnumerationStep{" +
                        "cursor=" + domain +
                        "} " + super.toString();
            }
        }

        private class EdgesConsumer extends IntCollectionAddConsumer {
            private boolean hasLabel;
            private int targetLabel;

            public EdgesConsumer(boolean hasLabel) {
                this.hasLabel = hasLabel;
            }

            public void setPatternEdge(PatternEdge patternEdge) {
                if (hasLabel) {
                    this.targetLabel = ((LabelledPatternEdge) patternEdge).getLabel();
                }
            }

            @Override
            public void accept(int edgeId) {
                if (hasLabel) {
                    LabelledEdge labelledEdge = (LabelledEdge) mainGraph.getEdge(edgeId);

                    if (labelledEdge.getEdgeLabel() != targetLabel) {
                        return;
                    }
                }

                super.accept(edgeId);
            }
        }
    }
}