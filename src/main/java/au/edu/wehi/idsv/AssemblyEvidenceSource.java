package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFileReader;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import au.edu.wehi.idsv.debruijn.anchored.DeBruijnAnchoredAssembler;
import au.edu.wehi.idsv.debruijn.subgraph.DeBruijnSubgraphAssembler;
import au.edu.wehi.idsv.pipeline.SortRealignedAssemblies;
import au.edu.wehi.idsv.util.AutoClosingIterator;
import au.edu.wehi.idsv.util.AutoClosingMergedIterator;
import au.edu.wehi.idsv.util.FileHelper;

import com.google.common.collect.ImmutableList;


public class AssemblyEvidenceSource extends EvidenceSource {
	private static final Log log = Log.getInstance(AssemblyEvidenceSource.class);
	private final List<SAMEvidenceSource> source;
	private final int maxSourceFragSize;
	private final FileSystemContext fsc;
	/**
	 * Generates assembly evidence based on the given evidence
	 * @param evidence evidence for creating assembly
	 * @param intermediateFileLocation location to store intermediate files
	 */
	public AssemblyEvidenceSource(ProcessingContext processContext, List<SAMEvidenceSource> evidence, File intermediateFileLocation) {
		super(processContext, intermediateFileLocation);
		this.fsc = processContext.getFileSystemContext();
		this.source = evidence;
		int max = 0;
		for (SAMEvidenceSource s : evidence) {
			max = Math.max(max, s.getMaxConcordantFragmentSize());
		}
		maxSourceFragSize = max;
	}
	public void ensureAssembled() {
		ensureAssembled(null);
	}
	public void ensureAssembled(ExecutorService threadpool) {
		if (!isProcessingComplete()) {
			process(threadpool);
		}
		if (isRealignmentComplete()) {
			SortRealignedAssemblies step = new SortRealignedAssemblies(processContext, this);
			step.process(EnumSet.of(ProcessStep.SORT_REALIGNED_ASSEMBLIES), threadpool);
			step.close();
		}
	}
	@SuppressWarnings("unchecked") // can I do checked covariant iterator casting in java?
	@Override
	protected CloseableIterator<DirectedEvidence> perChrIterator(String chr) {
		FileSystemContext fsc = processContext.getFileSystemContext();
		return (CloseableIterator<DirectedEvidence>)(Object)iterator(
				fsc.getAssemblyVcfForChr(input, chr),
				fsc.getRealignmentBamForChr(input, chr));
	}
	@SuppressWarnings("unchecked") // can I do checked covariant iterator casting in java? 
	@Override
	protected CloseableIterator<DirectedEvidence> singleFileIterator() {
		FileSystemContext fsc = processContext.getFileSystemContext();
		return (CloseableIterator<DirectedEvidence>)(Object)iterator(
			fsc.getAssemblyVcf(input),
			fsc.getRealignmentBam(input));
	}
	private CloseableIterator<VariantContextDirectedEvidence> iterator(File vcf, File realignment) {
		if (!isProcessingComplete()) {
			log.error("Assemblies not yet generated.");
			throw new RuntimeException("Assemblies not yet generated");
		}
		List<Closeable> toClose = new ArrayList<>();
		CloseableIterator<SAMRecord> realignedIt; 
		if (isRealignmentComplete()) {
			SamReader realignedReader = processContext.getSamReader(realignment);
			realignedIt = processContext.getSamReaderIterator(realignedReader);
			toClose.add(realignedReader);
			toClose.add(realignedIt);
		} else {
			log.debug(String.format("Assembly realignment for %s not completed", vcf));
			realignedIt = new AutoClosingIterator<SAMRecord>(ImmutableList.<SAMRecord>of().iterator());
		}
		VCFFileReader reader = new VCFFileReader(vcf, false);
		toClose.add(reader);
		CloseableIterator<VariantContextDirectedEvidence> evidenceIt = new VariantContextDirectedEvidenceIterator(
				processContext,
				this,
				new AutoClosingIterator<VariantContext>(reader.iterator(), ImmutableList.<Closeable>of(reader)),
				realignedIt);
		toClose.add(evidenceIt);
		// Change sort order from VCF sorted order to evidence position order
		CloseableIterator<VariantContextDirectedEvidence> sortedIt = new AutoClosingIterator<VariantContextDirectedEvidence>(new DirectEvidenceWindowedSortingIterator<VariantContextDirectedEvidence>(
				processContext,
				(int)((2 + processContext.getAssemblyParameters().maxSubgraphFragmentWidth + processContext.getAssemblyParameters().subgraphAssemblyMargin) * maxSourceFragSize),
				evidenceIt), toClose);
		// FIXME: TODO: add remote assemblies to iterator
		return sortedIt;
	}
	private boolean isProcessingComplete() {
		boolean done = true;
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				done &= IntermediateFileUtil.checkIntermediate(fsc.getAssemblyVcfForChr(input, seq.getSequenceName()));
				if (!done) return false;
				done &= IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastqForChr(input, seq.getSequenceName()));
				if (!done) return false;
			}
		} else {
			done &= IntermediateFileUtil.checkIntermediate(fsc.getAssemblyVcf(input));
			if (!done) return false;
			done &= IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastq(input));
			if (!done) return false;
		}
		return done;
	}
	protected void process(ExecutorService threadpool) {
		if (isProcessingComplete()) return;
		log.info("START evidence assembly ", input);
		for (SAMEvidenceSource s : source) {
			if (!s.isComplete(ProcessStep.EXTRACT_READ_PAIRS) ||
				!s.isComplete(ProcessStep.EXTRACT_SOFT_CLIPS)) {
				throw new IllegalStateException(String.format("Unable to perform assembly: evidence extraction not complete for %s", s.getSourceFile()));
			}
		}
		final SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
		if (processContext.shouldProcessPerChromosome()) {
			final List<Callable<Void>> workers = new ArrayList<>();
			for (int i = 0; i < dict.size(); i++) {
				final String seq = dict.getSequence(i).getSequenceName();
				
				if (IntermediateFileUtil.checkIntermediate(fsc.getAssemblyVcfForChr(input, seq))
					&& IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastqForChr(input, seq))) {
					log.debug("Skipping assembly for ", seq, " as output already exists.");
				} else {
					workers.add(new Callable<Void>() {
						@Override
						public Void call() {
							log.info("Starting ", seq, " breakend assembly");
							CloseableIterator<DirectedEvidence> merged = null;
							List<CloseableIterator<DirectedEvidence>> toMerge = new ArrayList<>();
							try {
								for (SAMEvidenceSource bam : source) {
									CloseableIterator<DirectedEvidence> it = bam.iterator(seq);
									toMerge.add(it);
								}
								merged = new AutoClosingMergedIterator<DirectedEvidence>(toMerge, DirectedEvidenceOrder.ByNatural);
								new ContigAssembler(merged, processContext.getFileSystemContext().getAssemblyVcfForChr(input, seq), processContext.getFileSystemContext().getRealignmentFastqForChr(input, seq)).run();
								merged.close();
							} catch (Exception e) {
								log.error(e, "Error performing ", seq, " breakend assembly");
								throw new RuntimeException(e);
							} finally {
								CloserUtil.close(merged);
								CloserUtil.close(toMerge);
							}
							log.info("Completed ", seq, " breakend assembly");
							return null;
						}
					});
				}
			}
			if (threadpool != null) {
				log.info("Performing multithreaded assembly");
				try {
					for (Future<Void> future : threadpool.invokeAll(workers)) {
						// Throws any exceptions back up the call stack
						try {
							future.get();
						} catch (ExecutionException e) {
							throw new RuntimeException(e);
						}
					}
				} catch (InterruptedException e) {
					String msg = "Interrupted while assembly in progress";
					log.error(e, msg);
					throw new RuntimeException(msg, e);
				}
			} else {
				log.info("Performing singlethreaded assembly");
				for (Callable<Void> c : workers) {
					try {
						c.call();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}
		} else {
			List<CloseableIterator<DirectedEvidence>> toMerge = new ArrayList<>();
			CloseableIterator<DirectedEvidence> merged = null;
			try {
				for (SAMEvidenceSource bam : source) {
					CloseableIterator<DirectedEvidence> it = bam.iterator();
					toMerge.add(it);
				}
				merged = new AutoClosingMergedIterator<DirectedEvidence>(toMerge, DirectedEvidenceOrder.ByNatural);
				new ContigAssembler(merged, processContext.getFileSystemContext().getAssemblyVcf(input), processContext.getFileSystemContext().getRealignmentFastq(input)).run();
				merged.close();
			} finally {
				CloserUtil.close(merged);
				CloserUtil.close(toMerge);
			}
		}
		log.info("SUCCESS evidence assembly ", input);
	}
	private class ContigAssembler implements Runnable {
		private Iterator<DirectedEvidence> it;
		private File breakendVcf;
		private File realignmentFastq;
		private FastqBreakpointWriter fastqWriter = null;
		private VariantContextWriter vcfWriter = null;
		private Queue<VariantContextDirectedEvidence> resortBuffer = new PriorityQueue<VariantContextDirectedEvidence>(32, IdsvVariantContext.ByLocationStart);
		private long maxAssembledPosition = Long.MIN_VALUE;
		private long lastFlushedPosition = Long.MIN_VALUE;
		private long lastProgress = 0;
		public ContigAssembler(Iterator<DirectedEvidence> it, File breakendVcf, File realignmentFastq) {
			this.it = it;
			this.breakendVcf = breakendVcf;
			this.realignmentFastq = realignmentFastq;
		}
		@Override
		public void run() {
			try {
				FileSystemContext.getWorkingFileFor(breakendVcf).delete();
				FileSystemContext.getWorkingFileFor(realignmentFastq).delete();
				vcfWriter = processContext.getVariantContextWriter(FileSystemContext.getWorkingFileFor(breakendVcf));
				fastqWriter = new FastqBreakpointWriter(processContext.getFastqWriterFactory().newWriter(FileSystemContext.getWorkingFileFor(realignmentFastq)));
				ReadEvidenceAssembler assembler = getAssembler();
				final ProgressLogger progress = new ProgressLogger(log);
				while (it.hasNext()) {
					DirectedEvidence readEvidence = it.next();
					if (readEvidence instanceof NonReferenceReadPair) {
						progress.record(((NonReferenceReadPair)readEvidence).getLocalledMappedRead());
					} else if (readEvidence instanceof SoftClipEvidence) {
						progress.record(((SoftClipEvidence)readEvidence).getSAMRecord());
					}
					// Need to process assembly evidence first since assembly calls are made when the
					// evidence falls out of scope so processing a given position will emit evidence
					// for a previous position (for it is only at this point we know there is no more
					// evidence for the previous position).
					processAssembly(assembler.addEvidence(readEvidence), resortBuffer, fastqWriter, vcfWriter);
					
					if (maxAssembledPosition / 1000000 > lastProgress / 1000000) {
						lastProgress = maxAssembledPosition;
						log.info(String.format("Assembly at %s:%d %s",
								processContext.getDictionary().getSequence(processContext.getLinear().getReferenceIndex(lastProgress)).getSequenceName(),
								processContext.getLinear().getReferencePosition(lastProgress),
								assembler.getStateSummaryMetrics()));
					}
				}
				processAssembly(assembler.endOfEvidence(), resortBuffer, fastqWriter, vcfWriter);
				flushWriterQueueBefore(Long.MAX_VALUE, resortBuffer, fastqWriter, vcfWriter);
				fastqWriter.close();
				vcfWriter.close();
				FileHelper.move(FileSystemContext.getWorkingFileFor(breakendVcf), breakendVcf, true);
				FileHelper.move(FileSystemContext.getWorkingFileFor(realignmentFastq), realignmentFastq, true);
			} catch (IOException e) {
				log.error(e, "Error assembling breakend ", breakendVcf);
				throw new RuntimeException("Error assembling breakend", e);
			} finally {
				if (fastqWriter != null) fastqWriter.close();
				if (vcfWriter != null) vcfWriter.close();
			}
		}
		private void processAssembly(
				Iterable<VariantContextDirectedEvidence> evidenceList,
				Queue<VariantContextDirectedEvidence> buffer,
				FastqBreakpointWriter fastqWriter,
				VariantContextWriter vcfWriter) {
	    	if (evidenceList != null) {
		    	for (VariantContextDirectedEvidence a : evidenceList) {
		    		if (a != null) {
			    		maxAssembledPosition = Math.max(maxAssembledPosition, processContext.getLinear().getLinearCoordinate(a.getReferenceIndex(), a.getStart()));
			    		if (Defaults.WRITE_FILTERED_CALLS || !a.isFiltered()) {
			    			buffer.add(a);
			    		}
		    		}
		    	}
	    	}
	    	flushWriterQueueBefore(
	    			maxAssembledPosition - (long)((processContext.getAssemblyParameters().maxSubgraphFragmentWidth + 2) * getMaxConcordantFragmentSize()),
	    			buffer,
	    			fastqWriter,
	    			vcfWriter);
	    }
		private void flushWriterQueueBefore(
				long flushBefore,
				Queue<VariantContextDirectedEvidence> buffer,
				FastqBreakpointWriter fastqWriter,
				VariantContextWriter vcfWriter) {
			while (!buffer.isEmpty() && processContext.getLinear().getLinearCoordinate(buffer.peek().getReferenceIndex(), buffer.peek().getStart()) < flushBefore) {
				long pos = processContext.getLinear().getLinearCoordinate(buffer.peek().getReferenceIndex(), buffer.peek().getStart());
				VariantContextDirectedEvidence evidence = buffer.poll();
				if (pos < lastFlushedPosition) {
					log.error(String.format("Sanity check failure: assembly breakend %s written out of order.", evidence.getID()));
				}
				lastFlushedPosition = pos;
				if (Defaults.WRITE_FILTERED_CALLS || !evidence.isFiltered()) {
					vcfWriter.add(evidence);
				}
				if (!evidence.isFiltered() && processContext.getRealignmentParameters().shouldRealignBreakend(evidence)) {
					fastqWriter.write(evidence);
				}
			}
		}
	}
	private ReadEvidenceAssembler getAssembler() {
    	switch (processContext.getAssemblyParameters().method) {
	    	case DEBRUIJN_PER_POSITION:
	    		return new DeBruijnAnchoredAssembler(processContext, this);
	    	case DEBRUIJN_SUBGRAPH:
	    		return new DeBruijnSubgraphAssembler(processContext, this);
	    	default:
	    		throw new IllegalArgumentException("Unknown assembly method.");
    	}
    }
	@Override
	public int getMaxConcordantFragmentSize() {
		return maxSourceFragSize;
	}
}